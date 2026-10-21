package voice.session

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.api.AudioClip
import voice.api.AudioFormat
import voice.api.SpeechToTextGateway
import voice.api.TextToSpeechGateway
import voice.api.TtsOptions
import voice.audio.AudioPlayback
import voice.audio.AudioResampler
import voice.audio.MicrophoneCapture
import voice.audio.SilenceDetector
import voice.config.CaptureConfig
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/**
 * Una sesión de voz: **escuchar → transcribir** y **texto → voz**.
 *
 * No sabe qué es un chat: el que la usa entrega el texto con un callback y
 * recibe la respuesta con otro. Por eso la misma sesión sirve para el
 * dictado (el texto va al redactor) y para el chat por voz (el texto va al
 * modelo y la respuesta vuelve como voz).
 *
 * Todo el I/O va a `Dispatchers.IO`; los errores son
 * [voice.error.VoiceException] (tipados, como los del LLM).
 */
class VoiceSession(
    private val capture: MicrophoneCapture,
    private val stt: SpeechToTextGateway,
    private val playback: AudioPlayback,
    /** Motor de locución; `null` = esta sesión sólo dicta. */
    private val tts: TextToSpeechGateway? = null,
    /** Ajustes de captura (tasa, silencios, tiempos). */
    private val settings: CaptureConfig = CaptureConfig(),
) {

    /** Resultado de una escucha. */
    sealed class ListenResult {
        /** Se entendió algo. */
        data class Text(val text: String) : ListenResult()

        /** No se oyó nada (o sólo ruido): se puede volver a escuchar. */
        data object Silence : ListenResult()

        /** Falló la captura o la transcripción. */
        data class Failed(val error: VoiceException) : ListenResult()
    }

    /**
     * Escucha hasta que hay silencio y transcribe lo que se oyó.
     *
     * El micrófono se abre y se cierra en cada llamada: nunca queda abierto
     * entre frase y frase (ni mientras se reproduce la respuesta, que es lo
     * que evita que el micrófono escuche la voz del modelo).
     */
    suspend fun listen(): ListenResult = withContext(Dispatchers.IO) {
        val format = try {
            capture.open(settings.sampleRate)
        } catch (e: VoiceException) {
            return@withContext ListenResult.Failed(e)
        }
        val detector = SilenceDetector(
            threshold = settings.silenceThreshold,
            silenceTimeoutMs = settings.silenceTimeoutMs,
            minSpeechMs = settings.minRecordingMs,
            maxMs = settings.maxRecordingMs,
        )
        val buffer = ByteArrayOutputStream()
        try {
            var vacias = 0
            while (true) {
                val frame = capture.read()
                if (frame.isEmpty()) {
                    // El micrófono de verdad bloquea hasta que hay datos; si
                    // una implementación devuelve vacío repetidas veces, es
                    // que se quedó mudo: mejor un error que un bucle infinito.
                    vacias++
                    if (vacias > MAX_EMPTY_READS) {
                        throw VoiceException(
                            VoiceErrorCode.CAPTURA_FALLIDA,
                            "El micrófono dejó de entregar datos.",
                        )
                    }
                    continue
                }
                vacias = 0
                buffer.write(frame)
                val decision = detector.push(frame, format.sampleRate)
                if (decision == SilenceDetector.Decision.SPEECH_ENDED ||
                    decision == SilenceDetector.Decision.MAX_DURATION
                ) {
                    break
                }
            }
        } catch (e: VoiceException) {
            return@withContext ListenResult.Failed(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ListenResult.Failed(
                VoiceException(VoiceErrorCode.CAPTURA_FALLIDA, "Falló la captura de audio: ${e.message}", cause = e),
            )
        } finally {
            capture.close()
        }

        // Sin voz detectada no se transcribe nada: se avisa y se vuelve a escuchar.
        if (!detector.speechDetected) return@withContext ListenResult.Silence

        val pcm = AudioResampler.convert(buffer.toByteArray(), format, targetFormat())
        if (pcm.isEmpty()) return@withContext ListenResult.Silence
        try {
            val transcription = stt.transcribe(AudioClip(pcm, targetFormat().sampleRate))
            if (transcription.isEmpty) ListenResult.Silence else ListenResult.Text(transcription.text)
        } catch (e: VoiceException) {
            ListenResult.Failed(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ListenResult.Failed(
                VoiceException(VoiceErrorCode.TRANSCRIPCION_FALLIDA, "Falló la transcripción: ${e.message}", cause = e),
            )
        }
    }

    /**
     * Lee un texto en voz alta.
     *
     * @return `true` si se reprodujo, `false` si no hay motor de locución.
     */
    suspend fun speak(text: String): Boolean {
        val gateway = tts ?: return false
        val content = text.trim()
        if (content.isEmpty()) return false
        try {
            val clip = gateway.synthesize(content, TtsOptions())
            playback.play(clip)
            return true
        } catch (e: VoiceException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VoiceException(
                VoiceErrorCode.SINTETESIS_FALLIDA,
                "Falló la locución: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
    }

    /** Corta lo que esté sonando y cierra el micrófono. */
    fun stop() {
        playback.stop()
        capture.close()
    }

    /** Formato al que se lleva el audio antes de transcribir. */
    private fun targetFormat(): AudioFormat =
        AudioFormat(sampleRate = settings.sampleRate, channels = 1, bitsPerSample = 16)

    private companion object {
        /** Lecturas vacías seguidas antes de dar el micrófono por mudo. */
        const val MAX_EMPTY_READS = 100
    }
}
