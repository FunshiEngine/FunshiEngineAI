package app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.core.ChatNode
import app.core.ChatRow
import app.core.ChatTree
import app.core.Screen
import app.core.UiAttachment
import app.core.UiCitation
import app.core.UiDocsFile
import app.core.UiMessage
import app.core.UiProgress
import app.core.UiToolCall
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import llm.api.OpenAiCompatibleGateway
import llm.catalog.HfGgufFile
import llm.catalog.HfRepoHit
import llm.catalog.HuggingFaceDownloader
import llm.catalog.ModelEntry
import llm.catalog.ModelManager
import llm.catalog.ModelOpProgress
import llm.config.ApiPreset
import llm.config.ChatMessage
import llm.config.LlmMode
import llm.config.ModelPreset
import llm.error.LlmException
import llm.service.LlmService
import rag.agent.AgentEvent
import rag.agent.AgentLoop
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest
import rag.agent.ToolContext
import rag.agent.ToolRegistry
import rag.config.AppConfig
import rag.config.AppPaths
import rag.config.ColorProfile
import rag.engine.RagEngine
import rag.mcp.DefaultMcpConnector
import rag.mcp.McpManager
import rag.retrieval.KnowledgeBase
import rag.store.ChatStore
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Estado y flujo del chat: qué se ve en pantalla y qué se le pide al modelo.
 *
 * Este archivo es el núcleo (estado, tema y envío). Las operaciones se
 * reparten en archivos siblings como funciones de extensión sobre el
 * ViewModel, que sigue siendo el dueño del estado:
 *
 *  · [ChatLibrary] — árbol de chats/carpetas y persistencia.
 *  · [ChatEngine] — arranque del motor, gateway y documentación.
 *  · [ChatCatalog] — catálogo de modelos y de APIs remotas.
 *  · [ChatCommands] — comandos de barra (`/help`, `/model`, `/tools`…).
 */
class ChatViewModel {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal var config = AppConfig.loadOrCreate()
    // Ambos servicios leen la config EN CADA ARRANQUE vía lambda ({ config.llm }):
    // si reciben la copia del constructor, activar una API o modelo no tiene efecto
    // y el arranque sigue exigiendo el modelo local (localhost) de la config inicial.
    internal val llmService = LlmService({ config.llm }, AppPaths.home)
    internal val models = ModelManager({ config.llm }, AppPaths.home) { updated ->
        config = config.copy(llm = updated).save()
    }
    internal val kb = KnowledgeBase(config.rag)
    internal var engine: RagEngine? = null
    /**
     * Herramientas disponibles para el modelo (el AgentLoop las ejecuta).
     *
     * Se arma con la configuración actual (ver `rebuildLocalTools`): los topes
     * de tamaño, el motor de LaTeX y el interruptor general salen de la config.
     */
    internal val tools = ToolRegistry()
    /** Servidores MCP (F7/F8): estado y herramientas descubiertas. */
    internal var mcp = McpManager(DefaultMcpConnector(config.mcp.timeoutMs), config.mcp.servers)
    internal var chatJob: Job? = null
    /** Cola de mensajes pendientes mientras no hay engine listo. */
    internal val pendingMessages = mutableListOf<Pair<String, List<ChatMessage>>>()

    var screen by mutableStateOf(Screen.CHAT)  // ¡Chat visible YA!
        internal set
    var messages by mutableStateOf(listOf<UiMessage>())
        internal set
    var status by mutableStateOf("Inicializando modelo en segundo plano…")
        internal set
    var ready by mutableStateOf(false)
        internal set
    var busy by mutableStateOf(false)
        internal set
    /** Hay mensajes esperando en cola (modelo aún cargando) → se puede cancelar el envío. */
    var queued by mutableStateOf(false)
        internal set
    /** Mensajes escritos mientras la IA responde (se envían juntos al terminar). */
    var queuedMessages by mutableStateOf(listOf<String>())
        internal set
    /** Error visible en banner (distinto del status de carga). */
    var error by mutableStateOf("")
        internal set
    /** Progreso de la operación en curso (descarga de modelo o arranque). */
    var progress by mutableStateOf<UiProgress?>(null)
        internal set
    var modelEntries by mutableStateOf(listOf<ModelEntry>())
        internal set
    var apiPresets by mutableStateOf(listOf<ApiPreset>())
        internal set
    var activeApiId by mutableStateOf<String?>(null)
        internal set
    var activeModelLabel by mutableStateOf("")
        internal set
    /** Puerto del servidor local (editable desde la pestaña Motor). */
    var localPort by mutableStateOf(config.llm.localPort)
        internal set
    /** Compilar llama.cpp si falta el binario (editable desde la pestaña Motor). */
    var buildFromSource by mutableStateOf(config.llm.buildFromSource)
        internal set
    /** true mientras se prueba una conexión de API ("Probar conexión"). */
    var testing by mutableStateOf(false)
        internal set
    /** Resultado de la última prueba de conexión (texto para la UI). */
    var testResult by mutableStateOf("")
        internal set
    /** Tema oscuro (persistido en app-config.json). */
    var isDark by mutableStateOf(config.llm.uiDark)
        internal set
    /** Color de acento en ARGB Int (persistido en app-config.json). */
    var accentColor by mutableStateOf(config.llm.uiAccent)
        internal set
    /** Nivel de thinking/razonamiento activo ("off"|"low"|"medium"|"high"). */
    var thinking by mutableStateOf(config.llm.thinking)
        internal set
    /** Potencia de respuesta activa ("low"|"medium"|"high"). */
    var power by mutableStateOf(config.llm.power)
        internal set
    /** Perfiles de color guardados por el usuario. */
    var colorProfiles by mutableStateOf(config.uiColorProfiles)
        internal set
    /** Árbol de chats/carpetas (modelo en memoria del sidebar). */
    internal val tree = ChatTree()
    /** Filas visibles del sidebar (árbol aplanado según carpetas expandidas). */
    var chatRows by mutableStateOf(listOf<ChatRow>())
        internal set
    /** Ids de las carpetas actualmente expandidas. */
    var expandedIds by mutableStateOf(setOf<String>())
        internal set
    /** Id del chat activo. */
    var activeChatId by mutableStateOf("")
        internal set
    /** Modo del chat: "GENERAL" (libre) u "DOCS" (RAG sobre documentación). */
    var chatMode by mutableStateOf(config.llm.chatMode)
        internal set
    /**
     * Petición de permiso esperando respuesta del usuario (null = no hay
     * ninguna). La UI la muestra como diálogo y la responde con
     * [answerPermission].
     */
    var permissionRequest by mutableStateOf<PermissionRequest?>(null)
        internal set
    /** Respuesta pendiente de la petición de permiso (la resuelve la UI). */
    internal var permissionAnswer: CompletableDeferred<PermissionDecision>? = null

    init {
        // Carga el árbol guardado y activa el primer chat (o crea uno vacío).
        tree.loadFrom(ChatStore.load())
        if (tree.firstChatId() == null) {
            tree.addChild(null, ChatNode.Chat(newChatId(), "Chat nuevo"))
        }
        activeChatId = tree.firstChatId() ?: ""
        messages = (tree.nodeOf(activeChatId) as? ChatNode.Chat)
            ?.messages?.map { ChatMessages.toUi(it) } ?: emptyList()
        refreshRows()
        refreshLists()
        // Las herramientas dependen de la config (topes, motor de LaTeX).
        rebuildLocalTools()
        // Arranque en background: NO bloquea la UI
        scope.launch { boot() }
    }

    internal fun newChatId(): String = "chat-${System.currentTimeMillis()}-${(0..9999).random()}"

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

    fun send(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        // La IA está respondiendo: el texto se encola y se enviará junto con
        // los demás en un solo mensaje (uno debajo del otro) al terminar.
        if (busy) {
            queuedMessages = queuedMessages + trimmed
            status = "${queuedMessages.size} mensaje(s) en cola — se enviarán juntos al terminar la respuesta."
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

    /** Lógica interna de envío (engine garantizado no-nulo y ready=true). */
    internal fun sendInternal(text: String, history: List<ChatMessage>) {
        val eng = engine!!
        // Chat al que pertenece esta respuesta: si mientras generamos se borra
        // o cambia de chat, los callbacks NO tocan el chat nuevo (sólo busy).
        val originChatId = activeChatId
        busy = true
        error = ""
        testResult = ""
        chatJob?.cancel()
        chatJob = scope.launch {
            try {
                val useDocs = chatMode == "DOCS"
                val prepared = withContext(Dispatchers.IO) { eng.prepare(text, history, useDocs) }
                val sourceLabels = prepared.sources.map { it.label }.distinct()
                // Camino nativo (`tool_calls` del protocolo OpenAI) sólo REMOTO;
                // LOCAL usa catálogo por prompt con bloque delimitado.
                val native = config.llm.mode == LlmMode.REMOTE
                val loop = AgentLoop(eng.gateway, tools, native, config.tools.timeoutMs)
                var cards = listOf<UiToolCall>()
                var attachments = listOf<UiAttachment>()
                val sb = StringBuilder()

                // Contexto de herramientas: carpeta de salidas del chat,
                // permisos ya concedidos en ese chat y la vía para preguntar.
                val ctx = ToolContext(
                    chatId = originChatId,
                    outputsDir = outputsDirFor(originChatId),
                    grantedCategories = grantedCategories(originChatId),
                    requestPermission = { req -> askPermission(req) },
                )

                val answer = withContext(Dispatchers.IO) {
                    loop.run(prepared.messages, ctx) { ev ->
                        when (ev) {
                            is AgentEvent.Text -> {
                                sb.append(ev.delta)
                                if (activeChatId == originChatId) {
                                    updateLast(sb.toString(), sourceLabels, cards, attachments)
                                }
                            }
                            is AgentEvent.ToolStart -> {
                                cards = cards + UiToolCall(ev.name, ev.argsJson, null, "")
                                if (activeChatId == originChatId) {
                                    updateLast(sb.toString(), sourceLabels, cards, attachments)
                                }
                            }
                            is AgentEvent.ToolEnd -> {
                                val i = cards.indexOfLast { it.name == ev.name && it.ok == null }
                                cards = if (i >= 0) {
                                    cards.withIndex().map { (j, c) ->
                                        if (j == i) c.copy(ok = ev.ok, result = ev.result) else c
                                    }
                                } else {
                                    cards + UiToolCall(ev.name, "", ev.ok, ev.result)
                                }
                                // Archivos producidos por la herramienta (adjuntos).
                                if (ev.artifacts.isNotEmpty()) {
                                    attachments = attachments + ev.artifacts.map {
                                        UiAttachment(it.name, it.path, it.sizeBytes, it.mime)
                                    }
                                }
                                if (activeChatId == originChatId) {
                                    updateLast(sb.toString(), sourceLabels, cards, attachments)
                                }
                            }
                        }
                    }
                }
                if (activeChatId == originChatId) {
                    // Modo Docs: se comprueba que las citas estén respaldadas
                    // por la fuente que citan (F9). Es determinista: no llama
                    // al modelo ni a la red.
                    val citation = if (useDocs && prepared.sources.isNotEmpty()) {
                        val report = eng.verify(answer.text, prepared.sources)
                        UiCitation(report.verified, report.unsupported, report.unknownCount)
                    } else {
                        null
                    }
                    updateLast(answer.text, sourceLabels, cards, attachments, citation)
                }
            } catch (e: CancellationException) {
                if (activeChatId == originChatId) updateLast("(cancelado)")
            } catch (e: Exception) {
                if (activeChatId == originChatId) updateLast("⚠ ${friendly(e)}")
            } finally {
                busy = false
                // Sólo persiste si seguimos en el mismo chat; si no, el historial
                // ajeno ya quedó guardado por quien lo cambió/borró.
                if (activeChatId == originChatId) persistActiveChat()
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
        if (queuedMessages.isEmpty()) return
        val combined = queuedMessages.joinToString("\n\n")
        queuedMessages = emptyList()
        send(combined)
    }

    /** Cancela la generación en curso (botón Detener) o desencola mensajes pendientes. */
    fun cancel() {
        // La detención descarta también lo encolado mientras respondía:
        // si el usuario paró, no se dispara nada automático después.
        queuedMessages = emptyList()
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
        if (queuedMessages.isEmpty()) return
        queuedMessages = emptyList()
        status = "Cola descartada."
    }

    /** Quita UN mensaje de la cola (los demás siguen esperando). */
    fun removeQueued(index: Int) {
        if (index !in queuedMessages.indices) return
        queuedMessages = queuedMessages.filterIndexed { i, _ -> i != index }
        if (queuedMessages.isEmpty()) status = "Cola descartada."
    }

    /**
     * Reinicia la respuesta del modelo: quita esa respuesta y reenvía el
     * mensaje del usuario que la provocó (con el historial previo intacto).
     * Corta el turno completo: la respuesta vieja y todo lo posterior quedan descartados.
     */
    fun regenerate(index: Int) {
        if (busy || index !in messages.indices || messages[index].role != "assistant") return
        val userIndex = (index - 1 downTo 0).firstOrNull { messages[it].role == "user" } ?: return
        val userText = messages[userIndex].text
        if (userText.isBlank() || userText == "…") return
        messages = messages.take(userIndex)
        send(userText)
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

    internal fun updateLast(
        text: String,
        sources: List<String> = emptyList(),
        toolCards: List<UiToolCall> = emptyList(),
        attachments: List<UiAttachment> = emptyList(),
        citation: UiCitation? = null,
    ) {
        if (messages.isEmpty()) return
        messages = messages.dropLast(1) +
            messages.last().copy(
                text = text,
                sources = sources,
                toolCalls = toolCards,
                attachments = attachments,
                citation = citation,
            )
    }

    internal fun friendly(e: Throwable): String = when (e) {
        is LlmException -> e.message
        else -> e.message ?: "Error desconocido"
    }

    fun shutdown() {
        scope.cancel()
        llmService.stop()
        // Los procesos de los servidores MCP se cierran: si no, quedan
        // colgados por debajo de la app.
        runBlocking(Dispatchers.IO) { mcp.close() }
    }

    companion object {
        /** Niveles válidos para el comando /thinking (razonamiento). */
        val THINKING_LEVELS = listOf("off", "low", "medium", "high")
        /** Niveles válidos para el comando /power (potencia de respuesta). */
        val POWER_LEVELS = listOf("low", "medium", "high")
    }
}
