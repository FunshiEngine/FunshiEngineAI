package voice.service

import voice.api.SpeechToTextGateway
import voice.api.TextToSpeechGateway
import voice.config.SttProvider
import voice.config.TtsProvider
import voice.config.VoiceConfig
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import voice.stt.OpenAiSpeechToText
import voice.stt.WhisperCliSpeechToText
import voice.tts.OpenAiTextToSpeech
import voice.tts.SystemTextToSpeech

/**
 * Fachada de voz: elige el motor de dictado y el de locución según la
 * configuración.
 *
 * Es el equivalente de `llm.service.LlmService`: la app no sabe qué motor
 * hay, sólo pide "dame el motor de dictado" y "dame el motor de locución".
 *
 * La configuración se lee **en cada llamada** (lambda), como en `LlmService`:
 * si el usuario cambia el motor en Ajustes, el cambio aplica sin reiniciar.
 */
class VoiceService(
    private val configProvider: () -> VoiceConfig,
) {

    /** Config de voz actual. */
    fun config(): VoiceConfig = configProvider()

    /**
     * Motor de dictado, o `null` si no hay ninguno configurado.
     *
     * Lanza [voice.error.VoiceException] si el motor está configurado pero
     * le falta lo imprescindible (fail-fast: mejor un error claro que un
     * micrófono que no transcribe nada).
     */
    fun speechToText(): SpeechToTextGateway? {
        val cfg = config()
        return when (cfg.stt.provider) {
            SttProvider.NONE -> null
            SttProvider.OPENAI -> {
                val url = cfg.stt.baseUrl.trim()
                if (url.isBlank()) {
                    throw VoiceException(
                        VoiceErrorCode.CONFIG_INVALIDA,
                        "El dictado por API necesita la URL base (voice.stt.baseUrl).",
                    )
                }
                OpenAiSpeechToText(
                    baseUrl = url,
                    apiKey = cfg.stt.apiKey,
                    model = cfg.stt.model,
                    language = cfg.stt.language,
                    timeoutMs = cfg.stt.timeoutMs,
                )
            }
            SttProvider.WHISPER_CLI -> {
                val model = cfg.stt.whisperModelPath.trim()
                if (model.isBlank()) {
                    throw VoiceException(
                        VoiceErrorCode.CONFIG_INVALIDA,
                        "El dictado local necesita el modelo de whisper (voice.stt.whisperModelPath).",
                    )
                }
                WhisperCliSpeechToText(
                    cliPath = cfg.stt.whisperCliPath.trim(),
                    modelPath = model,
                    language = cfg.stt.language,
                    timeoutMs = cfg.stt.timeoutMs,
                )
            }
        }
    }

    /**
     * Motor de locución, o `null` si no hay ninguno configurado.
     */
    fun textToSpeech(): TextToSpeechGateway? {
        val cfg = config()
        return when (cfg.tts.provider) {
            TtsProvider.NONE -> null
            TtsProvider.OPENAI -> {
                val url = cfg.tts.baseUrl.trim()
                if (url.isBlank()) {
                    throw VoiceException(
                        VoiceErrorCode.CONFIG_INVALIDA,
                        "La locución por API necesita la URL base (voice.tts.baseUrl).",
                    )
                }
                OpenAiTextToSpeech(
                    baseUrl = url,
                    apiKey = cfg.tts.apiKey,
                    model = cfg.tts.model,
                    defaultVoice = cfg.tts.voice,
                    defaultSpeed = cfg.tts.speed,
                    timeoutMs = cfg.tts.timeoutMs,
                )
            }
            TtsProvider.SYSTEM -> SystemTextToSpeech(timeoutMs = cfg.tts.timeoutMs)
        }
    }

    /** Problemas de la configuración de voz (vacío = todo bien). */
    fun problems(): List<String> = config().validate()
}
