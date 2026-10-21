package voice.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del códec WAV: es lo que se manda a los servicios de transcripción
 * y lo que se decodifica de los motores de locución.
 */
class WavCodecTest {

    @Test
    fun `el wav empieza con la cabecera RIFF WAVE`() {
        val wav = WavCodec.encodeWav(ByteArray(4), 16_000)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertTrue(WavCodec.isWav(wav))
    }

    @Test
    fun `el wav lleva las muestras al final de la cabecera`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wav = WavCodec.encodeWav(pcm, 16_000)
        assertEquals(WavCodec.HEADER_SIZE + pcm.size, wav.size)
        assertArrayEquals(pcm, wav.copyOfRange(WavCodec.HEADER_SIZE, wav.size))
    }

    @Test
    fun `un wav codificado se vuelve a decodificar igual`() {
        val pcm = ByteArray(64) { (it * 7).toByte() }
        val clip = WavCodec.decodeWav(WavCodec.encodeWav(pcm, 16_000))
        assertNotNull(clip)
        assertEquals(16_000, clip!!.sampleRate)
        assertArrayEquals(pcm, clip.pcm)
    }

    @Test
    fun `un wav estéreo se decodifica a mono`() {
        // Dos canales con el mismo valor: el mono debe ser ese mismo valor.
        // 8 bytes = 2 marcos estéreo (4 muestras por marco).
        val stereo = ByteArray(8)
        for (i in 0 until 2) {
            val v = 1000
            stereo[i * 4] = (v and 0xFF).toByte()
            stereo[i * 4 + 1] = ((v shr 8) and 0xFF).toByte()
            stereo[i * 4 + 2] = (v and 0xFF).toByte()
            stereo[i * 4 + 3] = ((v shr 8) and 0xFF).toByte()
        }
        val clip = WavCodec.decodeWav(WavCodec.encodeWav(stereo, 22_050, channels = 2))
        assertNotNull(clip)
        assertEquals(2 * 2, clip!!.pcm.size)
        assertEquals(1000, AudioResampler.sampleAt(clip.pcm, 0))
    }

    @Test
    fun `algo que no es wav no se decodifica`() {
        assertNull(WavCodec.decodeWav("no soy un wav".toByteArray()))
        assertFalse(WavCodec.isWav(ByteArray(4)))
    }

    @Test
    fun `un wav con el formato raro no se decodifica`() {
        // Se cambia el tag de formato a uno que no es PCM (0xFFFE).
        val wav = WavCodec.encodeWav(ByteArray(8), 16_000)
        wav[20] = 0xFE.toByte()
        wav[21] = 0xFF.toByte()
        assertNull(WavCodec.decodeWav(wav))
    }
}
