package voice.stt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import voice.api.AudioClip
import voice.api.SpeechToTextGateway
import voice.api.Transcription
import voice.audio.WavCodec
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import voice.net.Http

/**
 * Dictado contra una API compatible con OpenAI
 * (`POST {baseUrl}/audio/transcriptions`).
 *
 * Sirve con OpenAI, Groq, LocalAI, faster-whisper-server y cualquier otro
 * endpoint que hable el mismo protocolo. El audio se manda como WAV.
 */
class OpenAiSpeechToText(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val model: String = "whisper-1",
    private val language: String = "",
    private val timeoutMs: Long = 60_000,
) : SpeechToTextGateway {

    override val id: String = "openai"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(clip: AudioClip): Transcription = withContext(Dispatchers.IO) {
        if (clip.isEmpty) {
            throw VoiceException(VoiceErrorCode.SIN_HABLA, "No hay audio para transcribir.")
        }
        val wav = WavCodec.encodeWav(clip.pcm, clip.sampleRate)
        val fields = buildList {
            add("model" to model)
            if (language.isNotBlank()) add("language" to language)
            add("response_format" to "json")
        }
        val url = "${baseUrl.trimEnd('/')}/audio/transcriptions"
        val body = Http.postMultipart(
            url = url,
            fields = fields,
            fileField = "file",
            fileName = "audio.wav",
            contentType = "audio/wav",
            fileBytes = wav,
            apiKey = apiKey,
            timeoutMs = timeoutMs,
            errorCode = VoiceErrorCode.TRANSCRIPCION_FALLIDA,
        )
        val text = try {
            json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject["text"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            throw VoiceException(
                VoiceErrorCode.TRANSCRIPCION_FALLIDA,
                "El servicio de transcripción devolvió una respuesta que no se entiende: " +
                    body.toString(Charsets.UTF_8).take(200),
                cause = e,
            )
        } ?: throw VoiceException(
            VoiceErrorCode.TRANSCRIPCION_FALLIDA,
            "El servicio de transcripción no devolvió el texto (revisa el modelo y la URL).",
        )
        Transcription(text = text.trim(), language = language)
    }
}
