package voice.audio

import kotlin.math.sqrt

/**
 * Detector de silencio por energía: decide cuándo el usuario terminó de
 * hablar, sin modelo y sin red.
 *
 * Es **puro** (sólo bytes y números): se puede testear con muestras
 * sintéticas. La regla es:
 *
 *  - hay **voz** cuando el RMS del tramo supera el umbral;
 *  - la grabación termina cuando accumulating [silenceTimeoutMs] de silencio
 *    **después** de haber detectado voz;
 *  - si nunca hay voz, la grabación no termina por silencio (se espera hasta
 *    el tope): así el detector no corta la frase apenas empieza.
 *
 * @param threshold RMS normalizado (0..1) a partir del cual hay voz.
 * @param silenceTimeoutMs silencio seguido que cierra la frase.
 * @param minSpeechMs voz mínima para dar la frase por válida.
 * @param maxMs tope de la grabación.
 */
class SilenceDetector(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    private val minSpeechMs: Long = DEFAULT_MIN_SPEECH_MS,
    private val maxMs: Long = DEFAULT_MAX_MS,
) {
    /** Qué hacer con el audio acumulado. */
    enum class Decision {
        /** Seguir grabando. */
        CONTINUE,

        /** Terminó la frase (silencio tras hablar). */
        SPEECH_ENDED,

        /** Se alcanzó el tope de grabación. */
        MAX_DURATION,
    }

    /** Milisegros de voz detectados hasta ahora. */
    var speechMs: Long = 0L
        private set

    /** Milisegros de silencio consecutivos hasta ahora. */
    var silenceMs: Long = 0L
        private set

    /** Milisegros totales procesados. */
    var totalMs: Long = 0L
        private set

    /** ¿Se detectó voz alguna vez? */
    val speechDetected: Boolean get() = speechMs > 0L

    /** Reinicia el detector (para la siguiente frase). */
    fun reset() {
        speechMs = 0L
        silenceMs = 0L
        totalMs = 0L
    }

    /**
     * Procesa un tramo de PCM 16 bits mono y dice qué hacer.
     *
     * @param pcm muestras del tramo.
     * @param sampleRate tasa del tramo (para convertir muestras en tiempo).
     */
    fun push(pcm: ByteArray, sampleRate: Int = 16_000): Decision {
        if (pcm.isEmpty() || sampleRate <= 0) return Decision.CONTINUE
        val durationMs = (pcm.size / 2).toLong() * 1000L / sampleRate
        totalMs += durationMs
        if (rms(pcm) >= threshold) {
            speechMs += durationMs
            silenceMs = 0L
        } else {
            silenceMs += durationMs
        }
        if (maxMs > 0 && totalMs >= maxMs) return Decision.MAX_DURATION
        // El silencio sólo cierra la frase si ya hubo voz: si no, el usuario
        // todavía no empezó a hablar (o el micrófono está mudo).
        if (speechMs >= minSpeechMs && silenceMs >= silenceTimeoutMs) return Decision.SPEECH_ENDED
        return Decision.CONTINUE
    }

    companion object {
        /** Umbral por defecto (RMS normalizado). */
        const val DEFAULT_THRESHOLD = 0.02f

        /** Silencio por defecto que cierra la frase (ms). */
        const val DEFAULT_SILENCE_TIMEOUT_MS = 1_500L

        /** Voz mínima por defecto para dar la frase por válida (ms). */
        const val DEFAULT_MIN_SPEECH_MS = 400L

        /** Tope por defecto de la grabación (ms). */
        const val DEFAULT_MAX_MS = 30_000L

        /**
         * Energía media del tramo, normalizada (0..1).
         *
         * Se usa el RMS y no el pico: el pico se dispara con un clic y no
         * distingue una voz de un ruido constante.
         */
        fun rms(pcm: ByteArray): Float {
            val n = pcm.size / 2
            if (n == 0) return 0f
            var sum = 0.0
            for (i in 0 until n) {
                val s = AudioResampler.sampleAt(pcm, i * 2)
                sum += s.toDouble() * s
            }
            return (sqrt(sum / n) / 32768.0).toFloat()
        }
    }
}
