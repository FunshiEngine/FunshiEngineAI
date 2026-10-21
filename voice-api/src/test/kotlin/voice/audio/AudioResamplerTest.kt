package voice.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import voice.api.AudioFormat

/**
 * Tests de las conversiones de audio: downmix a mono, remuestreo y cambio de
 * formato. Son funciones puras: sin micrófono, sin disco, sin red.
 */
class AudioResamplerTest {

    /** PCM con una muestra constante de la amplitud dada. */
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
    fun `un audio mono no se toca`() {
        val original = pcm(10, 100)
        assertTrue(AudioResampler.toMono(original, 1).contentEquals(original))
    }

    @Test
    fun `el downmix a mono promedia los canales`() {
        // Canal izquierdo 1000, canal derecho 2000 → mono 1500.
        val stereo = ByteArray(4)
        stereo[0] = (1000 and 0xFF).toByte(); stereo[1] = ((1000 shr 8) and 0xFF).toByte()
        stereo[2] = (2000 and 0xFF).toByte(); stereo[3] = ((2000 shr 8) and 0xFF).toByte()
        val mono = AudioResampler.toMono(stereo, 2)
        assertEquals(2, mono.size)
        assertEquals(1500, AudioResampler.sampleAt(mono, 0))
    }

    @Test
    fun `el remuestreo baja la cantidad de muestras`() {
        val original = pcm(44_100, 5_000) // un segundo a 44,1 kHz
        val salida = AudioResampler.resample(original, 44_100, 16_000)
        assertEquals(16_000 * 2, salida.size)
    }

    @Test
    fun `el remuestreo sube la cantidad de muestras`() {
        val original = pcm(16_000, 5_000)
        val salida = AudioResampler.resample(original, 16_000, 44_100)
        assertEquals(44_100 * 2, salida.size)
    }

    @Test
    fun `remuestrear a la misma tasa no cambia el audio`() {
        val original = pcm(100, 1234)
        assertTrue(AudioResampler.resample(original, 16_000, 16_000).contentEquals(original))
    }

    @Test
    fun `convertir de stereo 44100 a mono 16000 baja a un cuarto`() {
        val stereo = pcm(44_100 * 2, 3_000) // un segundo estéreo
        val salida = AudioResampler.convert(
            stereo,
            AudioFormat(44_100, channels = 2),
            AudioFormat(16_000, channels = 1),
        )
        assertEquals(16_000 * 2, salida.size)
    }

    @Test
    fun `una muestra se lee con signo`() {
        val pcm = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // -1
        assertEquals(-1, AudioResampler.sampleAt(pcm, 0))
    }
}
