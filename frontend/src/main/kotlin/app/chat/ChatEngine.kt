package app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.core.ChatNode
import app.core.ChatRow
import app.core.ChatTree
import app.core.Screen
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
import rag.agent.ToolContext
import rag.agent.ToolRegistry
import rag.agent.tools.GetTimeTool
import rag.config.AppConfig
import rag.config.AppPaths
import rag.config.ColorProfile
import rag.engine.RagEngine
import rag.retrieval.KnowledgeBase
import rag.store.ChatStore
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Motor: arranque, gateway y documentación por motor.
 *
 * Arranque en segundo plano (descarga del modelo, compilación de llama.cpp,
 * indexación de la documentación), reinicio del gateway y gestión de los
 * archivos documentales de cada motor.
 */
/**
 * Reinicia sólo el gateway (sin reindexar ni descargar nada) para que los
 * cambios de thinking/power apliquen al siguiente mensaje. Sólo aplica en
 * REMOTE: el servidor local no entiende `reasoning_effort`.
 */
internal fun ChatViewModel.restartGateway() {
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

/** Modo de inferencia actual (LOCAL o REMOTE). */
internal val ChatViewModel.configMode
    get() = config.llm.mode.name

/** Cambia el modo de inferencia. En REMOTE exige que exista una API guardada. */
internal fun ChatViewModel.setConfigMode(mode: String) {
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

/**
 * Arranque en background con progreso por fases visibles en la UI:
 *  1) indexar docs →  2) descargar el modelo (%, MB/MB) →  3) clonar/compilar
 * llama.cpp si hace falta (%, sólo si no está) →  4) cargar el modelo en memoria (%).
 * Al terminar vuelve a la pantalla desde la que se lanzó (o al chat).
 */
internal suspend fun ChatViewModel.boot() {
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
        // Servidores MCP: se buscan sus herramientas y se registran (F7).
        // Si no hay transporte todavía, el estado lo explica y el chat sigue.
        runCatching { refreshMcpTools() }
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
internal fun ChatViewModel.modelAlreadyOnDisk(): Boolean =
    models.entries().firstOrNull { it.active }?.downloaded == true

/** Procesa la cola de mensajes pendientes tras inicializar el engine. */
internal fun ChatViewModel.processPendingMessages() {
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
internal fun ChatViewModel.retryBoot() {
    if (busy) return
    error = ""
    testResult = ""
    scope.launch { boot() }
}

internal fun ChatViewModel.reindex() {
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
internal fun ChatViewModel.activeMotorId(): String =
    if (config.llm.mode == LlmMode.REMOTE) (activeApiId ?: "remote") else "local"

/** Carpeta de documentación de un motor. */
internal fun ChatViewModel.docsDirFor(motorId: String): Path = AppPaths.resolve("api-docs/$motorId")

/** Archivos documentales de un motor: nombre, extensión y peso. */
internal fun ChatViewModel.listDocs(motorId: String): List<UiDocsFile> {
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
internal fun ChatViewModel.addDocsFiles(motorId: String, sources: List<Path>) {
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
internal fun ChatViewModel.removeDocsFile(motorId: String, fileName: String) {
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
internal fun ChatViewModel.resyncDocsAfterChange(motorId: String) {
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
