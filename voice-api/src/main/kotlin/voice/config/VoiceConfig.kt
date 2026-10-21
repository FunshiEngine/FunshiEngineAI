package voice.config

import kotlinx.serialization.Serializable

/** Motor de transcripción (dictado). */
@Serializable
enum class SttProvider {
    /** Sin dictado: la función de voz está apagada. */
    NONE,

    /** API compatible con OpenAI (`POST /v1/audio/transcriptions`). */
    OPENAI,

    /** Local: el binario de whisper.cpp (`whisper-cli`) con su modelo GGML. */
    WHISPER_CLI,
    ;

    companion object {
        /** Convierte el nombre persistido al enum; null si no existe. */
        fun fromName(name: String): SttProvider? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Motor de locución (texto → voz). */
@Serializable
enum class TtsProvider {
    /** Sin locución: el modelo responde sólo por texto. */
    NONE,

    /** API compatible con OpenAI (`POST /v1/audio/speech`). */
    OPENAI,

    /** El motor del sistema (`powershell`, `say`, `espeak-ng`). */
    SYSTEM,
    ;

    companion object {
        /** Convierte el nombre persistido al enum; null si no existe. */
        fun fromName(name: String): TtsProvider? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Configuración del motor de dictado. */
@Serializable
data class SttConfig(
    /** Motor de transcripción. NONE = el dictado no está disponible. */
    val provider: SttProvider = SttProvider.NONE,
    /**
     * URL base de la API de voz. Vacío = se usa la del motor de inferencia
     * activo (ver `AppConfig.resolvedVoice`).
     */
    val baseUrl: String = "",
    /** Clave de la API. Vacío = se usa la del motor de inferencia activo. */
    val apiKey: String = "",
    /** Modelo de transcripción ("whisper-1", "whisper-large-v3"…). */
    val model: String = "whisper-1",
    /** Idioma forzado ("es", "en"…); vacío = que lo detecte el motor. */
    val language: String = "es",
    /** Ruta del binario de whisper.cpp; vacío = autodetectar en el PATH. */
    val whisperCliPath: String = "",
    /** Ruta del modelo GGML de whisper (obligatoria con WHISPER_CLI). */
    val whisperModelPath: String = "",
    /** Timeout de la transcripción (ms). */
    val timeoutMs: Long = 60_000,
)

/** Configuración del motor de locución. */
@Serializable
data class TtsConfig(
    /** Motor de locución. NONE = la respuesta no se lee en voz alta. */
    val provider: TtsProvider = TtsProvider.NONE,
    /** URL base de la API de voz; vacío = la del motor de inferencia activo. */
    val baseUrl: String = "",
    /** Clave de la API; vacía = la del motor de inferencia activo. */
    val apiKey: String = "",
    /** Modelo de síntesis ("tts-1", "tts-1-hd"…). */
    val model: String = "tts-1",
    /** Voz del motor ("alloy", "nova", "espeak"…). */
    val voice: String = "alloy",
    /** Velocidad de la locución (1 = normal). */
    val speed: Float = 1.0f,
    /** Timeout de la síntesis (ms). */
    val timeoutMs: Long = 60_000,
)

/** Configuración de la captura de audio. */
@Serializable
data class CaptureConfig(
    /** Tasa de muestreo a la que se graba (Hz). 16 000 es lo que pide whisper. */
    val sampleRate: Int = 16_000,
    /**
     * Umbral de silencio (RMS normalizado 0..1). Por debajo de este valor se
     * considera que hay silencio.
     */
    val silenceThreshold: Float = 0.02f,
    /** Silencio seguido (ms) que corta la grabación. */
    val silenceTimeoutMs: Long = 1_500,
    /** Hablar mínimo (ms): por debajo, la captura se descarta como ruido. */
    val minRecordingMs: Long = 400,
    /** Tope de la grabación (ms): aunque el usuario siga hablando, se corta. */
    val maxRecordingMs: Long = 30_000,
)

/**
 * Sección `voice` de la configuración de la app.
 *
 * **Apagada por defecto**: una instalación que migra desde la v4 sigue
 * behaving exactamente igual que antes (mismo criterio que `tools`, `outputs`,
 * `latex` y `mcp`).
 */
@Serializable
data class VoiceConfig(
    /** Interruptor general de la voz. */
    val enabled: Boolean = false,
    /** Motor de dictado (audio → texto). */
    val stt: SttConfig = SttConfig(),
    /** Motor de locución (texto → audio). */
    val tts: TtsConfig = TtsConfig(),
    /** Captura: formato, umbral de silencio y tiempos. */
    val capture: CaptureConfig = CaptureConfig(),
    /** Leer en voz alta la respuesta del modelo en el chat por voz. */
    val speakAnswers: Boolean = true,
    /** Tope de caracteres que se leen en voz alta (el resto se muestra). */
    val maxSpeakChars: Int = 1200,
) {

    /**
     * Valida la config de voz y devuelve los problemas (vacío = válida).
     * Enfoque fail-fast: si algo está mal, se dice **antes** de tocar el
     * micrófono.
     */
    fun validate(fallbackBaseUrl: String = "", fallbackApiKey: String = ""): List<String> {
        val errors = mutableListOf<String>()
        if (capture.sampleRate !in 8_000..48_000) {
            errors += "voice.capture.sampleRate fuera de rango [8000, 48000] (actual: ${capture.sampleRate})."
        }
        if (capture.silenceThreshold !in 0f..1f) {
            errors += "voice.capture.silenceThreshold debe estar en [0, 1] (actual: ${capture.silenceThreshold})."
        }
        if (capture.silenceTimeoutMs <= 0) {
            errors += "voice.capture.silenceTimeoutMs debe ser mayor que 0."
        }
        if (capture.minRecordingMs < 0) {
            errors += "voice.capture.minRecordingMs no puede ser negativo."
        }
        if (capture.maxRecordingMs in 1..capture.minRecordingMs) {
            errors += "voice.capture.maxRecordingMs debe ser mayor que minRecordingMs."
        }
        if (maxSpeakChars < 0) {
            errors += "voice.maxSpeakChars no puede ser negativo."
        }
        when (stt.provider) {
            SttProvider.NONE -> Unit
            SttProvider.OPENAI -> {
                val url = stt.baseUrl.ifBlank { fallbackBaseUrl }
                if (url.isBlank()) {
                    errors += "Dictado por API: falta la URL base (voice.stt.baseUrl)."
                } else if (!url.trim().startsWith("http://") && !url.trim().startsWith("https://")) {
                    errors += "Dictado por API: la URL base debe empezar con http:// o https://."
                }
                if (stt.apiKey.isBlank() && fallbackApiKey.isBlank() && !isLoopback(url)) {
                    errors += "Dictado por API: falta la clave (voice.stt.apiKey)."
                }
            }
            SttProvider.WHISPER_CLI -> {
                if (stt.whisperModelPath.isBlank()) {
                    errors += "Dictado local: falta el modelo de whisper (voice.stt.whisperModelPath)."
                }
            }
        }
        when (tts.provider) {
            TtsProvider.NONE -> Unit
            TtsProvider.OPENAI -> {
                val url = tts.baseUrl.ifBlank { fallbackBaseUrl }
                if (url.isBlank()) {
                    errors += "Locución por API: falta la URL base (voice.tts.baseUrl)."
                }
                if (tts.apiKey.isBlank() && fallbackApiKey.isBlank() && !isLoopback(url)) {
                    errors += "Locución por API: falta la clave (voice.tts.apiKey)."
                }
            }
            TtsProvider.SYSTEM -> Unit
        }
        return errors
    }

    /** ¿Está configurado algún motor de dictado? */
    val hasStt: Boolean get() = stt.provider != SttProvider.NONE

    /** ¿Está configurado algún motor de locución? */
    val hasTts: Boolean get() = tts.provider != TtsProvider.NONE

    private companion object {
        /** ¿La URL apunta a la máquina propia? (esos endpoints no piden clave) */
        fun isLoopback(url: String): Boolean {
            val host = try {
                java.net.URI(url).host?.lowercase() ?: ""
            } catch (_: Exception) {
                ""
            }
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")
        }
    }
}
