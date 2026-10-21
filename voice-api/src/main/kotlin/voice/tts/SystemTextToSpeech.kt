package voice.tts

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.api.AudioClip
import voice.api.TextToSpeechGateway
import voice.api.TtsOptions
import voice.audio.WavCodec
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/**
 * Locución con el motor del sistema: no necesita clave ni Internet.
 *
 * | Sistema | Motor |
 * |---------|-------|
 * | Windows | `powershell.exe` con `System.Speech.Synthesis.SpeechSynthesizer` |
 * | macOS   | `say -o archivo.wav --data-format=LEI16@22050` |
 * | Linux   | `espeak-ng -w archivo.wav` (respaldo: `espeak`) |
 *
 * Si el motor no está instalado, el error lo dice con la guía de
 * instalación (mismo patrón fail-fast que `latex_compile`).
 */
class SystemTextToSpeech(
    private val timeoutMs: Long = 60_000,
) : TextToSpeechGateway {

    override val id: String = "system"

    override suspend fun synthesize(text: String, options: TtsOptions): AudioClip = withContext(Dispatchers.IO) {
        val content = text.trim()
        if (content.isEmpty()) {
            throw VoiceException(VoiceErrorCode.SINTETESIS_FALLIDA, "No hay texto para leer.")
        }
        val dir = Files.createTempDirectory("funshi-tts")
        try {
            val out = dir.resolve("voz.wav")
            val command = command(out, content, options)
            val log = dir.resolve("tts.log")
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()))
                .start()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw VoiceException(
                    VoiceErrorCode.TIMEOUT,
                    "La locución tardó más de ${timeoutMs / 1000} segundos y se cortó.",
                    retryable = true,
                )
            }
            if (process.exitValue() != 0 || !Files.exists(out)) {
                val detalle = runCatching { Files.readString(log) }.getOrDefault("").trim().take(300)
                throw VoiceException(
                    VoiceErrorCode.SINTETESIS_FALLIDA,
                    "El motor de voz del sistema falló." +
                        (if (detalle.isNotBlank()) " Detalle: $detalle" else "") +
                        " " + installGuide(),
                )
            }
            WavCodec.decodeWav(Files.readAllBytes(out)) ?: throw VoiceException(
                VoiceErrorCode.SINTETESIS_FALLIDA,
                "El motor de voz del sistema no generó un WAV que se pueda reproducir.",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Arma el comando según el sistema operativo. */
    private fun command(out: Path, text: String, options: TtsOptions): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> listOf(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", windowsScript(out, text),
            )
            os.contains("mac") || os.contains("darwin") -> listOf(
                "say", "-o", out.toString(), "--data-format=LEI16@22050", text,
            )
            else -> {
                val binary = if (which("espeak-ng") != null) "espeak-ng" else "espeak"
                listOf(binary, "-w", out.toString(), text)
            }
        }
    }

    /** Guion de PowerShell que sintetiza con `System.Speech`. */
    private fun windowsScript(out: Path, text: String): String = buildString {
        appendLine("Add-Type -AssemblyName System.Speech;")
        appendLine("\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;")
        appendLine("\$s.SetOutputToWaveFile('${out.toString().quoteForPs()}');")
        appendLine("\$s.Speak('${text.quoteForPs()}');")
        appendLine("\$s.Dispose();")
    }

    /** Escapa una cadena para un literal de PowerShell (comillas simples). */
    private fun String.quoteForPs(): String = replace("'", "''")

    /** Guía de instalación según el sistema (fail-fast con salida útil). */
    private fun installGuide(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") ->
                "En Windows hace falta Windows PowerShell con System.Speech " +
                    "(viene con el sistema; si falla, usá el motor por API)."
            os.contains("mac") ->
                "En macOS hace falta el comando `say` (viene con el sistema)."
            else ->
                "En Linux instalá `espeak-ng` (apt install espeak-ng) o usá el motor por API."
        }
    }

    /** Busca un ejecutable en el PATH. */
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
}
