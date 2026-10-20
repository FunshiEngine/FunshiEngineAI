package rag.mcp

import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.test.runTest

/** Fin de la salida del proceso falso. */
private val fin = Any()

/**
 * Tests del cliente MCP por stdio (F8).
 *
 * El proceso del servidor es un **doble en memoria**: habla JSON-RPC sin
 * lanzar nada. Así los tests son deterministas y rápidos, y cubren el
 * protocolo (handshake, `tools/list`, `tools/call`, errores, timeout, ruido).
 *
 * El proceso real se prueba aparte, en `McpStdioProcessTest`.
 */
class McpStdioClientTest {

    /* ------------------------------------------------------------------
     * Doubles
     * ------------------------------------------------------------------ */

    /**
     * Servidor MCP de mentira: responde JSON-RPC en memoria.
     *
     * @param responder qué contestar a cada método (null = no contesta).
     */
    private class FakeMcpProcess(
        private val responder: (method: String, params: JsonObject, id: Int) -> String?,
    ) : McpProcess {
        /** Todos los mensajes que mandó el cliente, en orden. */
        val received = mutableListOf<JsonObject>()

        /** Líneas que se cuelan antes de cada respuesta (ruido del servidor). */
        var ruido: String? = null

        /** Notificación que el servidor manda antes de responder. */
        var notificacion: String? = null

        private val salida = LinkedBlockingQueue<Any>()
        private var cerrado = false

        override fun write(line: String) {
            val mensaje = McpProtocol.parse(line) ?: return
            received += mensaje
            val method = McpProtocol.methodOf(mensaje) ?: return
            val id = McpProtocol.idOf(mensaje) ?: return
            ruido?.let { salida.put(it) }
            notificacion?.let { salida.put(it) }
            val respuesta = responder(method, mensaje["params"] as? JsonObject ?: JsonObject(emptyMap()), id)
            if (respuesta != null) salida.put(respuesta)
        }

        /** Entrega una línea al cliente, como si el servidor la mandara. */
        fun entregar(line: String) {
            salida.put(line)
        }

        override fun readLine(): String? {
            val item = salida.take()
            return if (item === fin) null else item as String
        }

        override fun closeInput() {
            cerrado = true
            salida.put(fin)
        }

        override fun terminate() {
            cerrado = true
            salida.put(fin)
        }

        override fun kill() {
            cerrado = true
            salida.put(fin)
        }

        override fun isAlive(): Boolean = !cerrado

        override fun stderrTail(): String = ""
    }

    /** Servidor que responde como uno de verdad. */
    private fun servidor(
        tools: List<Triple<String, String, String>> = listOf(
            Triple(
                "buscar",
                "Busca en la documentación",
                """{"type":"object","properties":{"q":{"type":"string"}}}""",
            ),
        ),
    ): (String, JsonObject, Int) -> String? = { method, params, id ->
        when (method) {
            "initialize" -> """
                {"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18",
                "capabilities":{"tools":{"listChanged":false}},
                "serverInfo":{"name":"servidor-falso","version":"1.0"}}}
            """.trimIndent().replace("\n", "")
            "tools/list" -> {
                val lista = tools.joinToString(",") { (n, d, s) ->
                    """{"name":"$n","description":"$d","inputSchema":$s}"""
                }
                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[$lista]}}"""
            }
            "tools/call" -> {
                val nombre = (params["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"resultado de $nombre"}],"isError":false}}"""
            }
            else -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"método desconocido"}}"""
        }
    }

    /** Conector que usa el proceso falso. */
    private fun conector(
        proceso: McpProcess,
        timeoutMs: Long = 2_000,
    ) = McpStdioConnector(timeoutMs) { proceso }

    private fun servidorDeclarado(command: String = "servidor-falso") =
        McpServerConfig(id = "docs", name = "Documentación", command = command)

    /* ------------------------------------------------------------------
     * Handshake
     * ------------------------------------------------------------------ */

    @Test
    fun `el handshake devuelve la version del protocolo`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        assertEquals("2025-06-18", conexion.protocolVersion)
        assertEquals("docs", conexion.serverId)
        conexion.close()
    }

    @Test
    fun `el handshake envia initialize y despues la notificacion`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        val metodos = proceso.received.mapNotNull { McpProtocol.methodOf(it) }
        assertEquals(listOf("initialize", "notifications/initialized"), metodos)
        conexion.close()
    }

    @Test
    fun `un servidor que no responde el handshake da un error claro`() = runTest {
        val proceso = FakeMcpProcess { _, _, _ -> null }
        val error = runCatching { conector(proceso, timeoutMs = 300).connect(servidorDeclarado()) }
            .exceptionOrNull()

        assertNotNull(error, "tenía que fallar el handshake")
        assertTrue(error is McpUnavailableException, "el error tiene que ser de transporte: $error")
        assertTrue(
            error?.message.orEmpty().contains("no respondió"),
            "el mensaje tiene que decir que el servidor no respondió: ${error?.message}",
        )
    }

    @Test
    fun `sin comando no se lanza nada`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val error = runCatching {
            conector(proceso).connect(McpServerConfig(id = "x", command = ""))
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is McpUnavailableException)
        assertTrue(error?.message.orEmpty().contains("comando"), error?.message.orEmpty())
        assertTrue(proceso.received.isEmpty(), "no se puede mandar nada sin proceso")
    }

    @Test
    fun `el transporte http dice que no esta implementado`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val error = runCatching {
            conector(proceso).connect(
                McpServerConfig(id = "x", transport = "http", url = "http://localhost:1/mcp"),
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is McpUnavailableException)
        assertTrue(error?.message.orEmpty().contains("no está implementado"), error?.message.orEmpty())
    }

    /* ------------------------------------------------------------------
     * tools/list
     * ------------------------------------------------------------------ */

    @Test
    fun `la lista de herramientas trae nombre descripcion y esquema`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        val tools = conexion.listTools()
        assertEquals(1, tools.size)
        assertEquals("buscar", tools[0].name)
        assertEquals("Busca en la documentación", tools[0].description)
        assertTrue(tools[0].inputSchema.contains("\"q\""), tools[0].inputSchema)
        conexion.close()
    }

    @Test
    fun `un servidor sin herramientas devuelve una lista vacia`() = runTest {
        val proceso = FakeMcpProcess { method, _, id ->
            when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}"""
                "tools/list" -> """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}"""
                else -> null
            }
        }
        val conexion = conector(proceso).connect(servidorDeclarado())

        assertTrue(conexion.listTools().isEmpty())
        conexion.close()
    }

    /* ------------------------------------------------------------------
     * tools/call
     * ------------------------------------------------------------------ */

    @Test
    fun `la llamada devuelve el texto del servidor`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        val resultado = conexion.callTool("buscar", buildJsonObject { put("q", "kotlin") })
        assertTrue(resultado is McpToolResult.Ok)
        val contenido = (resultado as McpToolResult.Ok).content
        assertEquals(1, contenido.size)
        assertEquals("resultado de buscar", (contenido[0] as McpContent.Text).text)
        conexion.close()
    }

    @Test
    fun `los argumentos llegan al servidor`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        conexion.callTool("buscar", buildJsonObject { put("q", "kotlin") })
        val llamada = proceso.received.first { McpProtocol.methodOf(it) == "tools/call" }
        val params = llamada["params"] as JsonObject
        assertEquals("buscar", (params["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        val args = params["arguments"] as JsonObject
        assertEquals("kotlin", (args["q"] as kotlinx.serialization.json.JsonPrimitive).content)
        conexion.close()
    }

    @Test
    fun `el contenido binario se decodifica`() = runTest {
        val proceso = FakeMcpProcess { method, _, id ->
            when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}"""
                "tools/call" -> """
                    {"jsonrpc":"2.0","id":$id,"result":{"content":[
                      {"type":"text","text":"mira esto"},
                      {"type":"image","data":"aGk=","mimeType":"image/png"}]}}
                """.trimIndent().replace("\n", "")
                else -> null
            }
        }
        val conexion = conector(proceso).connect(servidorDeclarado())

        val resultado = conexion.callTool("dibujar", JsonObject(emptyMap())) as McpToolResult.Ok
        assertEquals(2, resultado.content.size)
        val binario = resultado.content[1] as McpContent.Binary
        assertEquals("image/png", binario.mime)
        assertEquals("aGk=", binario.base64)
        conexion.close()
    }

    @Test
    fun `un resultado con isError se marca como error`() = runTest {
        val proceso = FakeMcpProcess { method, _, id ->
            when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}"""
                "tools/call" -> """
                    {"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"no hay resultados"}],"isError":true}}
                """.trimIndent().replace("\n", "")
                else -> null
            }
        }
        val conexion = conector(proceso).connect(servidorDeclarado())

        val resultado = conexion.callTool("buscar", JsonObject(emptyMap())) as McpToolResult.Ok
        assertTrue(resultado.isError, "el resultado tiene que quedar marcado como error")
        conexion.close()
    }

    @Test
    fun `un error del protocolo se informa como error de la herramienta`() = runTest {
        val proceso = FakeMcpProcess { method, _, id ->
            when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}"""
                "tools/call" -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32602,"message":"arguments inválidos"}}"""
                else -> null
            }
        }
        val conexion = conector(proceso).connect(servidorDeclarado())

        val resultado = conexion.callTool("buscar", JsonObject(emptyMap()))
        assertTrue(resultado is McpToolResult.Error, "tiene que ser un error controlado")
        assertTrue(
            (resultado as McpToolResult.Error).message.contains("arguments inválidos"),
            resultado.message,
        )
        conexion.close()
    }

    @Test
    fun `un servidor que no responde da timeout`() = runTest {
        val proceso = FakeMcpProcess { method, _, id ->
            when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18"}}"""
                else -> null // no contesta el tools/call
            }
        }
        val conexion = conector(proceso, timeoutMs = 300).connect(servidorDeclarado())

        val resultado = conexion.callTool("buscar", JsonObject(emptyMap()))
        assertTrue(resultado is McpToolResult.Error)
        assertTrue(
            (resultado as McpToolResult.Error).message.contains("no respondió"),
            resultado.message,
        )
        conexion.close()
    }

    /* ------------------------------------------------------------------
     * Ruido y notificaciones
     * ------------------------------------------------------------------ */

    @Test
    fun `el ruido en la salida del servidor se ignora`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        proceso.ruido = "2026-10-20 INFO: arrancando el servidor"
        val conexion = conector(proceso).connect(servidorDeclarado())

        val tools = conexion.listTools()
        assertEquals(1, tools.size, "el ruido no puede romper la conexión")
        conexion.close()
    }

    @Test
    fun `una notificacion del servidor no rompe la conexion`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        proceso.notificacion = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
        val conexion = conector(proceso).connect(servidorDeclarado())

        assertEquals(1, conexion.listTools().size)
        conexion.close()
    }

    @Test
    fun `una peticion del servidor al cliente se responde con error`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        // El servidor pide algo que el cliente no implementa.
        proceso.entregar("""{"jsonrpc":"2.0","id":99,"method":"sampling/createMessage"}""")

        // La respuesta del cliente llega por el mismo canal de escritura.
        val respuesta = waitFor { proceso.received.any { it.containsKey("error") } }
        assertTrue(respuesta, "el cliente tiene que responder que no soporta el método")
    }

    /* ------------------------------------------------------------------
     * Cierre
     * ------------------------------------------------------------------ */

    @Test
    fun `cerrar la conexion cierra el proceso`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        conexion.close()
        assertFalse(proceso.isAlive(), "el proceso tiene que quedar cerrado")
    }

    @Test
    fun `cerrar dos veces no rompe`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())

        conexion.close()
        conexion.close()
    }

    @Test
    fun `una conexion cerrada no acepta mas peticiones`() = runTest {
        val proceso = FakeMcpProcess(servidor())
        val conexion = conector(proceso).connect(servidorDeclarado())
        conexion.close()

        val error = runCatching { conexion.listTools() }.exceptionOrNull()
        assertNotNull(error, "una conexión cerrada tiene que fallar")
    }

    /** Espera a que se cumpla una condición (con tope de tiempo). */
    private suspend fun waitFor(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            kotlinx.coroutines.delay(10)
        }
        return condition()
    }
}
