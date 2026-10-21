package voice.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del detector de silencio: decide cuándo el usuario terminó de
 * hablar. Es puro (sólo bytes y números), así que se testea sin micrófono.
 */
class SilenceDetectorTest {

    /** PCM con una amplitud constante (altavoz = 8000, silencio = 0). */
    private fun pcm(samples: Int, amplitude: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `el silencio solo no cierra la frase`() {
        val detector = SilenceDetector(threshold = 0.02f, silenceTimeoutMs = 100, minSpeechMs = 50, maxMs = 10_000)
        // 5 tramos de 100 ms de silencio: no hay voz, así que no se corta.
        repeat(5) {
            assertEquals(SilenceDetector.Decision.CONTINUE, detector.push(pcm(1600, 0)))
        }
        assertFalse(detector.speechDetected)
    }

    @Test
    fun `la voz seguida de silencio cierra la frase`() {
        val detector = SilenceDetector(threshold = 0.02f, silenceTimeoutMs = 100, minSpeechMs = 50, maxMs = 10_000)
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.push(pcm(1600, 8000)))
        assertTrue(detector.speechDetected)
        assertEquals(SilenceDetector.Decision.SPEECH_ENDED, detector.push(pcm(1600, 0)))
    }

    @Test
    fun `un ruido de menos de la voz minima no cuenta como frase`() {
        val detector = SilenceDetector(threshold = 0.02f, silenceTimeoutMs = 100, minSpeechMs = 500, maxMs = 10_000)
        // 100 ms de voz (por debajo del mínimo) y luego silencio.
        detector.push(pcm(1600, 8000))
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.push(pcm(1600, 0)))
    }

    @Test
    fun `la grabacion se corta al llegar al tope`() {
        val detector = SilenceDetector(threshold = 0.02f, silenceTimeoutMs = 10_000, minSpeechMs = 10, maxMs = 200)
        // 3200 muestras a 16 kHz = 200 ms: el tope se alcanza en el primer tramo.
        assertEquals(SilenceDetector.Decision.MAX_DURATION, detector.push(pcm(3200, 8000)))
    }

    @Test
    fun `el rms de una señal fuerte es mayor que el del silencio`() {
        val fuerte = SilenceDetector.rms(pcm(1600, 8000))
        val silencio = SilenceDetector.rms(pcm(1600, 0))
        assertTrue(fuerte > 0.2f, "una señal fuerte tiene energía: $fuerte")
        assertEquals(0f, silencio)
    }

    @Test
    fun `reiniciar deja el detector como nuevo`() {
        val detector = SilenceDetector(threshold = 0.02f, silenceTimeoutMs = 100, minSpeechMs = 50, maxMs = 10_000)
        detector.push(pcm(1600, 8000))
        detector.reset()
        assertEquals(0L, detector.speechMs)
        assertEquals(0L, detector.totalMs)
        assertFalse(detector.speechDetected)
    }
}
