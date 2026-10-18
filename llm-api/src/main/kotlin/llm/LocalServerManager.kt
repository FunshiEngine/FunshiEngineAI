package llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Levanta/baja `llama-server` (llama.cpp) en localhost.
 *
 * Mejoras tomadas del backend de SAI (`LlamaCppServerManager.ts` + `portPolicy.ts`):
 *  - Si el puerto configurado está ocupado, escanea los siguientes (rango de 20)
 *    hasta encontrar uno libre (B-11) — en vez de morir con "address in use".
 *  - Autodetecta el binario (`llama-server[.exe]` según plataforma).
 *  - Incluye el *tail* del log del proceso en los errores (últimas líneas visibles).
 *  - Espera graceful: `destroy()` + espera de 3 s antes de `destroyForcibly()`.
 *  - Poll de salud cada 300 ms y timeout de carga configurable (`loadTimeoutMs`).
 */
class LocalServerManager(private val cfg: LlmConfig, private val logDir: Path) {

    private var process: Process? = null
    /** Puerto efectivamente usado (puede diferir del configurado si estaba ocupado). */
    var boundPort: Int = cfg.localPort
        private set

    private fun healthy(): Boolean = try {
        val c = URI("http://127.0.0.1:$boundPort/health").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 800
        c.responseCode == 200 // 503 mientras carga el modelo
    } catch (_: Exception) {
        false
    }

    suspend fun start(
        model: Path,
        onStatus: (String) -> Unit,
        onSetup: (LlamaSetupProgress) -> Unit = { onStatus(it.message) },
    ) = withContext(Dispatchers.IO) {
        if (healthy()) {
            onStatus("Servidor local ya activo en :$boundPort")
            return@withContext
        }
        Files.createDirectories(logDir)
        val log = logDir.resolve("llama-server.log")

        // B-11 (SAI): escanea puertos libres si el configurado está tomado.
        val port = findFreePort(cfg.localPort, PORT_SCAN_SPAN)
            ?: throw LlmException(
                LlmErrorCode.SERVIDOR_CAIDO,
                "No hay puertos libres en ${cfg.localPort}..${cfg.localPort + PORT_SCAN_SPAN - 1}.",
                retryable = false,
            )
        boundPort = port
        if (port != cfg.localPort) {
            onStatus("Puerto ${cfg.localPort} ocupado; usando $port")
        }

        val binary = resolveBinary(onSetup)
        val cmd = listOf(
            binary,
            "-m", model.toString(),
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", cfg.contextSize.toString(),
            "-ngl", cfg.gpuLayers.toString(),
            "--jinja"
        )
        onStatus("Arrancando llama-server en :$port…")
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        } catch (e: IOException) {
            throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "No pude ejecutar '$binary'. Si no tienes llama-server, activa " +
                    "llm.buildFromSource=true para clonarlo y compilarlo (necesitas git y cmake). " +
                    "O ajusta llm.llamaServerPath en la config. (${e.message})",
                retryable = false,
                cause = e,
            )
        }
        process = p
        Runtime.getRuntime().addShutdownHook(Thread { p.destroy() })

        val deadline = System.currentTimeMillis() + cfg.loadTimeoutMs
        var lastStatus = ""
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                throw LlmException(
                    LlmErrorCode.SERVIDOR_CAIDO,
                    "llama-server terminó (código ${p.exitValue()}). Log (${log.fileName}):\n${logTail(log)}",
                    retryable = false,
                )
            }
            if (healthy()) {
                onStatus("Modelo cargado ✔")
                return@withContext
            }
            // Refleja el avance real del log (porcentaje de carga) si cambió.
            val s = logPercent(log)?.let { "Cargando modelo… $it%" } ?: "Cargando modelo en memoria…"
            if (s != lastStatus) {
                onStatus(s)
                lastStatus = s
            }
            delay(300) // poll 300 ms, como SAI
        }
        throw LlmException(
            LlmErrorCode.MODELO_NO_DISPONIBLE,
            "Timeout de carga (${cfg.loadTimeoutMs} ms) esperando a llama-server. Log (${log.fileName}):\n${logTail(log)}",
            retryable = true,
        )
    }

    fun stop() {
        val p = process ?: return
        process = null
        // Espera graceful (patrón SAI): destruir y esperar antes de forzar.
        p.destroy()
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                killTree(p)
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }
        } catch (_: InterruptedException) {
            killTree(p)
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Mata el árbol de procesos (patrón SAI: en Windows `destroy()` no alcanza
     * a los hijos que cuelgan del servidor — SAI usa `taskkill /T /F`).
     */
    private fun killTree(p: Process) {
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (isWindows) {
                ProcessBuilder("taskkill", "/PID", p.pid().toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, TimeUnit.SECONDS)
            } else {
                p.descendants().forEach { it.destroy() }
                p.destroyForcibly()
            }
        } catch (_: Exception) {
            p.destroyForcibly()
        }
    }

    /** Devuelve el primer puerto libre en [start, start+span) o null si no hay ninguno. */
    private fun findFreePort(start: Int, span: Int): Int? {
        for (candidate in start until start + span) {
            if (candidate !in 1..65535) continue
            try {
                ServerSocket(candidate).use { return candidate }
            } catch (_: IOException) {
                // ocupado → siguiente
            }
        }
        return null
    }

    /**
     * Binario a ejecutar:
     * 1. `llamaServerPath` explícita si existe (patrón `resolveBinary()` de SAI).
     * 2. Binario compilado dentro del clon (`LlamaManager`).
     * 3. `llama-server[.exe]` del PATH (Windows necesita el sufijo).
     */
    private suspend fun resolveBinary(onSetup: (LlamaSetupProgress) -> Unit): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exe = if (isWindows) ".exe" else ""
        val explicit = cfg.llamaServerPath.trim()
        if (explicit.isNotEmpty()) {
            val asPath = java.nio.file.Paths.get(explicit)
            val withExe = if (isWindows && !explicit.endsWith(".exe", ignoreCase = true) &&
                java.nio.file.Files.isRegularFile(java.nio.file.Paths.get("$explicit.exe"))
            ) "$explicit.exe" else explicit
            if (java.nio.file.Files.isRegularFile(asPath) || java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(withExe))) {
                return withExe
            }
            // Ruta explícita que no existe: la devolvemos igual para que el error
            // de ProcessBuilder la mencione (mensaje claro en NO_CONFIGURADO).
            return withExe
        }
        // Sin ruta explícita: si hay clon compilado (o se puede compilar), va
        // primero — igual que SAI busca en el repo antes de rendirse.
        val manager = LlamaManager(cfg, logDir.parent ?: java.nio.file.Paths.get("."))
        try {
            return manager.ensureReady(onSetup)
        } catch (e: LlmException) {
            if (e.code != LlmErrorCode.NO_CONFIGURADO) throw e
            // Sin repo ni build: caemos al PATH como antes.
        }
        return "llama-server$exe"
    }

    /** Últimas líneas del log para incluir en errores (tail, como SAI). */
    private fun logTail(log: Path, maxLines: Int = 12, maxBytes: Long = 4096): String = try {
        if (!Files.exists(log)) {
            "(log vacío)"
        } else {
            val size = Files.size(log)
            val skip = size - maxBytes
            Files.newBufferedReader(log).use { r ->
                if (skip > 0) r.skip(skip)
                r.readLines().takeLast(maxLines).joinToString("\n")
            }
        }
    } catch (_: Exception) {
        "(no se pudo leer el log)"
    }

    /** Extrae el mayor porcentaje visible en las últimas líneas del log (ej. "45.2%"). */
    private fun logPercent(log: Path): Int? {
        val tail = logTail(log, maxLines = 40, maxBytes = 2048)
        return Regex("""(\d{1,3})(?:\.\d+)?%""").findAll(tail)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 0..100 }
            .maxOrNull()
    }

    companion object {
        /** Cuántos puertos consecutivos se prueban desde el configurado (B-11 de SAI). */
        const val PORT_SCAN_SPAN = 20
    }
}
