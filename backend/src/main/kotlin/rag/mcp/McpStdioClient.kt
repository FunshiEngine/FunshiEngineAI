package rag.mcp

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Transporte MCP por **stdio** (F8): el cliente habla JSON-RPC 2.0 con un
 * proceso hijo.
 *
 * Es la implementación propia del protocolo (no el SDK oficial): el SDK
 * published exige Kotlin ≥2.2 y ktor ≥3.2, y el proyecto está en Kotlin 2.1
 * con ktor 3.0. Con este cliente no hace falta tocar el toolchain.
 *
 * El protocolo es pequeño y estable: `initialize` → `notifications/initialized`
 * → `tools/list` → `tools/call`. Queda en [McpProtocol] (puro, testeable) y
 * acá sólo está el IO.
 *
 * Separar el proceso ([McpProcess]) del protocolo permite testear el cliente
 * con un doble en memoria, sin lanzar nada.
 */

/**
 * Proceso hijo de un servidor MCP: entrada, salida y ciclo de vida.
 *
 * Abstracción mínima sobre [java.lang.Process]: el cliente no sabe si el
 * proceso es real o un doble de prueba.
 */
interface McpProcess {
    /** Escribe una línea (un mensaje JSON-RPC) en la entrada del proceso. */
    fun write(line: String)

    /** Lee la siguiente línea de la salida; `null` cuando el proceso terminó. */
    fun readLine(): String?

    /** Cierra la entrada del proceso (le avisa al servidor que termine). */
    fun closeInput()

    /** Le pide al proceso que termine (SIGTERM). */
    fun terminate()

    /** Mata al proceso (SIGKILL). */
    fun kill()

    /** ¿El proceso sigue vivo? */
    fun isAlive(): Boolean

    /** Últimas líneas que el servidor escribió en stderr (diagnóstico). */
    fun stderrTail(): String
}

/**
 * [McpProcess] sobre un [java.lang.Process] real.
 *
 * stderr se drena en segundo plano: si el servidor loguea mucho y nadie lo
 * lee, el proceso se bloquea al escribir y la conexión se cuelga.
 */
class JvmMcpProcess(private val process: Process) : McpProcess {

    private val reader = BufferedReader(InputStreamReader(process.inputStream, UTF_8))
    private val writer = OutputStreamWriter(process.outputStream, UTF_8)
    private val stderr = StringBuilder()

    init {
        Thread(::drainStderr, "mcp-stderr").apply {
            isDaemon = true
            start()
        }
    }

    override fun write(line: String) {
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    override fun readLine(): String? = reader.readLine()

    override fun closeInput() {
        runCatching { writer.close() }
    }

    override fun terminate() {
        runCatching { process.destroy() }
    }

    override fun kill() {
        runCatching { process.destroyForcibly() }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun stderrTail(): String = synchronized(stderr) { stderr.toString() }

    private fun drainStderr() {
        try {
            BufferedReader(InputStreamReader(process.errorStream, UTF_8)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    synchronized(stderr) {
                        stderr.append(line).append('\n')
                        // sólo interesa el final: la cola de diagnóstico es corta.
                        if (stderr.length > MAX_STDERR) stderr.delete(0, stderr.length - MAX_STDERR)
                    }
                    line = r.readLine()
                }
            }
        } catch (_: IOException) {
            // el proceso murió: no hay más logs que leer.
        }
    }

    private companion object {
        const val MAX_STDERR = 4000
    }
}

/** Error del protocolo: el servidor respondió con un error JSON-RPC. */
class McpProtocolException(val code: Int, message: String) : Exception(message)

/** El servidor MCP no respondió a tiempo. */
class McpTimeoutException(message: String) : McpUnavailableException(message)

/**
 * Conexión MCP sobre un proceso hijo (stdio).
 *
 * Los mensajes se leen en un hilo aparte y se rutean por id: varias
 * peticiones pueden estar en vuelo a la vez (cada una con su timeout).
 */
class StdioMcpConnection(
    override val serverId: String,
    override val displayName: String,
    private val process: McpProcess,
    private val timeoutMs: Long,
) : McpConnection {

    private val ids = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var negotiatedVersion: String = ""

    private var reader: Thread? = null

    /** Versión de protocolo que negoció el servidor (informativa). */
    override val protocolVersion: String get() = negotiatedVersion

    /** ¿El proceso del servidor sigue vivo? (diagnóstico y tests). */
    internal fun processAlive(): Boolean = process.isAlive()

    /** Arranca el hilo que lee la salida del servidor. */
    fun start() {
        reader = Thread(::readLoop, "mcp-reader-$serverId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Handshake inicial: `initialize` y, si el servidor acepta, la
     * notificación `notifications/initialized`.
     */
    suspend fun initialize() {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", McpProtocol.CLIENT_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                })
            },
        )
        negotiatedVersion = McpProtocol.stringOf(result, "protocolVersion") ?: ""
        notify("notifications/initialized")
    }

    override suspend fun listTools(): List<McpToolDescriptor> {
        val result = request("tools/list")
        val tools = result["tools"] as? JsonArray ?: return emptyList()
        return tools.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = McpProtocol.stringOf(obj, "name")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            McpToolDescriptor(
                name = name,
                description = McpProtocol.stringOf(obj, "description") ?: "",
                inputSchema = obj["inputSchema"]?.toString() ?: "{}",
            )
        }
    }

    override suspend fun callTool(name: String, args: JsonObject): McpToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", args)
        }
        return try {
            val result = request("tools/call", params)
            val content = (result["content"] as? JsonArray).orEmpty().mapNotNull(::parseContent)
            McpToolResult.Ok(content, McpProtocol.booleanOf(result, "isError") ?: false)
        } catch (e: McpTimeoutException) {
            McpToolResult.Error(e.message ?: "El servidor MCP no respondió a tiempo.")
        } catch (e: McpProtocolException) {
            McpToolResult.Error(
                "El servidor MCP '${displayName}' devolvió un error: ${e.message}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            McpToolResult.Error(
                "No se pudo llamar a '$name' en el servidor MCP '${displayName}': " +
                    "${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Cierra la conexión: se cierra la entrada del proceso, se le da un
     * margen para terminar solo y, si no, se lo termina a la fuerza.
     *
     * Va en [NonCancellable]: aunque se cancele la coroutine que la estaba
     * cerrando, el proceso hijo no puede quedar vivo.
     */
    override suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        runCatching { process.closeInput() }
        if (!waitAlive(CLOSE_GRACE_MS)) {
            runCatching { process.terminate() }
            if (!waitAlive(CLOSE_GRACE_MS)) runCatching { process.kill() }
        }
        reader?.interrupt()
        reader = null
        failPending("La conexión con el servidor MCP '${displayName}' se cerró.")
    }

    /** ¿Seguirá vivo el proceso dentro del plazo dado? */
    private suspend fun waitAlive(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (process.isAlive() && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        return process.isAlive()
    }

    /* ------------------------------------------------------------------
     * IO interno
     * ------------------------------------------------------------------ */

    /** Lee la salida del servidor y rutea cada mensaje. */
    private fun readLoop() {
        try {
            while (!closed) {
                val line = process.readLine() ?: break
                if (line.isBlank()) continue
                val message = McpProtocol.parse(line) ?: continue // ruido del servidor
                route(message)
            }
        } catch (_: IOException) {
            // el proceso murió o se cortó el pipe: las pendientes fallan abajo.
        } catch (_: InterruptedException) {
            // cierre solicitado: el hilo lector se retira sin ruido.
        } finally {
            failPending("El servidor MCP '${displayName}' cerró la conexión.")
        }
    }

    /** Direcciona un mensaje del servidor: respuesta, notificación o petición. */
    private fun route(message: JsonObject) {
        val id = McpProtocol.idOf(message)
        val method = McpProtocol.methodOf(message)

        if (method != null && id == null) {
            // Notificación del servidor (por ejemplo tools/list_changed).
            // No hacemos nada: la app no reacciona a cambios de catálogo.
            return
        }
        if (method != null && id != null) {
            // Petición del servidor hacia nosotros (sampling, roots…): no hay
            // soporte, así que se responde "método no encontrado" en vez de
            // dejar al servidor esperando.
            respondMethodNotFound(id)
            return
        }

        val deferred = id?.let { pending.remove(it) } ?: return
        val error = message["error"] as? JsonObject
        if (error != null) {
            deferred.completeExceptionally(
                McpProtocolException(
                    McpProtocol.intOf(error, "code") ?: 0,
                    McpProtocol.stringOf(error, "message") ?: "Error desconocido del servidor MCP.",
                ),
            )
            return
        }
        deferred.complete(message["result"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** Responde "método no encontrado" a una petición del servidor. */
    private fun respondMethodNotFound(id: Int) {
        val error = buildJsonObject {
            put("code", -32601)
            put("message", "FunshiEngineAI no implementa peticiones del servidor al cliente.")
        }
        val response = buildJsonObject {
            put("jsonrpc", McpProtocol.JSONRPC)
            put("id", id)
            put("error", error)
        }
        runCatching { write(response) }
    }

    /** Manda una petición y espera su respuesta (con timeout). */
    private suspend fun request(method: String, params: JsonObject? = null): JsonObject {
        check(!closed) { "La conexión con el servidor MCP '${displayName}' está cerrada." }
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.IO) { write(McpProtocol.request(id, method, params)) }
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        } catch (e: Exception) {
            pending.remove(id)
            throw McpUnavailableException(
                "No se pudo escribir en el servidor MCP '${displayName}': " +
                    "${e.message ?: e.javaClass.simpleName}",
            )
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            cancelOnServer(id)
            throw McpTimeoutException(
                "El servidor MCP '${displayName}' no respondió a '$method' en ${timeoutMs} ms.",
            )
        } finally {
            pending.remove(id)
        }
    }

    /** Manda una notificación (no espera respuesta). */
    private suspend fun notify(method: String, params: JsonObject? = null) {
        try {
            withContext(Dispatchers.IO) { write(McpProtocol.notification(method, params)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Una notificación que no sale no puede romper el flujo: el
            // servidor se puede quejarse, pero la sesión sigue.
        }
    }

    /** Avisa al servidor que la petición se canceló (timeout). */
    private suspend fun cancelOnServer(requestId: Int) {
        val params = buildJsonObject {
            put("requestId", requestId)
            put("reason", "timeout")
        }
        notify("notifications/cancelled", params)
    }

    /** Escribe un mensaje (las escrituras van en serie). */
    private fun write(message: JsonObject) {
        val line = McpProtocol.encode(message)
        synchronized(writeLock) { process.write(line) }
    }

    /** Falla las peticiones que quedaron esperando. */
    private fun failPending(message: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.completeExceptionally(McpUnavailableException(message))
        }
    }

    /** Convierte un contenido del protocolo en [McpContent]. */
    private fun parseContent(element: kotlinx.serialization.json.JsonElement): McpContent? {
        val obj = element as? JsonObject ?: return null
        return when (McpProtocol.stringOf(obj, "type")) {
            "text" -> McpContent.Text(McpProtocol.stringOf(obj, "text") ?: "")
            "image", "audio" -> McpContent.Binary(
                mime = McpProtocol.stringOf(obj, "mimeType") ?: "application/octet-stream",
                base64 = McpProtocol.stringOf(obj, "data") ?: "",
            )
            "resource" -> {
                val resource = obj["resource"] as? JsonObject
                when {
                    resource == null -> null
                    resource.containsKey("blob") -> McpContent.Binary(
                        mime = McpProtocol.stringOf(resource, "mimeType") ?: "application/octet-stream",
                        base64 = McpProtocol.stringOf(resource, "blob") ?: "",
                    )
                    else -> McpContent.Text(McpProtocol.stringOf(resource, "text") ?: "")
                }
            }
            // Los resource_link y los tipos desconocidos no se traducen: la
            // app sólo necesita texto y binarios.
            else -> null
        }
    }

    companion object {
        /** Nombre que el cliente declara en el handshake. */
        const val CLIENT_NAME = "FunshiEngineAI"

        /** Versión que el cliente declara en el handshake. */
        const val CLIENT_VERSION = "1.0"

        /** Margen que se le da al servidor para terminar solo al cerrar. */
        private const val CLOSE_GRACE_MS = 1500L
    }
}

/**
 * Conector MCP por stdio: lanza el proceso del servidor y habla JSON-RPC.
 *
 * Es el transporte real (F8). Si algo falla (el comando no existe, el
 * servidor no responde el handshake) se lanza [McpUnavailableException] con
 * un mensaje que se puede mostrar tal cual en la UI.
 */
class McpStdioConnector(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val processFactory: (McpServerConfig) -> McpProcess = { launch(it) },
) : McpConnector {

    override suspend fun connect(server: McpServerConfig): McpConnection {
        if (!server.transport.equals("stdio", ignoreCase = true)) {
            throw McpUnavailableException(
                "El transporte '${server.transport}' no está implementado: sólo stdio.",
            )
        }
        val problemas = server.problems()
        if (problemas.isNotEmpty()) {
            throw McpUnavailableException(problemas.joinToString(" "))
        }

        val process = try {
            withContext(Dispatchers.IO) { processFactory(server) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw McpUnavailableException(
                "No se pudo lanzar el servidor MCP '${server.displayName}' " +
                    "(${server.command}): ${e.message ?: e.javaClass.simpleName}",
            )
        }

        val connection = StdioMcpConnection(
            serverId = server.id,
            displayName = server.displayName,
            process = process,
            timeoutMs = timeoutMs,
        )
        try {
            connection.start()
            connection.initialize()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { connection.close() }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { connection.close() }
            throw if (e is McpUnavailableException) {
                e
            } else {
                McpUnavailableException(
                    "El servidor MCP '${server.displayName}' no completó el handshake: " +
                        "${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
        return connection
    }

    companion object {
        /** Timeout por defecto de una petición MCP (ms). */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /**
         * Lanza el proceso del servidor.
         *
         * En Windows un `.cmd`/`.bat` no se puede ejecutar directo (CreateProcess
         * sólo corre binarios): hace falta `cmd.exe /c`. Y para que una ruta
         * con espacios no se rompa, la línea va entre comillas dobles extra,
         * que es la forma canónica de cmd.
         */
        internal fun launch(server: McpServerConfig): McpProcess {
            val builder = ProcessBuilder(commandLine(server))
            builder.environment().putAll(server.env)
            return JvmMcpProcess(builder.start())
        }

        /** Construye la línea de comando para el servidor. */
        internal fun commandLine(server: McpServerConfig): List<String> {
            val base = listOf(server.command) + server.args
            if (!isWindows()) return base
            val batch = resolveBatch(server.command) ?: return base
            if (!batch.contains(' ')) {
                // Sin espacios: cmd resuelve el .cmd por PATHEXT.
                return listOf("cmd.exe", "/d", "/c", batch) + server.args
            }
            // Con espacios: la línea entera entre comillas extra.
            val tail = server.args.joinToString(" ") { "\"$it\"" }
            return listOf("cmd.exe", "/d", "/c", "\"\"$batch\" $tail\"")
        }

        /** ¿Estamos en Windows? */
        private fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /**
         * Si el comando es (o resuelve a) un `.cmd`/`.bat`, devuelve la ruta
         * concreta; si no, `null` (se puede ejecutar directo).
         */
        private fun resolveBatch(command: String): String? {
            val lower = command.lowercase()
            if (lower.endsWith(".cmd") || lower.endsWith(".bat")) return command
            val path = System.getenv("PATH") ?: return null
            for (dir in path.split(File.pathSeparator)) {
                if (dir.isBlank()) continue
                for (ext in listOf(".cmd", ".bat")) {
                    val candidate = File(dir, command + ext)
                    if (candidate.isFile) return candidate.absolutePath
                }
            }
            return null
        }
    }
}

/**
 * Conector por defecto: elige el transporte según la declaración.
 *
 * Hoy sólo hay stdio ([McpStdioConnector]). Para los demás transportes el
 * error lo dice explícitamente: la UI nunca muestra un servidor "conectado"
 * que en realidad no responde.
 */
class DefaultMcpConnector(timeoutMs: Long = McpStdioConnector.DEFAULT_TIMEOUT_MS) : McpConnector {

    private val stdio = McpStdioConnector(timeoutMs)

    override suspend fun connect(server: McpServerConfig): McpConnection = when {
        server.transport.equals("stdio", ignoreCase = true) -> stdio.connect(server)
        else -> throw McpUnavailableException(
            "El transporte '${server.transport}' todavía no está implementado: sólo stdio.",
        )
    }
}
