package voice.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import voice.api.AudioFormat
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import javax.sound.sampled.AudioFormat as JdkFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/**
 * Captura de audio del micrófono.
 *
 * Es un **contrato** (no una implementación) para que el resto de la app no
 * dependa del hardware: los tests usan un doble y la sesión de voz no sabe si
 * hay micrófono o no.
 */
interface MicrophoneCapture {
    /**
     * Abre el micrófono y devuelve el **formato real** de la captura (puede
     * no ser el pedido: si el micrófono no da 16 kHz, se usa el que tenga).
     */
    suspend fun open(sampleRate: Int): AudioFormat

    /** Lee el siguiente trozo de muestras. */
    suspend fun read(): ByteArray

    /** Cierra el micrófono (idempotente). */
    fun close()
}

/**
 * Captura con el micrófono del sistema (`javax.sound.sampled`, sin
 * dependencias extra).
 *
 * Intenta el formato pedido (16 kHz mono 16 bits) y, si la máquina no lo
 * soporta, usa el formato por defecto del sistema: la conversión a 16 kHz
 * mono la hace después `AudioResampler`.
 */
class JdkMicrophoneCapture : MicrophoneCapture {

    private var line: TargetDataLine? = null
    private val buffer = ByteArray(BUFFER_BYTES)

    override suspend fun open(sampleRate: Int): AudioFormat = withContext(Dispatchers.IO) {
        close()
        val wanted = JdkFormat(sampleRate.toFloat(), 16, 1, true, false)
        val opened = openLine(wanted)
        val target = opened.first
        val format = opened.second
        try {
            target.start()
        } catch (e: Exception) {
            closeQuietly(target)
            throw VoiceException(
                VoiceErrorCode.MICROFONO_NO_DISPONIBLE,
                "No se pudo arrancar la captura del micrófono: " +
                    (e.message ?: e.javaClass.simpleName),
                cause = e,
            )
        }
        line = target
        format
    }

    /** Abre la línea con el formato pedido; si no se puede, con otro habitual. */
    private fun openLine(wanted: JdkFormat): Pair<TargetDataLine, AudioFormat> {
        // El pedido primero (16 kHz mono), después los formatos que casi todos
        // los micrófonos admiten: así funciona aunque la máquina no pueda bajar
        // a 16 kHz (la conversión la hace el remuestreador).
        val candidates = buildList {
            add(wanted)
            for (rate in intArrayOf(44_100, 48_000, 22_050, 32_000)) {
                add(JdkFormat(rate.toFloat(), 16, 1, true, false))
                add(JdkFormat(rate.toFloat(), 16, 2, true, false))
            }
        }
        var lastError: Exception? = null
        for (format in candidates) {
            var target: TargetDataLine? = null
            try {
                target = AudioSystem.getTargetDataLine(format)
                target.open(format, buffer.size)
                return target to format.toAudioFormat()
            } catch (e: Exception) {
                lastError = e
                closeQuietly(target)
            }
        }
        throw VoiceException(
            VoiceErrorCode.MICROFONO_NO_DISPONIBLE,
            "No hay ningún micrófono disponible en el sistema: " +
                (lastError?.message ?: lastError?.javaClass?.simpleName ?: "formato no soportado") +
                ". Puede que esté en uso por otra aplicación.",
            cause = lastError,
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val target = line ?: throw VoiceException(
            VoiceErrorCode.MICROFONO_NO_DISPONIBLE,
            "El micrófono no está abierto.",
        )
        var out: ByteArray? = null
        while (out == null) {
            // La lectura es bloqueante: se sondea `available` para poder
            // respetar la cancelación del usuario.
            coroutineContext.ensureActive()
            val available = target.available()
            if (available > 0) {
                val read = target.read(buffer, 0, minOf(available, buffer.size))
                if (read > 0) out = buffer.copyOf(read)
            } else {
                delay(POLL_MS)
            }
        }
        out!!
    }

    override fun close() {
        val current = line ?: return
        line = null
        closeQuietly(current)
    }

    /** Cierra la línea sin que una excepción rompa el flujo. */
    private fun closeQuietly(target: TargetDataLine?) {
        if (target == null) return
        try {
            target.stop()
        } catch (_: Exception) {
            // Ya estaba parado: no importa.
        }
        try {
            target.flush()
            target.close()
        } catch (_: Exception) {
            // Si falla el cierre, la línea se libera igual al hacer GC.
        }
    }

    /** Convierte el formato del JDK al formato propio. */
    private fun JdkFormat.toAudioFormat(): AudioFormat = AudioFormat(
        sampleRate = sampleRate.toInt(),
        channels = channels,
        bitsPerSample = sampleSizeInBits,
        // PCM: big endian es sin signo, little endian con signo.
        signed = !isBigEndian,
        bigEndian = isBigEndian,
    )

    private companion object {
        /** Tamaño del búfer de lectura (bytes). */
        const val BUFFER_BYTES = 4096

        /** Espera entre sondeos del micrófono (ms). */
        const val POLL_MS = 20L
    }
}
