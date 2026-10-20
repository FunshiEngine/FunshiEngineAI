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
 * Catálogo de modelos y de APIs remotas.
 *
 * Descarga y activación de modelos GGUF, búsqueda en Hugging Face y
 * colección de APIs compatibles con OpenAI (alta, prueba de conexión,
 * activación).
 */
internal fun ChatViewModel.refreshLists() {
    modelEntries = models.entries()
    apiPresets = models.apis
    activeApiId = models.activeApi()?.id
    activeModelLabel = "${config.llm.hfRepo}/${config.llm.hfFile}"
    localPort = config.llm.localPort
    buildFromSource = config.llm.buildFromSource
}

// ---- gestor de modelos ----

/** Descarga un modelo; con [activateAfter] lo activa y reinicia el motor al terminar. */
internal fun ChatViewModel.downloadModel(entry: ModelEntry, activateAfter: Boolean = false) {
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

internal fun ChatViewModel.cancelDownload() {
    models.cancelDownload()
}

/** Activa otro modelo descargado: reinicia el servidor local con él. */
internal fun ChatViewModel.activateModel(entry: ModelEntry) {
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

internal fun ChatViewModel.addModel(repo: String, file: String, label: String) {
    if (repo.isBlank() || file.isBlank()) {
        error = "Indica repo y archivo (ej. unsloth/...-GGUF y *.gguf)."
        return
    }
    val id = "${repo.substringAfterLast("/")}-${file.hashCode().toUInt().toString(36)}"
    models.addModel(ModelPreset(id = id, label = label.ifBlank { file }, repo = repo.trim(), file = file.trim()))
    refreshLists()
}

internal fun ChatViewModel.removeModel(id: String) {
    models.removeModel(id)
    refreshLists()
}

/** Lista los .gguf de un repo HF (ruta completa + peso) para elegir variante. */
internal fun ChatViewModel.listRepoFiles(repo: String, onResult: (List<HfGgufFile>) -> Unit) {
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
internal fun ChatViewModel.searchModels(query: String, onResult: (List<HfRepoHit>) -> Unit) {
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

internal fun ChatViewModel.validateApiFields(
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

internal fun ChatViewModel.addApi(label: String, baseUrl: String, apiKey: String, model: String) {
    saveApi(null, label, baseUrl, apiKey, model, activate = false)
}

/**
 * Guarda una API de la colección: si [id] coincide con una existente la
 * ACTUALIZA (edición); si no, crea una nueva. Bloquea duplicados (misma
 * URL base + mismo modelo con otro id) para no repetir la misma API.
 */
internal fun ChatViewModel.saveApi(
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
internal fun ChatViewModel.duplicateApiError(id: String?, baseUrl: String, model: String): String? {
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

internal fun ChatViewModel.removeApi(id: String) {
    models.removeApi(id)
    refreshLists()
}

/**
 * Guarda una API en la colección y la activa de inmediato (modo REMOTE).
 */
internal fun ChatViewModel.saveAndActivateApi(label: String, baseUrl: String, apiKey: String, model: String): Boolean {
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

internal fun ChatViewModel.activateApi(preset: ApiPreset) {
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

internal fun ChatViewModel.useLocal() {
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
internal fun ChatViewModel.changeLocalPort(port: Int) {
    if (port < 1 || port > 65535) {
        error = "El puerto debe estar entre 1 y 65535."
        return
    }
    config = config.copy(llm = config.llm.copy(localPort = port)).save()
    localPort = port
}

/** Activa/desactiva la compilación automática de llama.cpp cuando falta. */
internal fun ChatViewModel.changeBuildFromSource(enabled: Boolean) {
    config = config.copy(llm = config.llm.copy(buildFromSource = enabled)).save()
    buildFromSource = enabled
}

/** Prueba de conexión a una API remota (GET /models). */
internal fun ChatViewModel.testApi(baseUrl: String, apiKey: String, model: String) {
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
