package voice.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import voice.api.AudioClip
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import javax.sound.sampled.AudioFormat as JdkFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Reproducción de audio.
 *
 * Contrato (y no implementación) por el mismo motivo que el micrófono: la
 * sesión de voz no sabe si hay salida de audio ni cómo se reproduce.
 */
interface AudioPlayback {
    /** Reproduce el clip completo (bloquea hasta que termina). */
    suspend fun play(clip: AudioClip)

    /** Corta la reproducción en curso. */
    fun stop()
}

/**
 * Reproducción con el sistema (`javax.sound.sampled`, sin dependencias
 * extra): el clip es PCM 16 bits mono, así que la línea de salida se abre
 * con ese mismo formato.
 */
class JdkAudioPlayback : AudioPlayback {

    private var line: SourceDataLine? = null

    override suspend fun play(clip: AudioClip) = withContext(Dispatchers.IO) {
        if (clip.isEmpty) return@withContext
        val format = JdkFormat(clip.sampleRate.toFloat(), 16, 1, true, false)
        val target = try {
            AudioSystem.getSourceDataLine(format)
        } catch (e: Exception) {
            throw VoiceException(
                VoiceErrorCode.REPRODUCCION_FALLIDA,
                "No se pudo abrir la salida de audio: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        }
        try {
            target.open(format, clip.pcm.size)
            target.start()
            line = target
            var offset = 0
            while (offset < clip.pcm.size) {
                coroutineContext.ensureActive()
                val written = target.write(clip.pcm, offset, clip.pcm.size - offset)
                if (written > 0) offset += written
            }
            target.drain()
        } catch (e: VoiceException) {
            throw e
        } catch (e: Exception) {
            throw VoiceException(
                VoiceErrorCode.REPRODUCCION_FALLIDA,
                "Falló la reproducción del audio: ${e.message ?: e.javaClass.simpleName}",
                cause = e,
            )
        } finally {
            line = null
            try {
                target.stop()
            } catch (_: Exception) {
                // Ya estaba parado.
            }
            try {
                target.close()
            } catch (_: Exception) {
                // Nada que hacer.
            }
        }
    }

    override fun stop() {
        val current = line ?: return
        try {
            current.stop()
        } catch (_: Exception) {
            // Ya estaba parado.
        }
    }
}
