package rag.voice

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Job
import llm.catalog.HuggingFaceDownloader
import voice.catalog.VoiceModelEntry
import voice.catalog.VoiceModelPreset
import voice.catalog.VoiceModelProgress
import voice.catalog.isWhisperModelFile
import voice.config.VoiceConfig

/**
 * Gestor de los **modelos de voz** (los `.bin` de whisper.cpp): catálogo,
 * descarga con progreso y cancelación, y activación.
 *
 * Es el gemelo de `llm.catalog.ModelManager`, pero **separado a propósito**:
 *
 * - El catálogo de voz vive en la sección `voice` de la config, no en la del
 *   LLM. Activar un modelo de voz **nunca** cambia el motor de inferencia
 *   (ni al revés): si se mezclaran, "usar" un modelo de voz dejaría al
 *   motor local sin modelo.
 * - La descarga reutiliza el mismo `HuggingFaceDownloader` del LLM (streaming,
 *   reanudación con `.part` + `Range`, lock por archivo) pero en **otra
 *   carpeta** (`<app-home>/models/voz`), para que no se mezclen los archivos.
 *
 * Al activar un modelo se escribe su **ruta absoluta** en
 * `voice.stt.whisperModelPath`, que es justo lo que lee el motor
 * `voice.stt.WhisperCliSpeechToText`.
 */
class VoiceModelManager(
    private val state: () -> VoiceConfig,
    private val baseDir: Path,
    private val save: (VoiceConfig) -> Unit,
) {
    @Volatile private var downloadJob: Job? = null

    /** Config actual (viva). */
    private val cfg: VoiceConfig get() = state()

    /** Carpeta donde viven los modelos de voz descargados. */
    fun modelsDir(): Path = baseDir.resolve(cfg.modelsDir)

    /** Catálogo con el estado de cada modelo (descargado, en uso, parcial). */
    fun entries(): List<VoiceModelEntry> {
        val dir = modelsDir()
        return cfg.models.map { p ->
            val f = dir.resolve(p.file)
            val part = dir.resolve(p.file + ".part")
            VoiceModelEntry(
                preset = p,
                downloaded = isNonEmpty(f),
                active = isActive(p, f),
                sizeBytes = sizeOf(f),
                partBytes = if (isNonEmpty(f)) 0L else sizeOf(part),
            )
        }
    }

    /**
     * ¿Este preset es el que está en uso?
     *
     * Se compara por la **ruta absoluta**: es lo que se guarda en
     * `voice.stt.whisperModelPath`.
     */
    private fun isActive(preset: VoiceModelPreset, file: Path): Boolean {
        val actual = cfg.stt.whisperModelPath.trim()
        if (actual.isEmpty()) return false
        val esperado = file.toAbsolutePath().normalize().toString()
        val elegido = try {
            Path.of(actual).toAbsolutePath().normalize().toString()
        } catch (_: Exception) {
            actual
        }
        return elegido == esperado
    }

    /**
     * Descarga un modelo de voz con progreso (0..100) y cancelación.
     *
     * El archivo queda en `<app-home>/models/voz/<file>`; si se corta, queda
     * el `.part` y la próxima descarga lo retoma.
     */
    suspend fun download(
        preset: VoiceModelPreset,
        onProgress: (VoiceModelProgress) -> Unit,
    ): Path {
        downloadJob?.cancel()
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        downloadJob = job
        try {
            var last = 0L
            // Reporte inicial: la UI puede pintar la barra desde el primer instante.
            onProgress(VoiceModelProgress(preset.label, 0L, 0L, 0))
            return HuggingFaceDownloader().ensureModel(
                preset.repo, preset.file, modelsDir(),
            ) { done, total ->
                val now = System.currentTimeMillis()
                val finished = total > 0 && done >= total
                // Throttle de 250 ms, pero SIEMPRE se emite el último (100%).
                if (now - last > 250 || finished) {
                    last = now
                    val pct = if (total > 0) ((done * 100 / total).toInt().coerceIn(0, 100)) else null
                    onProgress(VoiceModelProgress(preset.label, done, total, pct))
                }
            }
        } finally {
            if (downloadJob == job) downloadJob = null
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /**
     * Activa el modelo para el dictado local: escribe su ruta **absoluta** en
     * `voice.stt.whisperModelPath` y deja el motor en `WHISPER_CLI`.
     */
    fun activate(preset: VoiceModelPreset) {
        val target = modelsDir().resolve(preset.file).toAbsolutePath().normalize()
        save(
            cfg.copy(
                stt = cfg.stt.copy(
                    provider = voice.config.SttProvider.WHISPER_CLI,
                    whisperModelPath = target.toString(),
                ),
            )
        )
    }

    /** Agrega (o reemplaza) un modelo al catálogo de voz. */
    fun addModel(preset: VoiceModelPreset) {
        save(cfg.copy(models = cfg.models.filter { it.id != preset.id } + preset))
    }

    /** Saca un modelo del catálogo (no borra el archivo). */
    fun removeModel(id: String) {
        save(cfg.copy(models = cfg.models.filter { it.id != id }))
    }

    /**
     * Lista los archivos de un repo de Hugging Face que son **modelos de voz**
     * (los `ggml-*.bin` que whisper.cpp puede cargar).
     */
    suspend fun listModelFiles(repo: String, token: String = ""): List<llm.catalog.HfRepoFile> =
        HuggingFaceDownloader.listRepoFiles(repo, token, voice.catalog.WHISPER_MODEL_EXTENSIONS)
            .filter { isWhisperModelFile(it.name) }

    private fun isNonEmpty(p: Path): Boolean =
        try {
            Files.isRegularFile(p) && Files.size(p) > 0
        } catch (_: Exception) {
            false
        }

    private fun sizeOf(p: Path): Long =
        try {
            if (Files.isRegularFile(p)) Files.size(p) else 0L
        } catch (_: Exception) {
            0L
        }
}
