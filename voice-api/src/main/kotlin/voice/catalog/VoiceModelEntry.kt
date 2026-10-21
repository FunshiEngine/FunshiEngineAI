package voice.catalog

/**
 * Estado de un modelo de voz del catálogo, visto desde la UI (el gemelo de
 * `llm.catalog.ModelEntry`).
 */
data class VoiceModelEntry(
    val preset: VoiceModelPreset,
    /** ¿Está descargado en disco? */
    val downloaded: Boolean,
    /** ¿Es el que está en uso por el dictado local? */
    val active: Boolean,
    /** Tamaño del archivo en disco (0 = no está). */
    val sizeBytes: Long = 0L,
    /** Bytes ya bajados de una descarga a medias (0 = ninguna). */
    val partBytes: Long = 0L,
) {
    /** Tamaño estimado total (descargado > parcial > catálogo). */
    val expectedBytes: Long
        get() = when {
            sizeBytes > 0 -> sizeBytes
            partBytes > 0 -> partBytes
            preset.sizeMb > 0 -> preset.sizeMb * 1024 * 1024
            else -> 0L
        }
}

/** Progreso de la descarga de un modelo de voz (para la barra de la UI). */
data class VoiceModelProgress(
    val label: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int? = null,
)
