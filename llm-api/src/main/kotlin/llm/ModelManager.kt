package llm

import java.nio.file.Files
import java.nio.file.Path

/** Estado de un modelo del catálogo visto desde la UI. */
data class ModelEntry(
    val preset: ModelPreset,
    val downloaded: Boolean,
    val active: Boolean,
    val sizeBytes: Long = 0L,
)

/** Progreso unificado para la UI (descarga de modelo o setup de llama.cpp). */
data class ModelOpProgress(
    val kind: String,
    val label: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int? = null,
)

/**
 * Gestor de modelos locales y APIs: catálogo, descargas con progreso y
 * cancelación, activación (cambia hfRepo/hfFile) y colección de APIs.
 * Vive en el backend-front (lo usa el ChatViewModel); persiste en
 * `config/app-config.json` vía [rag.AppConfig.save].
 */
class ModelManager(
    private var cfg: LlmConfig,
    private val baseDir: Path,
    private val onSave: (LlmConfig) -> Unit,
) {
    @Volatile private var downloadJob: kotlinx.coroutines.Job? = null

    fun modelsDir(): Path = baseDir.resolve(cfg.modelsDir)

    fun entries(): List<ModelEntry> {
        val dir = modelsDir()
        return cfg.modelCatalog.map { p ->
            val f = dir.resolve(p.file)
            ModelEntry(
                preset = p,
                downloaded = Files.exists(f),
                active = p.repo == cfg.hfRepo && p.file == cfg.hfFile,
                sizeBytes = try { if (Files.exists(f)) Files.size(f) else 0L } catch (_: Exception) { 0L },
            )
        }
    }

    val apis: List<ApiPreset> get() = cfg.apiPresets

    fun activeApi(): ApiPreset? = cfg.apiPresets.firstOrNull {
        it.baseUrl == cfg.remoteBaseUrl && it.model == cfg.modelName
    }

    /** Descarga un preset con progreso (0..100) y cancelación. */
    suspend fun download(
        preset: ModelPreset,
        onProgress: (ModelOpProgress) -> Unit,
    ): Path {
        downloadJob?.cancel()
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        downloadJob = job
        try {
            var last = 0L
            return HuggingFaceDownloader(cfg.hfToken).ensureModel(
                preset.repo, preset.file, modelsDir(),
            ) { done, total ->
                val now = System.currentTimeMillis()
                if (now - last > 250) {
                    last = now
                    val pct = if (total > 0) ((done * 100 / total).toInt().coerceIn(0, 100)) else null
                    onProgress(ModelOpProgress("download", preset.label, done, total, pct))
                }
            }
        } finally {
            if (downloadJob == job) downloadJob = null
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Marca el preset como activo y lo persiste (el próximo arranque lo levanta). */
    fun activate(preset: ModelPreset) {
        cfg = cfg.copy(hfRepo = preset.repo, hfFile = preset.file)
        onSave(cfg)
    }

    fun addModel(preset: ModelPreset) {
        cfg = cfg.copy(modelCatalog = cfg.modelCatalog.filter { it.id != preset.id } + preset)
        onSave(cfg)
    }

    fun removeModel(id: String) {
        cfg = cfg.copy(modelCatalog = cfg.modelCatalog.filter { it.id != id })
        onSave(cfg)
    }

    fun addApi(preset: ApiPreset) {
        cfg = cfg.copy(apiPresets = cfg.apiPresets.filter { it.id != preset.id } + preset)
        onSave(cfg)
    }

    fun removeApi(id: String) {
        cfg = cfg.copy(apiPresets = cfg.apiPresets.filter { it.id != id })
        onSave(cfg)
    }

    /** Activa una API guardada (modo REMOTE) y la persiste. */
    fun activateApi(preset: ApiPreset) {
        cfg = cfg.copy(
            mode = LlmMode.REMOTE,
            remoteBaseUrl = preset.baseUrl,
            apiKey = preset.apiKey,
            modelName = preset.model,
        )
        onSave(cfg)
    }

    fun useLocal() {
        cfg = cfg.copy(mode = LlmMode.LOCAL)
        onSave(cfg)
    }

    fun current(): LlmConfig = cfg
}

