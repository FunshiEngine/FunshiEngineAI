package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import llm.ApiPreset
import llm.ChatMessage
import llm.HuggingFaceDownloader
import llm.LlmException
import llm.LlmService
import llm.ModelEntry
import llm.ModelManager
import llm.ModelOpProgress
import llm.ModelPreset
import rag.AppConfig
import rag.AppPaths
import rag.KnowledgeBase
import rag.RagEngine

data class UiMessage(val role: String, val text: String, val sources: List<String> = emptyList())

/** Pantalla visible: carga inicial, chat, gestor de modelos o colección de APIs. */
enum class Screen { LOADING, CHAT, MODELS, APIS }

/** Progreso de la descarga/setup visible en la UI (barras). */
data class UiProgress(
    val label: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** 0..100, o null si es indeterminado (clonado inicial, compilación). */
    val percent: Int? = null,
)

/** Tema elegido por el usuario (persistido en app-config.json). */
data class AppTheme(
    val dark: Boolean = false,
    val accent: Int = 0xFF6200EE.toInt(),
)

class ChatViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var config = AppConfig.loadOrCreate()
    private val llmService = LlmService(config.llm, AppPaths.home)
    private val models = ModelManager(config.llm, AppPaths.home) { updated ->
        config = config.copy(llm = updated).save()
    }
    private val kb = KnowledgeBase(config.rag)
    private var engine: RagEngine? = null
    private var chatJob: Job? = null

    var screen by mutableStateOf(Screen.LOADING)
        private set
    var messages by mutableStateOf(listOf<UiMessage>())
        private set
    var status by mutableStateOf("Iniciando…")
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
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
    /** Tema oscuro (persistido en app-config.json). */
    var isDark by mutableStateOf(config.llm.uiDark)
        private set
    /** Color de acento en ARGB Int (persistido en app-config.json). */
    var accentColor by mutableStateOf(config.llm.uiAccent)
        private set

    init {
        refreshLists()
        scope.launch { boot() }
    }

    /** Cambia el tema y lo persiste. */
    fun setTheme(dark: Boolean, accent: Int) {
        isDark = dark
        accentColor = accent
        config = config.copy(llm = config.llm.copy(uiDark = dark, uiAccent = accent)).save()
    }

    fun go(target: Screen) {
        error = ""
        screen = target
        if (target == Screen.MODELS || target == Screen.APIS) refreshLists()
    }

    fun dismissError() {
        error = ""
    }

    private fun refreshLists() {
        modelEntries = models.entries()
        apiPresets = models.apis
        activeApiId = models.activeApi()?.id
        activeModelLabel = "${config.llm.hfRepo}/${config.llm.hfFile}"
    }

    private suspend fun boot() {
        screen = Screen.LOADING
        try {
            kb.sync { status = it }
            progress = UiProgress("Descargando modelo…", percent = null)
            val llm = llmService.start(
                onStatus = { status = it },
                onSetup = { setup ->
                    progress = UiProgress(label = setup.message, percent = setup.percent)
                    status = setup.message
                },
            )
            progress = null
            engine = RagEngine(config.rag, kb, llm)
            ready = true
            status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
            screen = Screen.CHAT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progress = null
            error = friendly(e)
            status = "Sin modelo: revisa la pantalla de modelos."
        }
    }

    /** Reintenta el arranque (botón "Reintentar" de la pantalla de carga). */
    fun retryBoot() {
        if (busy) return
        error = ""
        scope.launch { boot() }
    }

    fun reindex() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                kb.sync { status = it }
                status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun send(text: String) {
        val eng = engine ?: return
        if (text.isBlank() || busy) return
        val history = messages.filter { it.text.isNotEmpty() }.map { ChatMessage(it.role, it.text) }
        messages = messages + UiMessage("user", text) + UiMessage("assistant", "")
        busy = true
        error = ""
        chatJob?.cancel()
        chatJob = scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { eng.ask(text, history) }
                val sb = StringBuilder()
                answer.tokens.collect { sb.append(it); updateLast(sb.toString()) }
                updateLast(sb.toString(), answer.sources.map { it.label }.distinct())
            } catch (e: CancellationException) {
                updateLast("(cancelado)")
            } catch (e: Exception) {
                updateLast("⚠ ${friendly(e)}")
            } finally {
                busy = false
            }
        }
    }

    /** Cancela la generación en curso (botón Cancelar, como SAI). */
    fun cancel() {
        chatJob?.cancel()
    }

    private fun updateLast(text: String, sources: List<String> = emptyList()) {
        if (messages.isEmpty()) return
        messages = messages.dropLast(1) + messages.last().copy(text = text, sources = sources)
    }

    // ---- gestor de modelos ----

    fun downloadModel(entry: ModelEntry) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            try {
                progress = UiProgress("Descargando ${entry.preset.label}…", percent = 0)
                withContext(Dispatchers.IO) {
                    models.download(entry.preset) { p: ModelOpProgress ->
                        progress = UiProgress(p.label, p.doneBytes, p.totalBytes, p.percent)
                    }
                }
                progress = null
                refreshLists()
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
        scope.launch {
            busy = true
            error = ""
            try {
                models.activate(entry.preset)
                refreshLists()
                ready = false
                screen = Screen.LOADING
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

    /** Lista los .gguf de un repo HF para elegir variante. */
    fun listRepoFiles(repo: String, onResult: (List<String>) -> Unit) {
        scope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    HuggingFaceDownloader.listGgufFiles(repo.trim(), config.llm.hfToken)
                }
                onResult(files.map { it.name })
            } catch (e: Exception) {
                error = friendly(e)
                onResult(emptyList())
            }
        }
    }

    // ---- colección de APIs ----

    fun addApi(label: String, baseUrl: String, apiKey: String, model: String) {
        if (label.isBlank() || baseUrl.isBlank()) {
            error = "La API necesita nombre y URL base."
            return
        }
        val id = "api-${label.hashCode().toUInt().toString(36)}-${System.currentTimeMillis() % 10000}"
        models.addApi(ApiPreset(id, label.trim(), baseUrl.trim(), apiKey.trim(), model.trim()))
        refreshLists()
    }

    fun removeApi(id: String) {
        models.removeApi(id)
        refreshLists()
    }

    fun activateApi(preset: ApiPreset) {
        if (busy) return
        scope.launch {
            busy = true
            error = ""
            try {
                models.activateApi(preset)
                refreshLists()
                ready = false
                screen = Screen.LOADING
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
        scope.launch {
            busy = true
            error = ""
            try {
                models.useLocal()
                refreshLists()
                ready = false
                screen = Screen.LOADING
                llmService.stop()
                boot()
            } catch (e: Exception) {
                error = friendly(e)
            } finally {
                busy = false
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
}
