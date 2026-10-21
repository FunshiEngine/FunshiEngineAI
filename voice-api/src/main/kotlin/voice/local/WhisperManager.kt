package voice.local

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import voice.config.VoiceConfig
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/** Fase del aprovisionamiento de whisper.cpp (clonado/compilado). */
enum class WhisperSetupStage { CLONE, BUILD }

/** Progreso del aprovisionamiento: etapa + porcentaje (null = indeterminado). */
data class WhisperSetupProgress(
    val stage: WhisperSetupStage,
    val percent: Int?,
    val message: String,
)

/**
 * Aprovisionamiento de whisper.cpp: detectar el binario `whisper-cli` o
 * instalarlo (clonar + compilar).
 *
 * Es el gemelo de `llm.local.LlamaManager` (que hace lo mismo con
 * llama-server), con las mismas reglas:
 *
 * - Nada se clona ni se compila si el binario ya está.
 * - Se clona sólo si falta el `.git`; se compila sólo si falta el ejecutable.
 * - Git y cmake se comprueban **antes** de empezar (fallar en 1 s y no
 *   descargar nada).
 * - Todo el proceso (git, cmake) va a `logs/whisper-build.log`.
 *
 * El binario se busca en este orden:
 *
 *  1. la ruta que el usuario pongó en `voice.stt.whisperCliPath`;
 *  2. el clon local (`<app-home>/whisper.cpp/build/bin/…`, con las variantes
 *     de MSVC `Release`/`Debug` y el nombre viejo `main`);
 *  3. el PATH (`whisper-cli`, `whisper`, `whisper-cpp`).
 */
class WhisperManager(
    private val cfg: () -> VoiceConfig,
    private val baseDir: Path,
) {

    /** Config actual (viva). */
    private val c: VoiceConfig get() = cfg()

    /** Directorio donde vive (o vivirá) el clon de whisper.cpp. */
    fun repoDir(): Path = baseDir.resolve("whisper.cpp")

    /** Nombre del ejecutable según el sistema. */
    fun binaryName(): String = if (IS_WINDOWS) "whisper-cli.exe" else "whisper-cli"

    /** Binario esperado dentro del repo compilado. */
    fun expectedRepoBinary(): Path = repoDir().resolve("build/bin").resolve(binaryName())

    /** Log del clonado/compilado. */
    fun logFile(): Path = baseDir.resolve("logs/whisper-build.log")

    /**
     * ¿Está el binario? Devuelve su ruta absoluta o `null`.
     *
     * Función **pura** en cuanto a los candidatos (el chequeo de existencia
     * sí toca el disco, pero la lista de candidatos es fija y testeable).
     */
    fun findBinary(): String? {
        // 1) ruta explícita del usuario.
        val explicit = c.stt.whisperCliPath.trim()
        if (explicit.isNotEmpty()) {
            val p = Path.of(explicit)
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize().toString()
            return null
        }
        // 2) clon local compilado.
        candidateRepoBinaries().firstOrNull { Files.isRegularFile(it) }
            ?.let { return it.toAbsolutePath().normalize().toString() }
        // 3) el PATH.
        return findInPath()
    }

    /** Rutas candidatas dentro del clon local (con las variantes de MSVC). */
    fun candidateRepoBinaries(): List<Path> {
        val dir = repoDir()
        val name = binaryName()
        val old = if (IS_WINDOWS) "main.exe" else "main"
        return listOf(
            dir.resolve("build/bin").resolve(name),
            dir.resolve("build/bin/Release").resolve(name),
            dir.resolve("build/bin/Debug").resolve(name),
            dir.resolve("build").resolve(name),
            dir.resolve("build/bin").resolve(old),
            dir.resolve("build").resolve(old),
        )
    }

    /** Busca `whisper-cli` (o sus alias) en el PATH. */
    fun findInPath(): String? {
        val names = listOf("whisper-cli", "whisper", "whisper-cpp")
        val path = System.getenv("PATH") ?: return null
        val exts = if (IS_WINDOWS) listOf(".exe", ".cmd", ".bat", "") else listOf("")
        for (dir in path.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (n in names) {
                for (ext in exts) {
                    val p = Path.of(dir, n + ext)
                    if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize().toString()
                }
            }
        }
        return null
    }

    /**
     * Garantiza que haya un `whisper-cli` utilizable: ruta explícita → binario
     * ya compilado → PATH → (si nada) clonar y compilar.
     */
    suspend fun ensureReady(
        onProgress: (WhisperSetupProgress) -> Unit = {},
    ): String = chainFor(repoDir()).withLock {        val explicit = c.stt.whisperCliPath.trim()
        if (explicit.isNotEmpty()) {
            val p = Path.of(explicit)
            if (Files.isRegularFile(p)) return@withLock p.toAbsolutePath().normalize().toString()
            throw VoiceException(
                VoiceErrorCode.CONFIG_INVALIDA,
                "voice.stt.whisperCliPath apunta a '$explicit' pero ese archivo no existe.",
                retryable = false,
            )
        }
        // Ya está instalado: no se clona ni se compila nada.
        findBinary()?.let { return@withLock it }

        val dir = repoDir()
        if (!Files.isDirectory(dir.resolve(".git"))) {
            cloneRepo(dir, onProgress)
        }
        verifyCloneComplete(dir)
        candidateRepoBinaries().firstOrNull { Files.isRegularFile(it) }
            ?.let { return@withLock it.toAbsolutePath().normalize().toString() }
        buildRepo(dir, onProgress)
        val compiled = candidateRepoBinaries().firstOrNull { Files.isRegularFile(it) }
        if (compiled != null) return@withLock compiled.toAbsolutePath().normalize().toString()
        throw VoiceException(
            VoiceErrorCode.DESCARGA_FALLIDA,
            "La compilación terminó pero no apareció ${binaryName()}. " +
                "Revisa el log: ${logFile().toAbsolutePath()}",
            retryable = true,
        )
    }

    /** Error claro cuando no hay binario y no se puede instalar. */
    fun noBinaryError(): VoiceException = VoiceException(
        VoiceErrorCode.CONFIG_INVALIDA,
        "No se encontró whisper-cli. Compilá whisper.cpp " +
            "(https://github.com/ggerganov/whisper.cpp) o poné la ruta del binario en " +
            "Ajustes → Voz (voice.stt.whisperCliPath).",
        retryable = false,
    )

    /** Verifica que el clon esté completo ANTES de compilar. */
    private fun verifyCloneComplete(dir: Path) {
        val faltantes = buildList {
            if (!Files.isDirectory(dir.resolve(".git"))) add(".git")
            if (!Files.isRegularFile(dir.resolve("CMakeLists.txt"))) add("CMakeLists.txt")
            if (!Files.isDirectory(dir.resolve("models"))) add("models/")
        }
        if (faltantes.isEmpty()) return
        throw VoiceException(
            VoiceErrorCode.DESCARGA_FALLIDA,
            "El clon de whisper.cpp quedó incompleto en ${dir.toAbsolutePath()} " +
                "(falta: ${faltantes.joinToString(", ")}). Borra esa carpeta y reintentá.",
            retryable = true,
        )
    }

    /** Chequea que una herramienta de build exista antes de usarla. */
    private fun requireTool(name: String, hint: String) {
        val check = ProcessBuilder(name, "--version")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!check.waitFor(15_000, TimeUnit.MILLISECONDS) || check.exitValue() != 0) {
            throw VoiceException(
                VoiceErrorCode.NO_CONFIGURADO,
                "No se encontró '$name' en el PATH. $hint",
                retryable = false,
            )
        }
    }

    private suspend fun cloneRepo(dir: Path, onProgress: (WhisperSetupProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            requireTool("git", "Instalá Git (https://git-scm.com/downloads).")
            onProgress(WhisperSetupProgress(WhisperSetupStage.CLONE, 0, "Clonando whisper.cpp…"))
            val parent = dir.parent ?: dir
            Files.createDirectories(parent)
            if (Files.exists(dir) && !Files.isDirectory(dir.resolve(".git"))) {
                Files.move(dir, dir.resolveSibling("${dir.fileName}-${System.currentTimeMillis()}.bak"))
            }
            runProcess(
                listOf("git", "clone", "--progress", "--depth", "1", DEFAULT_REPO_URL, dir.toString()),
                baseDir, CLONE_TIMEOUT_MS, logFile(),
            ) { line ->
                percentOf(line)?.let {
                    onProgress(WhisperSetupProgress(WhisperSetupStage.CLONE, it, "Clonando whisper.cpp… $it%"))
                }
            }
            onProgress(WhisperSetupProgress(WhisperSetupStage.CLONE, 100, "whisper.cpp clonado."))
        }

    private suspend fun buildRepo(dir: Path, onProgress: (WhisperSetupProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            requireTool("cmake", "Instalá CMake (https://cmake.org/download/).")
            onProgress(
                WhisperSetupProgress(
                    WhisperSetupStage.BUILD, 0,
                    "Configurando la compilación de whisper.cpp (cmake)…",
                )
            )
            val cpus = maxOf(1, Runtime.getRuntime().availableProcessors())
            runProcess(
                listOf("cmake", "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Release"),
                dir, BUILD_TIMEOUT_MS, logFile(),
            ) { line ->
                percentOf(line)?.let {
                    onProgress(WhisperSetupProgress(WhisperSetupStage.BUILD, it / 2, "Configurando… $it%"))
                }
            }
            onProgress(
                WhisperSetupProgress(
                    WhisperSetupStage.BUILD, 50,
                    "Compilando whisper-cli con $cpus hilos… (puede tardar varios minutos)",
                )
            )
            runProcess(
                listOf("cmake", "--build", "build", "--config", "Release", "-j", cpus.toString()),
                dir, BUILD_TIMEOUT_MS, logFile(),
            ) { line ->
                percentOf(line)?.let {
                    onProgress(WhisperSetupProgress(WhisperSetupStage.BUILD, 50 + it / 2, "Compilando… $it%"))
                }
            }
            onProgress(WhisperSetupProgress(WhisperSetupStage.BUILD, 100, "Compilación terminada."))
        }

    /** Mayor `NN%` visible en la línea de salida. */
    private fun percentOf(line: String): Int? =
        Regex("""(\d{1,3})\s*%""").findAll(line)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..100 }
            .maxOrNull()

    /**
     * Ejecuta un proceso: stdout al log, stderr también (y su cola se lee
     * para sacar el % de progreso).
     */
    private fun runProcess(
        cmd: List<String>,
        cwd: Path,
        timeoutMs: Long,
        log: Path,
        onStderr: (String) -> Unit = {},
    ) {
        val parent = log.parent ?: cwd
        Files.createDirectories(parent)
        val logFile = log.toFile()
        val proc = try {
            ProcessBuilder(cmd).directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(false)
                .start()
        } catch (e: java.io.IOException) {
            throw VoiceException(
                VoiceErrorCode.NO_CONFIGURADO,
                "No pude ejecutar '${cmd.first()}'. Revisá que esté instalado y en el PATH. (${e.message})",
                retryable = false, cause = e,
            )
        }
        val err = StringBuilder()
        val pump = Thread({
            try {
                proc.errorReader().forEachLine { line ->
                    synchronized(err) {
                        err.append(line).append('\n')
                        if (err.length > 8192) err.delete(0, err.length - 8192)
                    }
                    if (line.isNotBlank()) onStderr(line)
                }
            } catch (_: Exception) { /* proceso terminado */ }
        }, "whisper-proc-err").apply { isDaemon = true; start() }
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            throw VoiceException(
                VoiceErrorCode.TIMEOUT,
                "'${cmd.first()}' superó el tiempo máximo (${timeoutMs / 1000}s).",
                retryable = true,
            )
        }
        pump.join(2000)
        if (proc.exitValue() != 0) {
            val tail = synchronized(err) { err.toString().takeLast(2000) }.ifBlank { "(sin salida)" }
            throw VoiceException(
                VoiceErrorCode.DESCARGA_FALLIDA,
                "'${cmd.joinToString(" ")}' terminó con código ${proc.exitValue()}. $tail",
                retryable = true,
            )
        }
    }

    companion object {
        const val DEFAULT_REPO_URL = "https://github.com/ggerganov/whisper.cpp.git"
        const val BUILD_TIMEOUT_MS = 20 * 60_000L
        const val CLONE_TIMEOUT_MS = 10 * 60_000L
        val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
        private val setupChains = ConcurrentHashMap<String, Mutex>()
        private fun chainFor(dir: Path): Mutex =
            setupChains.computeIfAbsent(dir.toAbsolutePath().toString()) { Mutex() }
    }
}
