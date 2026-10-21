package voice.api

/**
 * Contratos de voz: audio → texto (dictado) y texto → audio (locución).
 *
 * Son **dos** contratos y no uno porque se usan por separado: el dictado
 * sólo necesita STT, el chat por voz necesita los dos. Quien implemente
 * uno no tiene que saber nada del otro.
 *
 * No dependen de nada del chat: la app los usa desde `voice.session`.
 */

/** Texto reconocido a partir de un audio. */
data class Transcription(
    /** Lo que se entendió (puede estar vacío si no se oyó nada). */
    val text: String,
    /** Idioma detectado o pedido ("es", "en"…); vacío si no se sabe. */
    val language: String = "",
    /** Confianza del reconocedor (0..1); negativa si el motor no la da. */
    val confidence: Float = -1f,
) {
    /** ¿No se entendió nada? */
    val isEmpty: Boolean get() = text.isBlank()
}

/** Opciones de síntesis de voz. */
data class TtsOptions(
    /** Voz del motor ("alloy", "nova", "espeak"…). */
    val voice: String = "",
    /** Velocidad de la locución (1 = normal). */
    val speed: Float = 1.0f,
    /** Formato pedido al motor ("wav" es el único que se decodifica). */
    val format: String = "wav",
)

/**
 * Motor de dictado: convierte un audio en texto.
 *
 * Implementaciones: API OpenAI-compatible (`/v1/audio/transcriptions`) y
 * `whisper.cpp` local. La implementación decide el formato que espera;
 * el que se le pasa siempre es PCM 16 bits mono.
 */
interface SpeechToTextGateway {
    /** Identificador del motor ("openai", "whisper-cli"…), para los mensajes de la UI. */
    val id: String

    /** Transcribe el audio. Lanza [voice.error.VoiceException] si falla. */
    suspend fun transcribe(clip: AudioClip): Transcription
}

/**
 * Motor de locución: convierte texto en audio.
 *
 * Implementaciones: API OpenAI-compatible (`/v1/audio/speech`) y el motor
 * del sistema (`powershell`/`say`/`espeak-ng`).
 */
interface TextToSpeechGateway {
    /** Identificador del motor ("openai", "system"…), para los mensajes de la UI. */
    val id: String

    /** Sintetiza el texto. Lanza [voice.error.VoiceException] si falla. */
    suspend fun synthesize(text: String, options: TtsOptions = TtsOptions()): AudioClip
}
