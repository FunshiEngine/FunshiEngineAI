package voice.catalog

/**
 * Catálogo de modelos de voz: qué modelos existen para el dictado local y
 * cuáles de los archivos de un repo sirven.
 *
 * Todo lo de acá es **puro** (sin red ni disco) para que se pueda testear.
 */

/** Repo oficial de whisper.cpp: ahí viven los modelos GGML de voz. */
const val WHISPER_CPP_REPO = "ggerganov/whisper.cpp"

/** Prefijo de los archivos de modelo de whisper.cpp (`ggml-base.bin`…). */
private const val WHISPER_MODEL_PREFIX = "ggml-"

/**
 * ¿Este archivo del repo es un modelo que `whisper-cli` puede cargar?
 *
 * Sólo los `ggml-*.bin`: el repo de whisper.cpp tiene además baselines de
 * los tests (`for-tests-*.bin`) y los `.gguf` de otros proyectos (que son
 * para `transcribe.cpp`, no para whisper.cpp) no los carga este motor.
 */
fun isWhisperModelFile(path: String): Boolean {
    val name = path.substringAfterLast('/')
    return name.startsWith(WHISPER_MODEL_PREFIX) && name.endsWith(".bin", ignoreCase = true)
}

/** Extensión que se le pide a Hugging Face al listar un repo de voz. */
val WHISPER_MODEL_EXTENSIONS: Set<String> = setOf("bin")

/**
 * Catálogo por defecto: los modelos oficiales de whisper.cpp, del más chico
 * al más grande.
 *
 * El primero es el que se usa como sugerencia (calidad/tamaño razonable).
 */
fun defaultVoiceModels(): List<VoiceModelPreset> = listOf(
    VoiceModelPreset(
        id = "whisper-tiny",
        label = "Whisper tiny (GGML) - el mas liviano",
        repo = WHISPER_CPP_REPO,
        file = "ggml-tiny.bin",
        sizeMb = 75,
        description = "El mas rapido y pequeno. Para probar.",
    ),
    VoiceModelPreset(
        id = "whisper-base",
        label = "Whisper base (GGML) - el de siempre",
        repo = WHISPER_CPP_REPO,
        file = "ggml-base.bin",
        sizeMb = 148,
        description = "Buen equilibrio entre velocidad y precision. El recomendado.",
    ),
    VoiceModelPreset(
        id = "whisper-small-q5_1",
        label = "Whisper small cuantizado (Q5_1)",
        repo = WHISPER_CPP_REPO,
        file = "ggml-small-q5_1.bin",
        sizeMb = 181,
        description = "Mas exacto que base y casi del tamano de base.",
    ),
    VoiceModelPreset(
        id = "whisper-medium-q5_0",
        label = "Whisper medium cuantizado (Q5_0)",
        repo = WHISPER_CPP_REPO,
        file = "ggml-medium-q5_0.bin",
        sizeMb = 539,
        description = "Muy preciso; comodo en CPU para dictados largos.",
    ),
    VoiceModelPreset(
        id = "whisper-large-v3-turbo-q5_0",
        label = "Whisper large-v3-turbo cuantizado (Q5_0)",
        repo = WHISPER_CPP_REPO,
        file = "ggml-large-v3-turbo-q5_0.bin",
        sizeMb = 547,
        description = "La mejor relacion velocidad/precision para dictado en vivo.",
    ),
    VoiceModelPreset(
        id = "whisper-large-v3",
        label = "Whisper large-v3 (GGML) - el mas preciso",
        repo = WHISPER_CPP_REPO,
        file = "ggml-large-v3.bin",
        sizeMb = 3090,
        description = "La maxima calidad. Lento sin GPU y ocupa 3 GB.",
    ),
)
