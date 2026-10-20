package rag.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.test.runTest

/**
 * Tests del transporte MCP con un **proceso real** (F8).
 *
 * A diferencia de `McpStdioClientTest` (que usa un doble en memoria), acá se
 * lanza un proceso de verdad que habla JSON-RPC por stdin/stdout. Es lo que
 * valida el IO de verdad: el pipe, el handshake, el cierre del proceso y el
 * log a stderr.
 *
 * El servidor de prueba corre en la misma JVM del test, así que no hace
 * falta ningún intérprete extra en la máquina.
 */
class McpStdioProcessTest {

    @Test
    fun `el handshake con un proceso real funciona`() = runTest {
        val conexion = McpStdioConnector(timeoutMs = 15_000).connect(fakeServerConfig())

        assertEquals("real", conexion.serverId)
        assertEquals("2025-06-18", conexion.protocolVersion)
        conexion.close()
    }

    @Test
    fun `la lista de herramientas de un proceso real`() = runTest {
        val conexion = McpStdioConnector(timeoutMs = 15_000).connect(fakeServerConfig())

        val tools = conexion.listTools()
        assertEquals(1, tools.size)
        assertEquals("saludar", tools[0].name)
        assertTrue(tools[0].inputSchema.contains("nombre"), tools[0].inputSchema)
        conexion.close()
    }

    @Test
    fun `la llamada a una herramienta de un proceso real`() = runTest {
        val conexion = McpStdioConnector(timeoutMs = 15_000).connect(fakeServerConfig())

        val resultado = conexion.callTool("saludar", fakeToolArgs("mundo"))
        assertTrue(resultado is McpToolResult.Ok, "tenía que responder bien: $resultado")
        val texto = (resultado as McpToolResult.Ok).content
            .filterIsInstance<McpContent.Text>()
            .joinToString("") { it.text }
        assertTrue(texto.contains("mundo"), texto)
        conexion.close()
    }

    @Test
    fun `cerrar la conexion termina el proceso`() = runTest {
        val conexion = McpStdioConnector(timeoutMs = 15_000).connect(fakeServerConfig())
        val implementacion = conexion as StdioMcpConnection
        assertTrue(implementacion.processAlive())

        conexion.close()
        // El proceso puede tardar un instante en morirse: se le da un margen.
        val deadline = System.currentTimeMillis() + 3_000
        while (implementacion.processAlive() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
        assertFalse(implementacion.processAlive(), "el proceso tiene que quedar terminado")
    }

    @Test
    fun `el log a stderr del servidor no rompe la conexion`() = runTest {
        // El servidor de prueba escribe en stderr al arrancar.
        val conexion = McpStdioConnector(timeoutMs = 15_000).connect(fakeServerConfig())

        assertEquals(1, conexion.listTools().size)
        conexion.close()
    }

    @Test
    fun `el manager registra las herramientas de un proceso real`() = runTest {
        val manager = McpManager(
            connector = DefaultMcpConnector(timeoutMs = 15_000),
            servers = listOf(fakeServerConfig("real")),
        )

        val tools = manager.refresh()
        assertEquals(1, tools.size)
        assertEquals("mcp__real__saludar", tools[0].spec.name)
        assertTrue(
            tools[0].spec.description.contains("Servidor real"),
            "la descripción tiene que decir de qué servidor viene: ${tools[0].spec.description}",
        )

        val estado = manager.statuses().single()
        assertTrue(estado.connected, "el estado tiene que decir que conectó: $estado")
        assertEquals(1, estado.toolCount)

        manager.close()
    }

    @Test
    fun `un comando que no existe falla con un mensaje claro`() = runTest {
        val server = McpServerConfig(
            id = "fantasma",
            name = "Fantasma",
            command = "comando-que-no-existe-12345",
        )

        val error = runCatching { McpStdioConnector(timeoutMs = 5_000).connect(server) }.exceptionOrNull()

        assertTrue(error is McpUnavailableException, "tiene que ser un error de transporte: $error")
        assertTrue(
            error?.message.orEmpty().contains("No se pudo lanzar"),
            "el mensaje tiene que decir que no se pudo lanzar: ${error?.message}",
        )
    }

    @Test
    fun `el manager deja el estado del servidor que fallo`() = runTest {
        val manager = McpManager(
            connector = DefaultMcpConnector(timeoutMs = 5_000),
            servers = listOf(
                McpServerConfig(id = "fantasma", name = "Fantasma", command = "comando-que-no-existe-12345"),
                fakeServerConfig("bueno"),
            ),
        )

        val tools = manager.refresh()
        assertEquals(1, tools.size, "el servidor bueno sigue dando herramientas")

        val estados = manager.statuses()
        assertFalse(estados[0].connected)
        assertTrue(estados[0].error.contains("No se pudo lanzar"), estados[0].error)
        assertTrue(estados[1].connected)

        manager.close()
    }
}
