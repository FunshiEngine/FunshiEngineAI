package voice.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import voice.api.AudioClip
import voice.api.TextToSpeechGateway
import voice.api.TtsOptions
import voice.audio.WavCodec
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import voice.net.Http

/**
 * Locución contra una API compatible con OpenAI
 * (`POST {baseUrl}/audio/speech`).
 *
 * Se pide siempre formato **wav**: es el único que esta app sabe decodificar
 * (y el que los proveedores devuelven sin pérdida).
 */
class OpenAiTextToSpeech(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val model: String = "tts-1",
    private val defaultVoice: String = "alloy",
    private val defaultSpeed: Float = 1.0f,
    private val timeoutMs: Long = 60_000,
) : TextToSpeechGateway {

    override val id: String = "openai"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun synthesize(text: String, options: TtsOptions): AudioClip = withContext(Dispatchers.IO) {
        val content = text.trim()
        if (content.isEmpty()) {
            throw VoiceException(VoiceErrorCode.SINTETESIS_FALLIDA, "No hay texto para leer.")
        }
        val body = buildJsonObject {
            put("model", model)
            put("input", content)
            put("voice", options.voice.ifBlank { defaultVoice })
            put("response_format", "wav")
            put("speed", options.speed.takeIf { it > 0f } ?: defaultSpeed)
        }
        val url = "${baseUrl.trimEnd('/')}/audio/speech"
        val bytes = Http.postJson(
            url = url,
            json = body.toString(),
            apiKey = apiKey,
            timeoutMs = timeoutMs,
            errorCode = VoiceErrorCode.SINTETESIS_FALLIDA,
        )
        WavCodec.decodeWav(bytes) ?: throw VoiceException(
            VoiceErrorCode.SINTETESIS_FALLIDA,
            "El servicio de locución no devolvió un WAV que se pueda reproducir " +
                "(formatos no soportados: 24 bits, float o MP3).",
        )
    }
}
