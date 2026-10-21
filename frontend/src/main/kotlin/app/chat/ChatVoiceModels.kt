package app.chat

import app.core.UiProgress
import kotlinx.coroutines.launch
import llm.catalog.HfRepoFile
import llm.catalog.HfRepoHit
import llm.catalog.HuggingFaceDownloader
import voice.catalog.VoiceModelEntry
import voice.catalog.VoiceModelPreset
import voice.catalog.WHISPER_CPP_REPO

/**
 * Modelos de voz: búsqueda en Hugging Face, descarga y activación para el
 * dictado local.
 *
 * Son las funciones de extensión del ViewModel (mismo patrón que el resto de
 * áreas: el ViewModel es el dueño del estado).
 *
 * El **token de Hugging Face es el mismo** que el de los modelos de LLM
 * (`llm.hfToken`): es la misma cuenta de HF, no hace falta otra.
 */

/** Repos sugeridos de modelos de voz (el oficial de whisper.cpp primero). */
val VOICE_MODEL_SUGGESTIONS: List<String> = listOf(WHISPER_CPP_REPO)

/** Refresca el catálogo de modelos de voz (estado en pantalla). */
internal fun ChatViewModel.refreshVoiceModels() {
    voiceModelEntries = voiceModels.entries()
}

/**
 * Busca repos de modelos de voz en Hugging Face.
 *
 * A diferencia de los modelos de texto, **no** se filtra por la etiqueta
 * `gguf`: los modelos de whisper.cpp no la llevan (y sus archivos son `.bin`,
 * no `.gguf`).
 */
internal fun ChatViewModel.searchVoiceModels(query: String, onResult: (List<HfRepoHit>) -> Unit) {
    scope.launch {
        try {
            val hits = HuggingFaceDownloader.searchRepos(
                query = query,
                token = config.llm.hfToken,
                filterTag = null,
            )
            onResult(hits)
        } catch (e: Exception) {
            error = friendly(e)
            onResult(emptyList())
        }
    }
}

/** Lista los archivos de un repo que son **modelos de voz** (`ggml-*.bin`). */
internal fun ChatViewModel.listVoiceRepoFiles(repo: String, onResult: (List<HfRepoFile>) -> Unit) {
    scope.launch {
        try {
            onResult(voiceModels.listModelFiles(repo.trim(), config.llm.hfToken))
        } catch (e: Exception) {
            error = friendly(e)
            onResult(emptyList())
        }
    }
}

/**
 * Descarga un modelo de voz con progreso real.
 *
 * Con [activateAfter] lo deja además en uso (escribe su ruta en
 * `voice.stt.whisperModelPath`).
 */
internal fun ChatViewModel.downloadVoiceModel(entry: VoiceModelEntry, activateAfter: Boolean = false) {
    voiceProgress = UiProgress("Descargando ${entry.preset.label}…", percent = 0)
    scope.launch {
        try {
            voiceModels.download(entry.preset) { p ->
                voiceProgress = UiProgress(p.label, p.doneBytes, p.totalBytes, p.percent)
            }
            refreshVoiceModels()
            if (activateAfter) {
                voiceModels.activate(entry.preset)
                refreshVoiceModels()
            }
            voiceProgress = null
            status = "Modelo de voz listo."
        } catch (e: kotlinx.coroutines.CancellationException) {
            voiceProgress = null
            status = "Descarga cancelada."
        } catch (e: Exception) {
            voiceProgress = null
            error = friendly(e)
        }
    }
}

/** Cancela la descarga de un modelo de voz. */
internal fun ChatViewModel.cancelVoiceDownload() {
    voiceModels.cancelDownload()
}

/** Deja este modelo de voz como el del dictado local. */
internal fun ChatViewModel.activateVoiceModel(entry: VoiceModelEntry) {
    voiceModels.activate(entry.preset)
    refreshVoiceModels()
    status = "Dictado local usando ${entry.preset.label}."
}

/** Agrega (o reemplaza) un modelo de voz al catálogo. */
internal fun ChatViewModel.addVoiceModel(repo: String, file: String, label: String) {
    if (repo.isBlank() || file.isBlank()) return
    val id = "${repo.substringAfterLast("/")}-${file.hashCode().toUInt().toString(36)}"
    voiceModels.addModel(
        VoiceModelPreset(
            id = id,
            label = label.ifBlank { file },
            repo = repo.trim(),
            file = file.trim(),
        )
    )
    refreshVoiceModels()
}

/** Saca un modelo de voz del catálogo (no borra el archivo). */
internal fun ChatViewModel.removeVoiceModel(id: String) {
    voiceModels.removeModel(id)
    refreshVoiceModels()
}
