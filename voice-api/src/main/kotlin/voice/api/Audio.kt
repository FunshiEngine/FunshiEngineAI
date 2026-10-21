package voice.api

/**
 * Formato de un tramo de audio: PCM lineal (sin comprimir).
 *
 * Todos los contratos de voz trabajan con **PCM 16 bits signed
 * little-endian**; el resto de los formatos se convierten al entrar
 * (ver `voice.audio.AudioResampler`).
 */
data class AudioFormat(
    /** Muestras por segundo (Hz). */
    val sampleRate: Int,
    /** Canales (1 = mono, 2 = estéreo). */
    val channels: Int = 1,
    /** Bits por muestra. */
    val bitsPerSample: Int = 16,
    /** true = muestras con signo (PCM). */
    val signed: Boolean = true,
    /** true = big endian. */
    val bigEndian: Boolean = false,
) {
    /** Bytes por marco (una muestra de todos los canales). */
    val bytesPerFrame: Int get() = (channels * bitsPerSample / 8).coerceAtLeast(1)

    /** Formato mono 16 bits little-endian, el canónico de la voz. */
    companion object {
        val MONO_16K = AudioFormat(sampleRate = 16_000, channels = 1, bitsPerSample = 16)
    }
}

/**
 * Tramo de audio listo para reproducir o transcribir: **PCM 16 bits mono
 * little-endian** a la tasa indicated.
 *
 * Es una clase (no un `data class`) porque el audio son bytes: la igualdad
 * se compara por contenido.
 */
class AudioClip(
    /** Muestras PCM 16 bits signed little-endian, mono. */
    val pcm: ByteArray,
    /** Tasa de muestreo de [pcm]. */
    val sampleRate: Int,
) {
    /** Duración aproximada en milisegundos. */
    val durationMs: Int
        get() = if (sampleRate <= 0) 0 else ((pcm.size / 2).toLong() * 1000L / sampleRate).toInt()

    /** ¿No hay audio? */
    val isEmpty: Boolean get() = pcm.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is AudioClip && sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)

    override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate

    override fun toString(): String = "AudioClip(${durationMs} ms, $sampleRate Hz)"
}
