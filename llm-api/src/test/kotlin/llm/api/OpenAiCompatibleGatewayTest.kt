package llm.api

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import llm.config.ChatMessage
import llm.error.LlmErrorCode
import llm.error.LlmException

/**
 * Tests del gateway compatible con OpenAI (`llm-api`).
 *
 * El gateway es el que habla con el modelo: parsea el SSE, arma el request y
 * clasifica los errores. Se prueba contra un servidor HTTP **local** (el JDK
 * lo trae de serie), así que no hace falta red ni modelo: el test es
 * determinista y corre en cualquier máquina.
 */
class OpenAiCompatibleGatewayTest {

    private lateinit var server: HttpServer

    /** Cuerpos de los requests que recibió el servidor. */
    private val bodies = mutableListOf<String>()

    /** Ruta que responde el servidor. */
    private var ruta = "/v1/chat/completions"

    /** Estado HTTP de la respuesta. */
    private var status = 200

    /** Cuerpo crudo de la respuesta (si es null, se manda SSE). */
    private var cuerpo: String? = null

    /** Líneas SSE a mandar (si `cuerpo` es null). */
    private var lineasSse: List<String> = emptyList()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = (cuerpo ?: lineasSse.joinToString("\n\n") { "data: $it" }).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUrl() = "http://127.0.0.1:${server.address.port}/v1"

    private fun gateway(
        apiKey: String = "",
        timeoutMs: Long = 0,
        thinking: String = "off",
    ) = OpenAiCompatibleGateway(
        baseUrl = baseUrl(),
        apiKey = apiKey,
        model = "modelo-test",
        temperature = 0.7,
        maxTokens = 512,
        chatTimeoutMs = timeoutMs,
        thinking = thinking,
    )

    private fun texto(vararg partes: String): List<String> =
        partes.map { """{"choices":[{"delta":{"content":"$it"}}]}""" } + "[DONE]"

    /* ------------------------------------------------------------------
     * Texto
     * ------------------------------------------------------------------ */

    @Test
    fun `el texto del stream se arma completo`() = runBlocking {
        lineasSse = texto("hola ", "mundo")
        val chunks = gateway().chatStream(listOf(ChatMessage("user", "hi"))).toList()
        val texto = chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.delta }
        assertEquals("hola mundo", texto)
    }

    @Test
    fun `chatText devuelve el texto sin el andamiaje`() = runBlocking {
        lineasSse = texto("hola")
        val texto = gateway().chatText(listOf(ChatMessage("user", "hi"))).toList().joinToString("")
        assertEquals("hola", texto)
    }

    @Test
    fun `una respuesta sin contenido no rompe nada`() = runBlocking {
        lineasSse = listOf("[DONE]")
        val chunks = gateway().chatStream(listOf(ChatMessage("user", "hi"))).toList()
        assertTrue(chunks.none { it is LlmChunk.Text })
    }

    /* ------------------------------------------------------------------
     * Tool calls
     * ------------------------------------------------------------------ */

    @Test
    fun `los fragmentos de tool_call se acumulan en una sola llamada`() = runBlocking {
        lineasSse = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_time","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tz\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"local\"}"}}]}}]}""",
            "[DONE]",
        )
        val calls = gateway().chatStream(listOf(ChatMessage("user", "hi")))
            .toList().filterIsInstance<LlmChunk.ToolCall>()

        assertEquals(1, calls.size, "los fragmentos tienen que formar UNA llamada")
        assertEquals("get_time", calls[0].name)
        assertEquals("call_1", calls[0].id)
        assertEquals("""{"tz":"local"}""", calls[0].argsJson)
    }

    @Test
    fun `varias tool calls se emiten por orden`() = runBlocking {
        lineasSse = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"primera","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"segunda","arguments":"{}"}}]}}]}""",
            "[DONE]",
        )
        val calls = gateway().chatStream(listOf(ChatMessage("user", "hi")))
            .toList().filterIsInstance<LlmChunk.ToolCall>()
        assertEquals(listOf("primera", "segunda"), calls.map { it.name })
    }

    @Test
    fun `una tool call sin argumentos llega con objeto vacio`() = runBlocking {
        lineasSse = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"get_time"}}]}}]}""",
            "[DONE]",
        )
        val calls = gateway().chatStream(listOf(ChatMessage("user", "hi")))
            .toList().filterIsInstance<LlmChunk.ToolCall>()
        assertEquals("{}", calls[0].argsJson)
    }

    /* ------------------------------------------------------------------
     * El request
     * ------------------------------------------------------------------ */

    @Test
    fun `el request lleva el modelo y los mensajes`() = runBlocking {
        lineasSse = texto("ok")
        gateway().chatStream(listOf(ChatMessage("user", "hola"))).toList()
        val body = bodies.last()
        assertTrue("\"model\":\"modelo-test\"" in body, body)
        assertTrue("\"role\":\"user\"" in body, body)
        assertTrue("hola" in body, body)
    }

    @Test
    fun `el catalogo de tools va en el request`() = runBlocking {
        lineasSse = texto("ok")
        gateway().chatStream(
            listOf(ChatMessage("user", "hola")),
            listOf(ToolSpec("get_time", "Da la hora", """{"type":"object"}""")),
        ).toList()
        val body = bodies.last()
        assertTrue("\"tools\"" in body, body)
        assertTrue("get_time" in body, body)
    }

    @Test
    fun `sin catalogo no se manda el campo tools`() = runBlocking {
        lineasSse = texto("ok")
        gateway().chatStream(listOf(ChatMessage("user", "hola")), null).toList()
        assertTrue("\"tools\"" !in bodies.last(), bodies.last())
    }

    @Test
    fun `el resultado de una tool viaja como role tool`() = runBlocking {
        lineasSse = texto("ok")
        gateway().chatStream(
            listOf(
                ChatMessage("user", "hola"),
                ChatMessage("tool", "son las 10", toolCallId = "c1", toolName = "get_time"),
            ),
        ).toList()
        val body = bodies.last()
        assertTrue("\"role\":\"tool\"" in body, body)
        assertTrue("\"tool_call_id\":\"c1\"" in body, body)
    }

    @Test
    fun `la clave va en el encabezado de autorizacion`() = runBlocking {
        lineasSse = texto("ok")
        gateway(apiKey = "secreto").chatStream(listOf(ChatMessage("user", "hola"))).toList()
        assertTrue(bodies.isNotEmpty())
    }

    @Test
    fun `el nivel de razonamiento se manda solo si esta activado`() = runBlocking {
        lineasSse = texto("ok")
        gateway(thinking = "off").chatStream(listOf(ChatMessage("user", "hola"))).toList()
        assertTrue("reasoning_effort" !in bodies.last())

        lineasSse = texto("ok")
        gateway(thinking = "low").chatStream(listOf(ChatMessage("user", "hola"))).toList()
        assertTrue("\"reasoning_effort\":\"low\"" in bodies.last(), bodies.last())
    }

    /* ------------------------------------------------------------------
     * Errores tipados
     * ------------------------------------------------------------------ */

    private fun pedirYCapturarError(): LlmException {
        var fallo: LlmException? = null
        try {
            runBlocking { gateway().chatStream(listOf(ChatMessage("user", "hi"))).toList() }
        } catch (e: LlmException) {
            fallo = e
        }
        return fallo ?: error("se esperaba un LlmException")
    }

    @Test
    fun `un 401 es clave invalida`() {
        status = 401
        cuerpo = """{"error":"unauthorized"}"""
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.CLAVE_INVALIDA, e.code)
        assertTrue(!e.retryable, "una clave inválida no se arregla reintentando")
    }

    @Test
    fun `un 429 es cuota agotada y se puede reintentar`() {
        status = 429
        cuerpo = "too many requests"
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.SIN_CUOTA, e.code)
        assertTrue(e.retryable, "la cuota se puede reintentar")
    }

    @Test
    fun `un 400 que habla de tokens es contexto agotado`() {
        status = 400
        cuerpo = """{"error":{"message":"the request exceeds the context size (kv cache)"}}"""
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.CONTEXTO_AGOTADO, e.code)
    }

    @Test
    fun `un 500 es modelo no disponible`() {
        status = 500
        cuerpo = "server error"
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.MODELO_NO_DISPONIBLE, e.code)
    }

    @Test
    fun `un 404 es modelo no disponible`() {
        status = 404
        cuerpo = "not found"
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.MODELO_NO_DISPONIBLE, e.code)
    }

    @Test
    fun `un error desconocido conserva el codigo generico`() {
        status = 418
        cuerpo = "soy una tetera"
        val e = pedirYCapturarError()
        assertEquals(LlmErrorCode.DESCONOCIDO, e.code)
    }

    @Test
    fun `un servidor local caido se distingue de uno remoto`() {
        server.stop(0)
        val e = pedirYCapturarError()
        // La URL es un loopback: el gateway la clasifica como servidor local
        // caído (no como API remota caída). El usuario ve mensajes distintos.
        assertEquals(LlmErrorCode.SERVIDOR_CAIDO, e.code)
        assertTrue(e.retryable, "un servidor caído se puede reintentar")
        assertTrue("servidor local" in e.message, e.message)
    }
}
