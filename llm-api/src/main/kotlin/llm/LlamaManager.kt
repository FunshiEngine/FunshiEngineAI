package llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Fase del aprovisionamiento de `llama-server` (clonado/compilado), rÃƒÂ©plica de LlamaCppProgress de SAI. */
enum class LlamaSetupStage { CLONE, BUILD, DOWNLOAD, START }

/** Progreso del aprovisionamiento: etapa + porcentaje (null = indeterminado) + mensaje. */
data class LlamaSetupProgress(val stage: LlamaSetupStage, val percent: Int?, val message: String)

/**
 * Clonado + compilado del repo llama.cpp, rÃƒÂ©plica de la tÃƒÂ©cnica de
 * `LlamaCppServerManager.ts` (SAI):
 *
 * - `git clone --depth 1 [--branch <v>]` con progreso parseado de stderr
 *   (`Receiving objects: NN%`); reintenta sin `--depth 1` si falla (B-16).
 * - `git checkout` a la versiÃƒÂ³n pedida (vacÃƒÂ­o = HEAD).
 * - Candidatos de binario: ruta explÃƒÂ­cita Ã¢â€ â€™ `build/bin/llama-server[.exe]` Ã¢â€ â€™
 *   `build/llama-server[.exe]`.
 * - Si no hay binario y `llm.buildFromSource=true`: `cmake -B build` +
 *   `cmake --build build --config Release -j<N>` con progreso por lÃƒÂ­neas de
 *   compilaciÃƒÂ³n (`[NN%]`/`[NN/NN]`), timeout 20 min.
 * - `ensureReady()`: cadena global contra clonados concurrentes
 *   (como `repoSetupChain` de SAI).
 */
class LlamaManager(private val cfg: LlmConfig, private val baseDir: Path) {

    /** Directorio donde vive (o vivirÃƒÂ¡) el clon de llama.cpp. */
    fun repoDir(): Path = if (cfg.llamaRepoDir.isNotBlank()) Path.of(cfg.llamaRepoDir)
    else baseDir.resolve("llama.cpp")

    fun serverBinaryName(): String = if (IS_WINDOWS) "llama-server.exe" else "llama-server"

    /** Binario esperado dentro del repo compilado (`build/bin/Ã¢â‚¬Â¦`). */
    fun expectedRepoBinary(): Path = repoDir().resolve("build/bin").resolve(serverBinaryName())

    fun logFile(): Path = baseDir.resolve("logs/llama-build.log")

    companion object {
        const val DEFAULT_REPO_URL = "https://github.com/ggml-org/llama.cpp.git"
        const val BUILD_TIMEOUT_MS = 20 * 60_000L
        const val CLONE_TIMEOUT_MS = 10 * 60_000L
        val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
        private val setupChains = ConcurrentHashMap<String, Mutex>()
        private fun chainFor(dir: Path): Mutex =
            setupChains.computeIfAbsent(dir.toAbsolutePath().toString()) { Mutex() }
    }

    /**
     * Garantiza `llama-server` ejecutable: ruta explÃƒÂ­cita Ã¢â€ â€™ binario del repo Ã¢â€ â€™
     * build desde fuentes (si [LlmConfig.buildFromSource]) Ã¢â‚¬â€ igual que
     * `LlamaCppServerManager.ensureBinary()` de SAI.
     */
    suspend fun ensureReady(onProgress: (LlamaSetupProgress) -> Unit = {}): String =
        chainFor(repoDir()).withLock {
            val explicit = cfg.llamaServerPath.trim()
            if (explicit.isNotEmpty()) return@withLock resolveExplicit(explicit)
            findRepoBinary()?.let { return@withLock it }
            val dir = repoDir()
            if (!Files.isDirectory(dir.resolve(".git"))) {
                cloneRepo(dir, onProgress)
            } else {
                checkoutVersion(dir)
            }
            findRepoBinary()?.let { return@withLock it }
            if (!cfg.buildFromSource) {
                throw LlmException(
                    LlmErrorCode.NO_CONFIGURADO,
                    "No hay llama-server compilado en ${dir.toAbsolutePath()} y la compilaciÃƒÂ³n " +
                        "estÃƒÂ¡ desactivada (llm.buildFromSource=false). ActÃƒÂ­vala o indica llm.llamaServerPath.",
                    retryable = false,
                )
            }
            buildRepo(dir, onProgress)
            findRepoBinary() ?: throw LlmException(
                LlmErrorCode.DESCARGA_FALLIDA,
                "La compilaciÃƒÂ³n terminÃƒÂ³ pero no apareciÃƒÂ³ ${expectedRepoBinary()}. " +
                    "Revisa el log: ${logFile().toAbsolutePath()}",
                retryable = true,
            )
        }

    // ---- clonado (SAI: cloneRepo) ----

    private suspend fun cloneRepo(dir: Path, onProgress: (LlamaSetupProgress) -> Unit) = withContext(Dispatchers.IO) {
        onProgress(LlamaSetupProgress(LlamaSetupStage.CLONE, 0, "Clonando llama.cpp (${cfg.llamaRepoUrl})Ã¢â‚¬Â¦"))
        val parent = dir.parent ?: dir
        Files.createDirectories(parent)
        if (Files.exists(dir) && !Files.isDirectory(dir.resolve(".git"))) {
            Files.move(dir, dir.resolveSibling("${dir.fileName}-${System.currentTimeMillis()}.bak"))
        }
        val repoUrl = cfg.llamaRepoUrl.ifBlank { DEFAULT_REPO_URL }
        val args = mutableListOf("clone", "--progress")
        if (cfg.llamaRepoVersion.isBlank()) args += listOf("--depth", "1")
        else args += listOf("--branch", cfg.llamaRepoVersion)
        args += listOf(repoUrl, dir.toString())
        try {
            runGit(args, CLONE_TIMEOUT_MS) { line ->
                clonePercent(line)?.let { onProgress(LlamaSetupProgress(LlamaSetupStage.CLONE, it, "Clonando llama.cppÃ¢â‚¬Â¦ $it%")) }
            }
        } catch (e: LlmException) {
            // B-16 de SAI: si el shallow clone falla, reintenta completo.
            if (!cfg.llamaRepoVersion.isBlank()) throw e
            onProgress(LlamaSetupProgress(LlamaSetupStage.CLONE, null, "El clon superficial fallÃƒÂ³; reintentando completoÃ¢â‚¬Â¦"))
            runGit(listOf("clone", "--progress", repoUrl, dir.toString()), CLONE_TIMEOUT_MS) { line ->
                clonePercent(line)?.let { onProgress(LlamaSetupProgress(LlamaSetupStage.CLONE, it, "Clonando llama.cppÃ¢â‚¬Â¦ $it%")) }
            }
        }
        checkoutVersion(dir)
        onProgress(LlamaSetupProgress(LlamaSetupStage.CLONE, 100, "llama.cpp clonado."))
    }

    private fun checkoutVersion(dir: Path) {
        val v = cfg.llamaRepoVersion.trim()
        if (v.isEmpty()) return
        // Best-effort: si el checkout falla (tag movido), seguimos con HEAD.
        try {
            runGitBlocking(listOf(cfg.gitBin.ifBlank { "git" }, "checkout", v), dir, CLONE_TIMEOUT_MS)
        } catch (_: Exception) { /* HEAD por defecto */ }
    }

    /** Mayor `NN%` visible en la lÃƒÂ­nea (`Receiving objects: 45%`, `Resolving deltas: 12%`). */
    private fun clonePercent(line: String): Int? =
        Regex("""(\d{1,3})\s*%""").findAll(line)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..100 }
            .maxOrNull()

    // ---- compilado (SAI: buildServerBinary) ----

    private suspend fun buildRepo(dir: Path, onProgress: (LlamaSetupProgress) -> Unit): Unit = withContext(Dispatchers.IO) {
        onProgress(LlamaSetupProgress(LlamaSetupStage.BUILD, 0, "Configurando la compilaciÃƒÂ³n (cmake)Ã¢â‚¬Â¦"))
        val cpus = maxOf(1, Runtime.getRuntime().availableProcessors())
        runProcess(
            listOf("cmake", "-S", ".", "-B", "build", "-DCMAKE_BUILD_TYPE=Release"),
            dir, BUILD_TIMEOUT_MS, logFile(),
        ) { line ->
            clonePercent(line)?.let { onProgress(LlamaSetupProgress(LlamaSetupStage.BUILD, it / 2, "ConfigurandoÃ¢â‚¬Â¦ $it%")) }
        }
        onProgress(LlamaSetupProgress(LlamaSetupStage.BUILD, 50, "Compilando llama-server con $cpus hilosÃ¢â‚¬Â¦ (puede tardar varios minutos)"))
        val buildArgs = mutableListOf("cmake", "--build", "build", "--config", "Release", "-j", cpus.toString())
        if (!IS_WINDOWS) buildArgs += listOf("--target", "llama-server")
        runProcess(buildArgs, dir, BUILD_TIMEOUT_MS, logFile()) { line ->
            buildPercent(line)?.let { onProgress(LlamaSetupProgress(LlamaSetupStage.BUILD, 50 + it / 2, "CompilandoÃ¢â‚¬Â¦ $it%")) }
        }
        onProgress(LlamaSetupProgress(LlamaSetupStage.BUILD, 100, "CompilaciÃƒÂ³n terminada."))
    }

    /** `[ 45%]` o `[123/456]` de la salida de cmake/make. */
    private fun buildPercent(line: String): Int? {
        Regex("""\[\s*(\d{1,3})\s*%]""").findAll(line)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..100 }
            .maxOrNull()?.let { return it }
        val m = Regex("""\[\s*(\d+)\s*/\s*(\d+)\s*]""").find(line) ?: return null
        val done = m.groupValues[1].toIntOrNull() ?: return null
        val total = m.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return (done * 100 / total).coerceIn(0, 100)
    }
    // ---- resoluciÃƒÂ³n del binario (SAI: resolveBinary) ----

    private fun findRepoBinary(): String? {
        val cands = listOf(
            expectedRepoBinary(),
            repoDir().resolve("build").resolve(serverBinaryName()),
        )
        return cands.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.toString()
    }

    private fun resolveExplicit(explicit: String): String {
        val withExe = if (IS_WINDOWS && !explicit.endsWith(".exe", ignoreCase = true) &&
            Files.isRegularFile(Path.of("$explicit.exe"))) "$explicit.exe" else explicit
        if (!Files.isRegularFile(Path.of(withExe))) {
            throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "llm.llamaServerPath apunta a '$withExe' pero el archivo no existe.",
                retryable = false,
            )
        }
        return withExe
    }

    // ---- procesos ----

    private suspend fun runGit(args: List<String>, timeoutMs: Long, onStderr: (String) -> Unit) {
        val git = cfg.gitBin.ifBlank { "git" }
        try {
            runProcess(listOf(git) + args, baseDir, timeoutMs, logFile(), onStderr)
        } catch (e: LlmException) {
            val hint = if (e.code == LlmErrorCode.NO_CONFIGURADO) " Ã‚Â¿EstÃƒÂ¡ git instalado y en el PATH?" else ""
            throw LlmException(e.code, e.message + hint, e.retryable, e.cause)
        }
    }

    /**
     * Ejecuta un proceso capturando stdout al log y reportando cada lÃƒÂ­nea de
     * stderr a [onStderr] (de ahÃƒÂ­ sale el % de git/cmake). Guarda el tail
     * (ÃƒÂºltimos 2 KB) para incluirlo en el error, como SAI.
     */
    private fun runProcess(cmd: List<String>, cwd: Path, timeoutMs: Long, log: Path, onStderr: (String) -> Unit = {}) {
        val parent = log.parent ?: cwd
        Files.createDirectories(parent)
        val logFile = log.toFile()
        val proc = try {
            ProcessBuilder(cmd).directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(false)
                .start()
        } catch (e: java.io.IOException) {
            throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "No pude ejecutar '${cmd.first()}'. Revisa que estÃƒÂ© instalado y en el PATH. (${e.message})",
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
                    synchronized(logFile) { logFile.appendText("[stderr] $line\n") }
                    if (line.isNotBlank()) onStderr(line)
                }
            } catch (_: Exception) { /* proceso terminado */ }
        }, "llama-proc-err").apply { isDaemon = true; start() }
        if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            proc.destroyForcibly()
            throw LlmException(
                LlmErrorCode.TIMEOUT,
                "'${cmd.first()}' superÃƒÂ³ el tiempo mÃƒÂ¡ximo (${timeoutMs / 1000}s).",
                retryable = true,
            )
        }
        pump.join(2000)
        if (proc.exitValue() != 0) {
            val tail = synchronized(err) { err.toString().takeLast(2000) }.ifBlank { "(sin salida)" }
            throw LlmException(
                LlmErrorCode.DESCARGA_FALLIDA,
                "'${cmd.joinToString(" ")}' terminÃƒÂ³ con cÃƒÂ³digo ${proc.exitValue()}. $tail",
                retryable = true,
            )
        }
    }

    private fun runGitBlocking(cmd: List<String>, cwd: Path, timeoutMs: Long) {
        val p = ProcessBuilder(cmd).directory(cwd.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectErrorStream(true).start()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || p.exitValue() != 0) {
            throw IllegalStateException("git checkout fallÃƒÂ³")
        }
    }
}
