package llm

import java.nio.file.Files
import java.nio.file.Path

/** Estado de un modelo del catálogo visto desde la UI. */
data class ModelEntry(
    val preset: ModelPreset,
    val downloaded: Boolean,
    val active: Boolean,
    val sizeBytes: Long = 0L,
    /** Bytes ya bajados en el `.part` de una descarga a medias (0 = ninguna). */
    val partBytes: Long = 0L,
) {
    /** Tamaño estimado total del modelo (descargado > parcial > catálogo). */
    val expectedBytes: Long
        get() = when {
            sizeBytes > 0 -> sizeBytes
            partBytes > 0 -> partBytes
            preset.sizeGb > 0 -> (preset.sizeGb * 1024 * 1024 * 1024).toLong()
            else -> 0L
        }
}

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
 *
 * La config NO se copia aquí: se lee en cada acceso mediante [state] (la fuente
 * de verdad es el ChatViewModel). Así un cambio hecho en otra parte (tema,
 * modo, API activa) nunca queda desfasado y luego sobrescrito al guardar.
 */
class ModelManager(
    private val state: () -> LlmConfig,
    private val baseDir: Path,
    private val save: (LlmConfig) -> Unit,
) {
    @Volatile private var downloadJob: kotlinx.coroutines.Job? = null

    /** Config actual (viva). */
    private val cfg: LlmConfig get() = state()

    fun modelsDir(): Path = baseDir.resolve(cfg.modelsDir)

    /** Colección completa de APIs guardadas. */
    val apis: List<ApiPreset> get() = cfg.apiPresets

    fun entries(): List<ModelEntry> {
        val dir = modelsDir()
        return cfg.modelCatalog.map { p ->
            val f = dir.resolve(p.file)
            val part = dir.resolve(p.file + ".part")
            ModelEntry(
                preset = p,
                downloaded = isNonEmpty(f),
                active = p.repo == cfg.hfRepo && p.file == cfg.hfFile,
                sizeBytes = sizeOf(f),
                partBytes = if (isNonEmpty(f)) 0L else sizeOf(part),
            )
        }
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
            // Reporte inicial: la UI puede pintar la barra desde el primer instante.
            onProgress(ModelOpProgress("download", preset.label, 0L, 0L, 0))
            return HuggingFaceDownloader(cfg.hfToken).ensureModel(
                preset.repo, preset.file, modelsDir(),
            ) { done, total ->
                val now = System.currentTimeMillis()
                val finished = total > 0 && done >= total
                // Throttle de 250 ms, pero SIEMPRE se emite el último (100%).
                if (now - last > 250 || finished) {
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
        save(cfg.copy(hfRepo = preset.repo, hfFile = preset.file))
    }

    fun addModel(preset: ModelPreset) {
        save(cfg.copy(modelCatalog = cfg.modelCatalog.filter { it.id != preset.id } + preset))
    }

    fun removeModel(id: String) {
        save(cfg.copy(modelCatalog = cfg.modelCatalog.filter { it.id != id }))
    }

    fun addApi(preset: ApiPreset) {
        save(cfg.copy(apiPresets = cfg.apiPresets.filter { it.id != preset.id } + preset))
    }

    fun removeApi(id: String) {
        val remaining = cfg.apiPresets.filter { it.id != id }
        save(
            cfg.copy(
                apiPresets = remaining,
                // Si borramos la activa, limpiamos el puntero para no marcar una fantasma.
                activeApiId = if (cfg.activeApiId == id) null else cfg.activeApiId,
            )
        )
    }

    /** Activa una API guardada (modo REMOTE) y la persiste. */
    fun activateApi(preset: ApiPreset) {
        save(
            cfg.copy(
                mode = LlmMode.REMOTE,
                remoteBaseUrl = preset.baseUrl,
                apiKey = preset.apiKey,
                modelName = preset.model,
                activeApiId = preset.id,
            )
        )
    }

    fun useLocal() {
        save(cfg.copy(mode = LlmMode.LOCAL))
    }

    /**
     * API activa: primero por id persistido; si no, por coincidencia URL+modelo
     * (compatibilidad con configs antiguas que no tenían `activeApiId`).
     */
    fun activeApi(): ApiPreset? =
        cfg.apiPresets.firstOrNull { it.id == cfg.activeApiId }
            ?: cfg.apiPresets.firstOrNull {
                it.baseUrl == cfg.remoteBaseUrl && it.model == cfg.modelName
            }

    fun current(): LlmConfig = cfg

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
