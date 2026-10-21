package voice.catalog

import kotlinx.serialization.Serializable

/**
 * Un modelo de voz del catálogo: los **modelos de voz** (audio → texto) que
 * el motor local de whisper.cpp puede cargar.
 *
 * Es el gemelo de `llm.config.ModelPreset`, pero **separado a propósito**: el
 * catálogo de los LLM activa el modelo del motor de inferencia (y si se
 * mezclaran, "usar" un modelo de voz mataría el motor local). El catálogo de
 * voz sólo alimenta `voice.stt.whisperModelPath`.
 */
@Serializable
data class VoiceModelPreset(
    /** Id único dentro del catálogo de voz. */
    val id: String,
    /** Texto visible (con la etiqueta de tamaño, como los del LLM). */
    val label: String,
    /** Repo de Hugging Face (ej. `ggerganov/whisper.cpp`). */
    val repo: String,
    /** Archivo dentro del repo (ej. `ggml-base.bin`). */
    val file: String,
    /** Tamaño aproximado, para mostrarlo antes de descargar (MB). */
    val sizeMb: Long = 0L,
    /** Descripción corta (para qué sirve este tamaño). */
    val description: String = "",
)
