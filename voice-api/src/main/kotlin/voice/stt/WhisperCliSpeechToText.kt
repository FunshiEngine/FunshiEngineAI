package voice.stt

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.api.AudioClip
import voice.api.SpeechToTextGateway
import voice.api.Transcription
import voice.audio.WavCodec
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/**
 * Dictado **local** con whisper.cpp: el binario `whisper-cli` (o `whisper`)
 * y su modelo GGML.
 *
 * Es la opción que **no manda el audio a Internet**: el micrófono es dato
 * privado y este motor lo transcribe en la máquina.
 *
 * El binario se busca en el PATH si no se indica la ruta; el modelo hay que
 * apuntarlo sí o sí (`voice.stt.whisperModelPath`).
 */
class WhisperCliSpeechToText(
    /** Ruta del binario; vacía = autodetectar en el PATH. */
    private val cliPath: String = "",
    /** Ruta del modelo GGML (obligatoria). */
    private val modelPath: String,
    /** Idioma forzado ("es", "en"…); vacío = que lo detecte whisper. */
    private val language: String = "es",
    private val timeoutMs: Long = 120_000,
) : SpeechToTextGateway {

    override val id: String = "whisper-cli"

    override suspend fun transcribe(clip: AudioClip): Transcription = withContext(Dispatchers.IO) {
        if (clip.isEmpty) {
            throw VoiceException(VoiceErrorCode.SIN_HABLA, "No hay audio para transcribir.")
        }
        if (modelPath.isBlank()) {
            throw VoiceException(
                VoiceErrorCode.CONFIG_INVALIDA,
                "Falta el modelo de whisper (voice.stt.whisperModelPath).",
            )
        }
        val model = Path.of(modelPath)
        if (!Files.exists(model)) {
            throw VoiceException(
                VoiceErrorCode.CONFIG_INVALIDA,
                "El modelo de whisper no existe: $modelPath",
            )
        }
        val binary = resolveBinary()
        val dir = Files.createTempDirectory("funshi-voice")
        try {
            val wav = dir.resolve("audio.wav")
            Files.write(wav, WavCodec.encodeWav(clip.pcm, clip.sampleRate))
            val outBase = dir.resolve("out").toString()
            val command = buildList {
                add(binary)
                add("-m"); add(modelPath)
                add("-f"); add(wav.toString())
                if (language.isNotBlank()) { add("-l"); add(language) }
                add("-nt")
                add("-otxt")
                add("-of"); add(outBase)
            }
            val log = dir.resolve("whisper.log")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw VoiceException(
                    VoiceErrorCode.TIMEOUT,
                    "whisper tardó más de ${timeoutMs / 1000} segundos y se cortó.",
                    retryable = true,
                )
            }
            if (process.exitValue() != 0) {
                val detalle = runCatching { Files.readString(log) }.getOrDefault("").trim().take(400)
                throw VoiceException(
                    VoiceErrorCode.TRANSCRIPCION_FALLIDA,
                    "whisper terminó con error (código ${process.exitValue()})." +
                        if (detalle.isNotBlank()) " Detalle: $detalle" else "",
                )
            }
            // whisper escribe el texto en `<salida>.txt`; si no está, se usa
            // lo que haya salido por consola.
            val txt = dir.resolve("out.txt")
            val text = when {
                Files.exists(txt) -> Files.readString(txt)
                else -> runCatching { Files.readString(log) }.getOrDefault("")
            }
            Transcription(text = clean(text), language = language)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Busca el binario: la ruta configurada o el PATH. */
    private fun resolveBinary(): String {
        if (cliPath.isNotBlank()) {
            val file = Path.of(cliPath)
            if (!Files.exists(file)) {
                throw VoiceException(
                    VoiceErrorCode.CONFIG_INVALIDA,
                    "El binario de whisper no existe: $cliPath",
                )
            }
            return cliPath
        }
        for (name in listOf("whisper-cli", "whisper", "whisper-cpp", "main")) {
            val found = which(name)
            if (found != null) return found
        }
        throw VoiceException(
            VoiceErrorCode.CONFIG_INVALIDA,
            "No se encontró whisper-cli en el PATH. Compilá whisper.cpp " +
                "(https://github.com/ggerganov/whisper.cpp) o indicá la ruta en " +
                "Ajustes → Voz (voice.stt.whisperCliPath).",
        )
    }

    /** Busca un ejecutable en el PATH (sirve en Windows y en Unix). */
    private fun which(name: String): String? {
        val path = System.getenv("PATH") ?: return null
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exts = if (isWindows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        for (dir in path.split(java.io.File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (ext in exts) {
                val candidate = Path.of(dir, name + ext)
                if (Files.isRegularFile(candidate)) return candidate.toString()
            }
        }
        return null
    }

    /** Limpia el texto de whisper: quita marcas de tiempo y corchetes. */
    private fun clean(raw: String): String = raw
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("[") }
        .joinToString(" ")
        .trim()
}
