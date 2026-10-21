package voice.audio

import java.io.ByteArrayOutputStream
import voice.api.AudioClip

/**
 * Lectura y escritura de archivos WAV (PCM 16 bits little-endian).
 *
 * Es **puro** (sólo bytes): se puede testear sin disco ni audio. El formato
 * WAV es el que aceptan las APIs de transcripción y el que producen los
 * motores de locución del sistema.
 */
object WavCodec {

    /** Tamaño de la cabecera WAV canónica (RIFF + fmt + data). */
    const val HEADER_SIZE = 44

    private const val PCM_FORMAT = 1

    /**
     * Envuelve muestras PCM 16 bits little-endian en un WAV.
     *
     * @param pcm muestras (sin cabecera).
     * @param sampleRate tasa de muestreo.
     * @param channels número de canales.
     */
    fun encodeWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val out = ByteArrayOutputStream(HEADER_SIZE + pcm.size)
        // "RIFF"
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        // "fmt "
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(16)                 // tamaño del bloque fmt
        out.writeShortLE(PCM_FORMAT)       // PCM sin comprimir
        out.writeShortLE(channels)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(16)               // bits por muestra
        // "data"
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.writeIntLE(pcm.size)
        out.writeBytes(pcm)
        return out.toByteArray()
    }

    /** ¿El byte array empieza con una cabecera RIFF/WAVE? */
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    /**
     * Lee un WAV y devuelve el audio como clip **mono 16 bits**.
     *
     * Devuelve `null` si no es un WAV PCM de 16 bits (el llamador lo
     * convierte en un error claro: mejor fallar que devolver basura).
     */
    fun decodeWav(bytes: ByteArray): AudioClip? {
        if (!isWav(bytes)) return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = readIntLE(bytes, offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    format = readShortLE(bytes, body)
                    channels = readShortLE(bytes, body + 2)
                    sampleRate = readIntLE(bytes, body + 4)
                    bits = readShortLE(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            // Los chunks son pares: se alinean a 2 bytes.
            offset = body + size + (size and 1)
        }
        if (format != PCM_FORMAT || bits != 16 || channels <= 0 || dataOffset < 0) return null
        val end = minOf(dataOffset + dataLength, bytes.size)
        if (end <= dataOffset) return null
        val raw = bytes.copyOfRange(dataOffset, end)
        val mono = AudioResampler.toMono(raw, channels)
        return AudioClip(mono, sampleRate)
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun readIntLE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readShortLE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}
