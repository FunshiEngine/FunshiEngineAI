package voice.audio

import voice.api.AudioFormat

/**
 * Conversiones de audio **puras**: downmix a mono, remuestreo y cambio de
 * formato. Sin I/O ⇒ se testea al 100% y nunca falla por culpa del entorno.
 *
 * El micrófono suele entregar 44,1 o 48 kHz en estéreo; los motores de
 * transcripción quieren 16 kHz mono. La conversión se hace una sola vez, al
 * cerrar la captura.
 */
object AudioResampler {

    /**
     * Pasa un PCM entre canales a mono, promediando (no tomando un canal:
     * si el usuario habla con un oído tapado, el otro canal trae la voz).
     */
    fun toMono(pcm: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / (channels * 2)
        if (frames == 0) return ByteArray(0)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0L
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                if (i + 1 >= pcm.size) break
                sum += sampleAt(pcm, i)
            }
            val avg = (sum / channels).toInt().coerceIn(-32768, 32767)
            out[f * 2] = (avg and 0xFF).toByte()
            out[f * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Remuestrea un PCM 16 bits mono con interpolación lineal.
     *
     * @param fromRate tasa de origen (Hz).
     * @param toRate tasa destino (Hz).
     */
    fun resample(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate || fromRate <= 0 || toRate <= 0) return pcm
        val frames = pcm.size / 2
        if (frames == 0) return ByteArray(0)
        val outFrames = ((frames.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrames * 2)
        val ratio = fromRate.toDouble() / toRate
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val frac = (pos - i0).toFloat()
            val s0 = sampleAt(pcm, i0 * 2).toFloat()
            val s1 = sampleAt(pcm, i1 * 2).toFloat()
            val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Convierte un PCM de un formato a otro (downmix + remuestreo).
     *
     * El destino tiene que ser PCM 16 bits little-endian (el canónico de la
     * voz); si no, se devuelve el audio sin tocar.
     */
    fun convert(pcm: ByteArray, from: AudioFormat, to: AudioFormat): ByteArray {
        if (from.bitsPerSample != 16 || from.bigEndian || !from.signed) return pcm
        val mono = toMono(pcm, from.channels)
        return resample(mono, from.sampleRate, to.sampleRate)
    }

    /** Lee una muestra PCM 16 bits little-endian (con signo). */
    fun sampleAt(pcm: ByteArray, index: Int): Int {
        if (index < 0 || index + 1 >= pcm.size) return 0
        return ((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
    }

    /** Escribe una muestra PCM 16 bits little-endian en el destino. */
    fun writeSample(out: ByteArray, index: Int, value: Int) {
        if (index < 0 || index + 1 >= out.size) return
        val v = value.coerceIn(-32768, 32767)
        out[index] = (v and 0xFF).toByte()
        out[index + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
