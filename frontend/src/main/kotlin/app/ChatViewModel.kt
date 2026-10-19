package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import llm.ApiPreset
import llm.ChatMessage
import llm.HfGgufFile
import llm.HfRepoHit
import llm.HuggingFaceDownloader
import llm.LlmException
import llm.LlmMode
import llm.LlmService
import llm.ModelEntry
import llm.ModelManager
import llm.ModelOpProgress
import llm.ModelPreset
import llm.OpenAiCompatibleGateway
import rag.AppConfig
import rag.AppPaths
import rag.ChatStore
import rag.ColorProfile
import rag.KnowledgeBase
import rag.RagEngine
import rag.StoredMessage
import java.nio.file.Files
import java.nio.file.Path

class ChatViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var config = AppConfig.loadOrCreate()
    // Ambos servicios leen la config EN CADA ARRANQUE vía lambda ({ config.llm }):
    // si reciben la copia del constructor, activar una API o modelo no tiene efecto
    // y el arranque sigue exigiendo el modelo local (localhost) de la config inicial.
    private val llmService = LlmService({ config.llm }, AppPaths.home)
    private val models = ModelManager({ config.llm }, AppPaths.home) { updated ->
        config = config.copy(llm = updated).save()
    }
    private val kb = KnowledgeBase(config.rag)
    private var engine: RagEngine? = null
    private var chatJob: Job? = null
    /** Cola de mensajes pendientes mientras no hay engine listo. */
    private val pendingMessages = mutableListOf<Pair<String, List<ChatMessage>>>()
    /** Textos escritos mientras la IA responde; se envían juntos al terminar. */
    private val sendQueue = mutableListOf<String>()

    var screen by mutableStateOf(Screen.CHAT)  // ¡Chat visible YA!
        private set
    var messages by mutableStateOf(listOf<UiMessage>())
        private set
    var status by mutableStateOf("Inicializando modelo en segundo plano…")
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Hay mensajes esperando en cola (modelo aún cargando) → se puede cancelar el envío. */
    var queued by mutableStateOf(false)
        private set
    /** Mensajes escritos mientras la IA responde (se envían juntos al terminar). */
    var queuedCount by mutableStateOf(0)
        private set
    /** Error visible en banner (distinto del status de carga). */
    var error by mutableStateOf("")
        private set
    /** Progreso de la operación en curso (descarga de modelo o arranque). */
    var progress by mutableStateOf<UiProgress?>(null)
        private set
    var modelEntries by mutableStateOf(listOf<ModelEntry>())
        private set
    var apiPresets by mutableStateOf(listOf<ApiPreset>())
        private set
    var activeApiId by mutableStateOf<String?>(null)
        private set
    var activeModelLabel by mutableStateOf("")
        private set
    /** Puerto del servidor local (editable desde la pestaña Motor). */
    var localPort by mutableStateOf(config.llm.localPort)
        private set
    /** Compilar llama.cpp si falta el binario (editable desde la pestaña Motor). */
    var buildFromSource by mutableStateOf(config.llm.buildFromSource)
        private set
    /** true mientras se prueba una conexión de API ("Probar conexión"). */
    var testing by mutableStateOf(false)
        private set
    /** Resultado de la última prueba de conexión (texto para la UI). */
    var testResult by mutableStateOf("")
        private set
    /** Tema oscuro (persistido en app-config.json). */
    var isDark by mutableStateOf(config.llm.uiDark)
        private set
    /** Color de acento en ARGB Int (persistido en app-config.json). */
    var accentColor by mutableStateOf(config.llm.uiAccent)
        private set
    /** Nivel de thinking/razonamiento activo ("off"|"low"|"medium"|"high"). */
    var thinking by mutableStateOf(config.llm.thinking)
        private set
    /** Potencia de respuesta activa ("low"|"medium"|"high"). */
    var power by mutableStateOf(config.llm.power)
        private set
    /** Perfiles de color guardados por el usuario. */
    var colorProfiles by mutableStateOf(config.uiColorProfiles)
        private set
    /** Árbol de chats/carpetas (modelo en memoria del sidebar). */
    private val tree = ChatTree()
    /** Filas visibles del sidebar (árbol aplanado según carpetas expandidas). */
    var chatRows by mutableStateOf(listOf<ChatRow>())
        private set
    /** Ids de las carpetas actualmente expandidas. */
    var expandedIds by mutableStateOf(setOf<String>())
        private set
    /** Id del chat activo. */
    var activeChatId by mutableStateOf("")
        private set
    /** Modo del chat: "GENERAL" (libre) u "DOCS" (RAG sobre documentación). */
    var chatMode by mutableStateOf(config.llm.chatMode)
        private set

    init {
        // Carga el árbol guardado y activa el primer chat (o crea uno vacío).
        tree.loadFrom(ChatStore.load())
        if (tree.firstChatId() == null) {
            tree.addChild(null, ChatNode.Chat(newChatId(), "Chat nuevo"))
        }
        activeChatId = tree.firstChatId() ?: ""
        messages = (tree.nodeOf(activeChatId) as? ChatNode.Chat)
            ?.messages?.map { UiMessage(it.role, it.text, it.sources) } ?: emptyList()
        refreshRows()
        refreshLists()
        // Arranque en background: NO bloquea la UI
        scope.launch { boot() }
    }

    private fun newChatId(): String = "chat-${System.currentTimeMillis()}-${(0..9999).random()}"

    /** Cambia el tema y lo persiste. */
    fun setTheme(dark: Boolean, accent: Int) {
        isDark = dark
        accentColor = accent
        config = config.copy(llm = config.llm.copy(uiDark = dark, uiAccent = accent)).save()
    }

    /** Cambia el nivel de thinking (razonamiento) y lo persiste. */
    fun changeThinking(level: String) {
        val l = level.lowercase().let { if (it in THINKING_LEVELS) it else "off" }
        thinking = l
        config = config.copy(llm = config.llm.copy(thinking = l)).save()
        restartGateway()
    }

    /** Cambia la potencia de respuesta y la persiste. */
    fun changePower(level: String) {
        val l = level.lowercase().let { if (it in POWER_LEVELS) it else "medium" }
        power = l
        config = config.copy(llm = config.llm.copy(power = l)).save()
        restartGateway()
    }

    /**
     * Reinicia sólo el gateway (sin reindexar ni descargar nada) para que los
     * cambios de thinking/power apliquen al siguiente mensaje. Sólo aplica en
     * REMOTE: el servidor local no entiende `reasoning_effort`.
     */
    private fun restartGateway() {
        if (config.llm.mode != LlmMode.REMOTE) return
        val c = config.llm
        if (c.remoteBaseUrl.isBlank()) return
        try {
            llmService.stop()
            val gw = OpenAiCompatibleGateway(
                c.remoteBaseUrl, c.apiKey, c.modelName, c.temperature, c.maxTokens,
                c.chatTimeoutMs, c.thinking, c.power,
            )
            engine = RagEngine(config.rag, kb, gw)
            ready = true
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    // ---- perfiles de color ----

    /** Guarda el tema+accento actual como perfil con el nombre dado. */
    fun saveColorProfile(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val profile = ColorProfile(n, isDark, accentColor)
        val list = (config.uiColorProfiles.filterNot { it.name.equals(n, true) } + profile)
        colorProfiles = list
        config = config.copy(uiColorProfiles = list).save()
    }

    /** Aplica un perfil de color guardado. */
    fun applyColorProfile(profile: ColorProfile) {
        setTheme(profile.dark, profile.accent)
    }

    /** Borra un perfil de color por nombre. */
    fun deleteColorProfile(name: String) {
        val list = config.uiColorProfiles.filterNot { it.name.equals(name, true) }
        colorProfiles = list
        config = config.copy(uiColorProfiles = list).save()
    }

    // ---- árbol de chats/carpetas ----

    /** Recalcula las filas visibles del sidebar a partir del árbol. */
    private fun refreshRows() {
        chatRows = tree.flatten(expandedIds, activeChatId)
    }

    /** Guarda el árbol completo en disco. */
    private fun persistTree() {
        ChatStore.save(tree.toStored())
    }

    /** Guarda los mensajes del chat activo en el árbol y en disco. */
    private fun persistActiveChat() {
        val chat = tree.nodeOf(activeChatId) as? ChatNode.Chat ?: return
        val stored = messages
            .filter { it.text.isNotBlank() && it.text != "…" }
            .map { StoredMessage(it.role, it.text, it.sources) }
        // El nombre sigue derivándose del primer mensaje sólo si el usuario
        // nunca lo renombró a mano.
        if (chat.autoName) {
            stored.firstOrNull { it.role == "user" }
                ?.text?.lineSequence()?.firstOrNull()?.take(40)?.ifBlank { null }
                ?.let { chat.name = it }
        }
        chat.messages = stored
        persistTree()
        refreshRows()
    }

    /** Crea un chat nuevo bajo [parentId] (null = raíz) y lo deja activo. */
    fun newChat(parentId: String? = null) {
        persistActiveChat()
        val id = newChatId()
        tree.addChild(parentId, ChatNode.Chat(id, "Chat nuevo"))
        activeChatId = id
        messages = emptyList()
        error = ""
        testResult = ""
        persistTree()
        refreshRows()
    }

    /** Crea una carpeta nueva bajo [parentId] (null = raíz) y la deja expandida. */
    fun newFolder(parentId: String? = null) {
        persistActiveChat()
        val id = newChatId()
        tree.addChild(parentId, ChatNode.Folder(id, "Carpeta nueva"))
        expandedIds = expandedIds + id
        persistTree()
        refreshRows()
    }

    /** Alterna el expandido/colapsado de la carpeta [id]. */
    fun toggleFolder(id: String) {
        expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
        refreshRows()
    }

    /**
     * Renombra un chat o carpeta. El chat renombrado deja de auto-titularse
     * por el primer mensaje.
     */
    fun renameNode(id: String, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) return
        val node = tree.nodeOf(id) ?: return
        node.name = n
        if (node is ChatNode.Chat) node.autoName = false
        persistTree()
        refreshRows()
    }

    /** ¿El nodo [id] (o su subárbol) contiene al chat activo? */
    fun containsActive(id: String): Boolean =
        activeChatId == id || tree.isSelfOrDescendant(activeChatId, id)

    /** Mueve el nodo [id] dentro del árbol (drag & drop). */
    fun moveNode(id: String, newParentId: String?, index: Int) {
        if (id == newParentId || (newParentId != null && tree.isSelfOrDescendant(newParentId, id))) {
            error = "No podés soltar una carpeta dentro de sí misma."
            return
        }
        tree.move(id, newParentId, index)
        persistTree()
        refreshRows()
    }

    /**
     * Suelta el nodo [draggedId] sobre [targetId]: si el destino es una carpeta
     * entra al FINAL de esa carpeta (y se expande); si es un chat, queda justo
     * después de él en el mismo nivel.
     */
    fun dropOnto(draggedId: String, targetId: String, intoFolder: Boolean) {
        if (busy || draggedId == targetId) return
        if (intoFolder) {
            moveNode(draggedId, targetId, Int.MAX_VALUE)
            expandedIds = expandedIds + targetId
            refreshRows()
        } else {
            val parentId = tree.parentIdOf(targetId)
            val siblings = tree.childrenOf(parentId)
            val index = siblings.indexOfFirst { it.id == targetId } + 1
            moveNode(draggedId, parentId, index)
        }
    }

    /** (chats, carpetas) del subárbol [id], para el diálogo de confirmación. */
    fun subtreeCounts(id: String): Pair<Int, Int> = tree.subtreeCounts(id)

    fun nodeIsFolder(id: String): Boolean = tree.isFolder(id)

    fun nodeName(id: String): String = tree.nameOf(id)

    /** Borra el subárbol [id]. Si el chat activo estaba ahí, salta a otro. */
    fun deleteNode(id: String) {
        if (busy) return
        val wasInside = containsActive(id)
        tree.remove(id)
        // Garantiza que siempre quede al menos un chat visible.
        if (tree.firstChatId() == null) {
            tree.addChild(null, ChatNode.Chat(newChatId(), "Chat nuevo"))
        }
        if (wasInside) {
            activeChatId = tree.firstChatId() ?: ""
            messages = (tree.nodeOf(activeChatId) as? ChatNode.Chat)
                ?.messages?.map { UiMessage(it.role, it.text, it.sources) } ?: emptyList()
            error = ""
            testResult = ""
        }
        persistTree()
        refreshRows()
    }

    /** Cambia al chat con el id dado (guarda el actual y carga el destino). */
    fun switchChat(id: String) {
        if (id == activeChatId || busy) return
        val target = tree.nodeOf(id) as? ChatNode.Chat ?: return
        persistActiveChat()
        activeChatId = id
        messages = target.messages.map { UiMessage(it.role, it.text, it.sources) }
        error = ""
        testResult = ""
        refreshRows()
    }

    /** Cambia el modo del chat (GENERAL u DOCS) y lo persiste. */
    fun changeChatMode(mode: String) {
        val m = if (mode == "DOCS") "DOCS" else "GENERAL"
        chatMode = m
        config = config.copy(llm = config.llm.copy(chatMode = m)).save()
    }

    /** Modo de inferencia actual (LOCAL o REMOTE). */
    val configMode: String
        get() = config.llm.mode.name

    /** Cambia el modo de inferencia. En REMOTE exige que exista una API guardada. */
    fun setConfigMode(mode: String) {
        val newMode = LlmMode.valueOf(mode)
        if (newMode == LlmMode.LOCAL) {
            useLocal()
            return
        }
        // REMOTE: activa la API activa guardada (o la primera). Si no hay ninguna,
        // NO cambia el modo: evita el estado roto que apuntaba a un localhost vacío.
        val target = models.activeApi() ?: config.llm.apiPresets.firstOrNull()
        if (target != null) {
            activateApi(target)
        } else {
            error = "No hay ninguna API guardada. Completá nombre, URL y clave en el " +
                "formulario de API remota y pulsá \"Guardar y usar\"."
        }
    }

    fun go(target: Screen) {
        error = ""
        testResult = ""
        screen = target
        if (target == Screen.MODELS || target == Screen.APIS || target == Screen.SETTINGS) refreshLists()
    }

    fun dismissError() {
        error = ""
        testResult = ""
    }

    private fun refreshLists() {
        modelEntries = models.entries()
        apiPresets = models.apis
        activeApiId = models.activeApi()?.id
        activeModelLabel = "${config.llm.hfRepo}/${config.llm.hfFile}"
        localPort = config.llm.localPort
        buildFromSource = config.llm.buildFromSource
    }

    /**
     * Arranque en background con progreso por fases visibles en la UI:
     *  1) indexar docs →  2) descargar el modelo (%, MB/MB) →  3) clonar/compilar
     * llama.cpp si hace falta (%, sólo si no está) →  4) cargar el modelo en memoria (%).
     * Al terminar vuelve a la pantalla desde la que se lanzó (o al chat).
     */
    private suspend fun boot() {
        // Pantalla a la que al terminar; si el usuario ya navegó, se respeta.
        val back = if (screen == Screen.LOADING) Screen.CHAT else screen
        screen = Screen.LOADING
        try {
            status = "Indexando documentación…"
            progress = UiProgress("Indexando documentación…", percent = null)
            kb.motorDocsDir = docsDirFor(activeMotorId())
            kb.sync { status = it }

            val file = config.llm.hfFile
            status = if (config.llm.mode == LlmMode.LOCAL && !modelAlreadyOnDisk()) {
                "Descargando $file…"
            } else {
                "Preparando $file…"
            }
            progress = UiProgress(status, percent = null)

            val llm = llmService.start(
                onStatus = { status = it },
                onDownload = { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else null
                    val size = if (total > 0) "${done shr 20} / ${total shr 20} MB" else "${done shr 20} MB"
                    status = "Descargando $file — $size" + (pct?.let { " · $it%" } ?: "")
                    progress = UiProgress("Descargando $file", done, total, pct)
                },
                onSetup = { setup ->
                    // Etapas de llama.cpp (clonar/compilar) y carga en memoria, con su %.
                    status = setup.message
                    progress = UiProgress(setup.message, percent = setup.percent)
                },
            )
            progress = null
            engine = RagEngine(config.rag, kb, llm)
            ready = true
            status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
            if (screen == Screen.LOADING) screen = back
            // Procesar mensajes encolados mientras el modelo cargaba
            processPendingMessages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress = null
            error = friendly(e)
            status = if (config.llm.mode == LlmMode.REMOTE) {
                "No se pudo usar la API: revisa URL/clave en la pestaña Motor."
            } else {
                "No se pudo arrancar el modelo: revisa Modelos (descarga) o la compilación de llama.cpp."
            }
            // screen se queda en LOADING para mostrar "Reintentar"; si el usuario
            // ya navegó a otra pantalla, se respeta su elección.
        }
    }

    /** ¿El modelo activo ya está en disco y pesa > 0? (evita decir "Descargando" si no toca). */
    private fun modelAlreadyOnDisk(): Boolean =
        models.entries().firstOrNull { it.active }?.downloaded == true

    /** Procesa la cola de mensajes pendientes tras inicializar el engine. */
    private fun processPendingMessages() {
        if (pendingMessages.isNotEmpty() && engine != null) {
            val queue = pendingMessages.toList()
            pendingMessages.clear()
            queued = false
            for ((text, history) in queue) {
                sendInternal(text, history)
            }
        }
    }

    /** Reintenta el arranque (botón "Reintentar" de la pantalla de carga). */
    fun retryBoot() {
        if (busy) return
        error = ""
        testResult = ""
        scope.launch { boot() }
    }

    fun reindex() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                kb.motorDocsDir = docsDirFor(activeMotorId())
                kb.sync { status = it }
                status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    // ---- documentación por motor (tarea 4) ----

    /**
     * Id del motor cuya documentación se administra: la API activa en REMOTE,
     * o "local" para el modelo local. Cada motor tiene su carpeta propia en
     * `app-home/api-docs/<id>/` (los archivos se COPIAN ahí, así que la
     * persistencia no depende de rutas del sistema operativo).
     */
    fun activeMotorId(): String =
        if (config.llm.mode == LlmMode.REMOTE) (activeApiId ?: "remote") else "local"

    /** Carpeta de documentación de un motor. */
    fun docsDirFor(motorId: String): Path = AppPaths.resolve("api-docs/$motorId")

    /** Archivos documentales de un motor: nombre, extensión y peso. */
    fun listDocs(motorId: String): List<UiDocsFile> {
        val dir = docsDirFor(motorId)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { p ->
                    val name = p.fileName.toString()
                    UiDocsFile(name, name.substringAfterLast('.', "").lowercase(), Files.size(p))
                }
                .sorted { a, b -> a.name.lowercase().compareTo(b.name.lowercase()) }
                .toList()
        }
    }

    /** Copia archivos elegidos en el SO a la carpeta del motor (sin pisar nombres). */
    fun addDocsFiles(motorId: String, sources: List<Path>) {
        if (sources.isEmpty()) return
        val dir = docsDirFor(motorId)
        try {
            Files.createDirectories(dir)
            for (src in sources) {
                var target = dir.resolve(src.fileName.toString())
                if (Files.exists(target)) {
                    // Mismo nombre: sufijo incremental para no pisar otro archivo.
                    val base = src.fileName.toString().substringBeforeLast('.')
                    val ext = src.fileName.toString().substringAfterLast('.', "")
                    var n = 2
                    while (Files.exists(target)) {
                        target = dir.resolve(if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext")
                        n++
                    }
                }
                Files.copy(src, target)
            }
            resyncDocsAfterChange(motorId)
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    /** Elimina un archivo documental del motor. */
    fun removeDocsFile(motorId: String, fileName: String) {
        try {
            Files.deleteIfExists(docsDirFor(motorId).resolve(fileName))
            resyncDocsAfterChange(motorId)
        } catch (e: Exception) {
            error = friendly(e)
        }
    }

    /**
     * Reindexa tras agregar/quitar archivos. Sólo hace falta si el motor es el
     * activo (los demás se indexarán cuando se activen).
     */
    private fun resyncDocsAfterChange(motorId: String) {
        if (motorId != activeMotorId()) return
        scope.launch {
            try {
                kb.motorDocsDir = docsDirFor(motorId)
                kb.sync { status = it }
                status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
            } catch (e: Exception) {
                error = friendly(e)
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        // La IA está respondiendo: el texto se encola y se enviará junto con
        // los demás en un solo mensaje (uno debajo del otro) al terminar.
        if (busy) {
            sendQueue.add(trimmed)
            queuedCount = sendQueue.size
            status = "$queuedCount mensaje(s) en cola — se enviarán juntos al terminar la respuesta."
            return
        }
        // Comandos de chat: se procesan localmente, sin tocar el modelo.
        if (trimmed.startsWith("/")) {
            handleCommand(trimmed)
            return
        }
        val history = messages.filter { it.text.isNotEmpty() }.map { ChatMessage(it.role, it.text) }
        // Añadir mensaje del usuario + placeholder del asistente inmediatamente
        messages = messages + UiMessage("user", text) + UiMessage("assistant", "…")
        
        if (engine == null || !ready) {
            // Encolar para cuando el modelo esté listo
            pendingMessages.add(text to history)
            queued = true
            status = "Mensaje encolado — el modelo se está cargando…"
            return
        }
        
        sendInternal(text, history)
    }

    /**
     * Procesa los comandos de chat: /help, /model, /thinking y /power.
     * Se ejecutan sin pasar por el modelo (respuesta local inmediata).
     */
    private fun handleCommand(raw: String) {
        messages = messages + UiMessage("user", raw) + UiMessage("assistant", "")
        val parts = raw.removePrefix("/").split(Regex("\\s+"))
        val cmd = parts.firstOrNull().orEmpty().lowercase()
        val arg = parts.getOrNull(1)?.lowercase().orEmpty()
        val reply = when (cmd) {
            "help" -> cmdHelp()
            "model" -> cmdModel(arg)
            "thinking" -> cmdThinking(arg)
            "power" -> cmdPower(arg)
            else -> "Comando no reconocido: `$raw`.\n\n" + cmdHelp()
        }
        updateLast(reply)
        persistActiveChat()
    }

    /** /help — lista de comandos disponibles y qué hace cada uno. */
    private fun cmdHelp(): String = """
        Comandos disponibles (se ejecutan al instante, sin tocar el modelo):

        · `/help` — muestra esta ayuda.
        · `/model` — lista los modelos del servicio activo.
        · `/model <id>` — cambia el modelo activo al indicado.
        · `/thinking [off|low|medium|high]` — nivel de razonamiento del modelo.
        · `/power [low|medium|high]` — potencia de la respuesta (tokens de salida).

        Mientras el modelo responde podés seguir escribiendo: los mensajes
        se encolan y se envían juntos, uno debajo del otro, al terminar.
    """.trimIndent()

    /** /model — lista modelos del servicio activo (o cambia con `/model <id>`). */
    private fun cmdModel(arg: String): String {
        val c = config.llm
        if (c.mode != LlmMode.REMOTE) {
            return "El modo actual es LOCAL. Los modelos se cambian en la pestaña Modelos. " +
                "El activo es `${c.hfFile}`."
        }
        if (c.remoteBaseUrl.isBlank()) {
            return "No hay una API remota activa. Configurala en la pestaña Motor."
        }
        if (arg.isNotEmpty()) {
            // Cambiar modelo: persiste y reinicia el gateway al instante.
            val presets = c.apiPresets.map { if (it.id == c.activeApiId) it.copy(model = arg) else it }
            config = config.copy(llm = c.copy(modelName = arg, apiPresets = presets)).save()
            refreshLists()
            restartGateway()
            return "Modelo cambiado a `$arg`."
        }
        // Listar modelos del endpoint activo.
        scope.launch {
            try {
                val names = withContext(Dispatchers.IO) {
                    val gw = OpenAiCompatibleGateway(
                        c.remoteBaseUrl, c.apiKey, c.modelName, c.temperature, c.maxTokens,
                        20_000, c.thinking, c.power,
                    )
                    try { withTimeout(20_000) { gw.listModels() } } finally { gw.close() }
                }
                val list = names.take(40).joinToString("\n") { "· $it" }
                updateLast(
                    "Modelo activo: `${c.modelName}`\n\n" +
                        "Modelos disponibles en ${c.remoteBaseUrl} (${names.size}):\n$list" +
                        if (names.size > 40) "\n…y ${names.size - 40} más." else "",
                )
            } catch (e: Exception) {
                updateLast("⚠ No pude listar los modelos: ${friendly(e)}")
            }
        }
        return "Consultando los modelos disponibles…"
    }

    /** /thinking [off|low|medium|high] — nivel de razonamiento. */
    private fun cmdThinking(arg: String): String {
        if (arg.isEmpty()) {
            return "Nivel de thinking actual: `${thinking}`.\n" +
                "Cambiá con `/thinking off`, `/thinking low`, `/thinking medium` o `/thinking high`."
        }
        if (arg !in THINKING_LEVELS) {
            return "Nivel inválido: `$arg`. Valores válidos: ${THINKING_LEVELS.joinToString(", ")}."
        }
        changeThinking(arg)
        return if (arg == "off") "Thinking desactivado."
        else "Thinking activado en nivel `$arg`."
    }

    /** /power [low|medium|high] — potencia de la respuesta. */
    private fun cmdPower(arg: String): String {
        if (arg.isEmpty()) {
            return "Potencia actual: `$power`.\n" +
                "Cambiá con `/power low`, `/power medium` o `/power high`."
        }
        if (arg !in POWER_LEVELS) {
            return "Nivel inválido: `$arg`. Valores válidos: ${POWER_LEVELS.joinToString(", ")}."
        }
        changePower(arg)
        return "Potencia de respuesta ajustada a `$arg`."
    }

    /** Lógica interna de envío (engine garantizado no-nulo y ready=true). */
    private fun sendInternal(text: String, history: List<ChatMessage>) {
        val eng = engine!!
        busy = true
        error = ""
        testResult = ""
        chatJob?.cancel()
        chatJob = scope.launch {
            try {
                val useDocs = chatMode == "DOCS"
                val answer = withContext(Dispatchers.IO) {
                    if (useDocs) eng.ask(text, history) else eng.askGeneral(text, history)
                }
                val sb = StringBuilder()
                answer.tokens.collect { sb.append(it); updateLast(sb.toString()) }
                updateLast(sb.toString(), answer.sources.map { it.label }.distinct())
            } catch (e: CancellationException) {
                updateLast("(cancelado)")
            } catch (e: Exception) {
                updateLast("⚠ ${friendly(e)}")
            } finally {
                busy = false
                persistActiveChat()
                // Si el usuario escribió mientras respondíamos, todo lo encolado
                // se envía ahora como UN solo mensaje (uno debajo del otro).
                flushSendQueue()
            }
        }
    }

    /**
     * Une los mensajes encolados mientras la IA respondía y los envía como
     * UN solo texto, con un salto de línea en blanco entre cada uno.
     * (La cancelación limpia la cola antes, así que no se envía nada.)
     */
    private fun flushSendQueue() {
        if (sendQueue.isEmpty()) return
        val combined = sendQueue.joinToString("\n\n")
        sendQueue.clear()
        queuedCount = 0
        send(combined)
    }

    /** Cancela la generación en curso (botón Detener) o desencola mensajes pendientes. */
    fun cancel() {
        // La detención descarta también lo encolado mientras respondía:
        // si el usuario paró, no se dispara nada automático después.
        if (sendQueue.isNotEmpty()) {
            sendQueue.clear()
            queuedCount = 0
        }
        chatJob?.cancel()
        if (pendingMessages.isNotEmpty()) {
            pendingMessages.clear()
            queued = false
            val last = messages.lastOrNull()
            if (last?.role == "assistant" && last.text == "…") updateLast("(cancelado)")
            status = "Envío cancelado."
            persistActiveChat()
        }
    }

    /** Descarta los mensajes encolados mientras la IA responde. */
    fun clearQueue() {
        if (sendQueue.isEmpty()) return
        sendQueue.clear()
        queuedCount = 0
        status = "Cola descartada."
    }

    /**
     * Borra un mensaje del historial. Si es del usuario, también se borra la
     * respuesta del modelo que le sigue (para no dejar un "pregunta sin respuesta").
     */
    fun deleteMessage(index: Int) {
        if (busy || index !in messages.indices) return
        val end = if (
            messages[index].role == "user" &&
            index + 1 < messages.size &&
            messages[index + 1].role == "assistant"
        ) {
            index + 2
        } else {
            index + 1
        }
        messages = messages.filterIndexed { i, _ -> i < index || i >= end }
        persistActiveChat()
    }

    /** Edita el texto de un mensaje de usuario y lo REENVÍA (la respuesta vieja se descarta). */
    fun editAndResend(index: Int, newText: String) {
        val text = newText.trim()
        if (text.isBlank() || busy) return
        if (index !in messages.indices || messages[index].role != "user") return
        // Corta el historial en ese punto y vuelve a enviar: la respuesta anterior
        // y los turnos posteriores quedan descartados (como en los chats típicos).
        messages = messages.take(index)
        send(text)
    }

    private fun updateLast(text: String, sources: List<String> = emptyList()) {
        if (messages.isEmpty()) return
        messages = messages.dropLast(1) + messages.last().copy(text = text, sources = sources)
    }

    // ---- gestor de modelos ----

    /** Descarga un modelo; con [activateAfter] lo activa y reinicia el motor al terminar. */
    fun downloadModel(entry: ModelEntry, activateAfter: Boolean = false) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            testResult = ""
            try {
                val gb = entry.preset.sizeGb
                progress = UiProgress(
                    "Descargando ${entry.preset.label}" + if (gb > 0) " (~${gb} GB)" else "…",
                    percent = 0,
                )
                withContext(Dispatchers.IO) {
                    models.download(entry.preset) { p: ModelOpProgress ->
                        progress = UiProgress(p.label, p.doneBytes, p.totalBytes, p.percent)
                    }
                }
                progress = null
                refreshLists()
                if (activateAfter) {
                    models.activate(entry.preset)
                    refreshLists()
                    ready = false
                    llmService.stop()
                    boot()
                }
            } catch (e: CancellationException) {
                progress = null
                status = "Descarga cancelada."
            } catch (e: Exception) {
                progress = null
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun cancelDownload() {
        models.cancelDownload()
    }

    /** Activa otro modelo descargado: reinicia el servidor local con él. */
    fun activateModel(entry: ModelEntry) {
        if (busy) return
        val sameLocal = config.llm.mode == LlmMode.LOCAL &&
            config.llm.hfRepo == entry.preset.repo && config.llm.hfFile == entry.preset.file
        if (sameLocal && ready) {
            status = "Ese modelo ya está activo."
            return
        }
        scope.launch {
            busy = true
            error = ""
            testResult = ""
            try {
                models.activate(entry.preset)
                refreshLists()
                ready = false
                llmService.stop()
                boot()
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun addModel(repo: String, file: String, label: String) {
        if (repo.isBlank() || file.isBlank()) {
            error = "Indica repo y archivo (ej. unsloth/...-GGUF y *.gguf)."
            return
        }
        val id = "${repo.substringAfterLast("/")}-${file.hashCode().toUInt().toString(36)}"
        models.addModel(ModelPreset(id = id, label = label.ifBlank { file }, repo = repo.trim(), file = file.trim()))
        refreshLists()
    }

    fun removeModel(id: String) {
        models.removeModel(id)
        refreshLists()
    }

    /** Lista los .gguf de un repo HF (ruta completa + peso) para elegir variante. */
    fun listRepoFiles(repo: String, onResult: (List<HfGgufFile>) -> Unit) {
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    HuggingFaceDownloader.listGgufFiles(repo.trim(), config.llm.hfToken)
                }
                onResult(files)
            } catch (e: Exception) {
                error = friendly(e)
                onResult(emptyList())
            }
        }
    }

    /** Busca modelos GGUF en Hugging Face por nombre (búsqueda real, no sólo URL directa). */
    fun searchModels(query: String, onResult: (List<HfRepoHit>) -> Unit) {
        scope.launch {
            try {
                val hits = withContext(Dispatchers.IO) {
                    HuggingFaceDownloader.searchRepos(query, config.llm.hfToken)
                }
                onResult(hits)
            } catch (e: Exception) {
                error = friendly(e)
                onResult(emptyList())
            }
        }
    }

    // ---- colección de APIs ----

    private fun validateApiFields(
        label: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        requireLabel: Boolean = true,
    ): String? {
        val l = label.trim()
        val u = baseUrl.trim()
        val k = apiKey.trim()
        val m = model.trim()
        if (requireLabel && l.isBlank()) return "Falta el nombre de la configuración (label)."
        if (u.isBlank()) return "Falta la URL base (ej. https://api.openai.com/v1)."
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return "La URL base debe empezar con http:// o https://. Si pegaste ahí la clave, ese valor va en \"API key\"."
        }
        if (u == k) return "La URL base y la API key no pueden ser iguales. Revisa en qué campo pegaste cada dato."
        if (m.isBlank()) return "Falta el nombre del modelo (ej. gpt-4o-mini)."
        return null
    }

    fun addApi(label: String, baseUrl: String, apiKey: String, model: String) {
        saveApi(null, label, baseUrl, apiKey, model, activate = false)
    }

    /**
     * Guarda una API de la colección: si [id] coincide con una existente la
     * ACTUALIZA (edición); si no, crea una nueva. Bloquea duplicados (misma
     * URL base + mismo modelo con otro id) para no repetir la misma API.
     */
    fun saveApi(
        id: String?,
        label: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        activate: Boolean,
    ): Boolean {
        val err = validateApiFields(label, baseUrl, apiKey, model) ?: duplicateApiError(id, baseUrl, model)
        if (err != null) {
            error = err
            return false
        }
        val l = label.trim()
        val preset = ApiPreset(
            id = id ?: "api-${l.hashCode().toUInt().toString(36)}-${System.currentTimeMillis() % 10000}",
            label = l,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            model = model.trim(),
        )
        models.addApi(preset) // upsert por id: crea o reemplaza
        if (activate) activateApi(preset) else refreshLists()
        return true
    }

    /** Mensaje de duplicado: misma URL base + mismo modelo guardados con OTRO id. */
    fun duplicateApiError(id: String?, baseUrl: String, model: String): String? {
        val u = baseUrl.trim()
        if (u.isBlank()) return null
        val m = model.trim()
        val duplicated = config.llm.apiPresets.any { it.id != id && it.baseUrl == u && it.model == m }
        return if (duplicated) {
            "Ya guardaste una API con esa URL y ese modelo. Editala desde su tarjeta en vez de duplicarla."
        } else {
            null
        }
    }

    fun removeApi(id: String) {
        models.removeApi(id)
        refreshLists()
    }

    /**
     * Guarda una API en la colección y la activa de inmediato (modo REMOTE).
     */
    fun saveAndActivateApi(label: String, baseUrl: String, apiKey: String, model: String): Boolean {
        val err = validateApiFields(label, baseUrl, apiKey, model)
        if (err != null) {
            error = err
            return false
        }
        val l = label.trim()
        val u = baseUrl.trim()
        val k = apiKey.trim()
        val m = model.trim()
        val preset = ApiPreset(
            id = "api-${l.hashCode().toUInt().toString(36)}-${System.currentTimeMillis() % 10000}",
            label = l,
            baseUrl = u,
            apiKey = k,
            model = m,
        )
        models.addApi(preset)
        activateApi(preset)
        return true
    }

    fun activateApi(preset: ApiPreset) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            testResult = ""
            try {
                models.activateApi(preset)
                refreshLists()
                ready = false
                llmService.stop()
                boot()
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun useLocal() {
        if (busy) return
        val alreadyLocal = config.llm.mode == LlmMode.LOCAL && ready
        if (alreadyLocal) {
            status = "Modelo local ya activo."
            return
        }
        scope.launch {
            busy = true
            error = ""
            testResult = ""
            try {
                models.useLocal()
                refreshLists()
                ready = false
                llmService.stop()
                boot()
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    /** Cambia puerto del servidor local (aplica al próximo arranque). */
    fun changeLocalPort(port: Int) {
        if (port < 1 || port > 65535) {
            error = "El puerto debe estar entre 1 y 65535."
            return
        }
        config = config.copy(llm = config.llm.copy(localPort = port)).save()
        localPort = port
    }

    /** Activa/desactiva la compilación automática de llama.cpp cuando falta. */
    fun changeBuildFromSource(enabled: Boolean) {
        config = config.copy(llm = config.llm.copy(buildFromSource = enabled)).save()
        buildFromSource = enabled
    }

    /** Prueba de conexión a una API remota (GET /models). */
    fun testApi(baseUrl: String, apiKey: String, model: String) {
        val problem = validateApiFields("(prueba)", baseUrl, apiKey, model, requireLabel = false)
        if (problem != null) {
            error = problem
            return
        }
        scope.launch {
            testing = true
            testResult = ""
            error = ""
            try {
                val names = withContext(Dispatchers.IO) {
                    val gw = OpenAiCompatibleGateway(
                        baseUrl.trim(),
                        apiKey.trim(),
                        model.trim(),
                        config.llm.temperature,
                        config.llm.maxTokens,
                        20_000,
                    )
                    try {
                        // withTimeout: si el endpoint no responde, corta a los 20 s
                        // aunque el cliente HTTP se quede esperando (cooperativo).
                        withTimeout(20_000) { gw.listModels() }
                    } finally {
                        gw.close()
                    }
                }
                // Validación del modelo contra la lista real: el 404 típico de
                // Gemini es "ese modelo ya no existe para cuentas nuevas", y se
                // ve ANTES de escribir en el chat.
                val m = model.trim()
                val stripped = names.map { it.removePrefix("models/") }
                val modelOk = m.isBlank() || stripped.any { it.equals(m, ignoreCase = true) }
                testResult = buildString {
                    if (names.isEmpty()) {
                        append("Conexión OK, pero el endpoint no devolvió ningún modelo.")
                    } else {
                        append("Conexión OK: ${names.size} modelo(s). Ej.: ${names.take(3).joinToString(", ")}")
                    }
                    if (m.isNotBlank() && !modelOk) {
                        val sugerido = stripped.firstOrNull { it.startsWith("gemini") || it.startsWith("gpt") }
                        append(
                            "\nEl modelo \"$m\" NO figura en la lista del endpoint " +
                                "(en Gemini esto provoca un HTTP 404 al chatear)." +
                                (if (sugerido != null) " Probá con \"$sugerido\"." else ""),
                        )
                    }
                }
                status = testResult
            } catch (e: TimeoutCancellationException) {
                val msg = "Sin respuesta en 20 s: revisá la URL base y que el endpoint esté accesible."
                error = msg
                testResult = msg
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = friendly(e)
                error = msg
                testResult = msg
            } finally {
                testing = false
            }
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is LlmException -> e.message
        else -> e.message ?: "Error desconocido"
    }

    fun shutdown() {
        scope.cancel()
        llmService.stop()
    }

    companion object {
        /** Niveles válidos para el comando /thinking (razonamiento). */
        val THINKING_LEVELS = listOf("off", "low", "medium", "high")
        /** Niveles válidos para el comando /power (potencia de respuesta). */
        val POWER_LEVELS = listOf("low", "medium", "high")
    }
}
