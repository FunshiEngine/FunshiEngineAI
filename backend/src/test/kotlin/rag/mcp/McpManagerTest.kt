package rag.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.test.runTest

/**
 * Tests del administrador de servidores MCP (F7).
 *
 * El manager no sabe de transporte: pide conexiones al conector. Acá se prueba
 * con un conector falso, así no hace falta ningún servidor MCP de verdad.
 */
class McpManagerTest {

    private class FakeConnection(
        override val serverId: String,
        override val displayName: String,
        private val tools: List<McpToolDescriptor> = listOf(McpToolDescriptor("buscar")),
    ) : McpConnection {
        var closed = false
        override suspend fun listTools(): List<McpToolDescriptor> = tools
        override suspend fun callTool(name: String, args: kotlinx.serialization.json.JsonObject) =
            McpToolResult.Ok(listOf(McpContent.Text("ok")))

        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun `sin servidores no hay herramientas`() = runTest {
        val manager = McpManager()
        assertTrue(manager.tools().isEmpty())
        assertTrue(manager.refresh().isEmpty())
    }

    @Test
    fun `las herramientas del servidor se registran`() = runTest {
        val server = McpServerConfig(id = "docs", name = "Documentación", command = "docs-server")
        val manager = McpManager(
            connector = { FakeConnection("docs", "Documentación", listOf(McpToolDescriptor("buscar"))) },
            servers = listOf(server),
        )
        val tools = manager.refresh()
        assertEquals(1, tools.size)
        assertEquals("mcp__docs__buscar", tools[0].spec.name)
    }

    @Test
    fun `el estado dice cuantos servidores conectaron`() = runTest {
        val servers = listOf(
            McpServerConfig(id = "a", command = "x"),
            McpServerConfig(id = "b", command = "y"),
        )
        val manager = McpManager(
            connector = { server -> FakeConnection(server.id, server.id) },
            servers = servers,
        )
        manager.refresh()
        val estados = manager.statuses()
        assertEquals(2, estados.size)
        assertTrue(estados.all { it.connected })
        assertEquals(1, estados[0].toolCount)
    }

    @Test
    fun `un servidor deshabilitado no se conecta`() = runTest {
        var conectado = false
        val manager = McpManager(
            connector = { conectado = true; FakeConnection(it.id, it.id) },
            servers = listOf(McpServerConfig(id = "a", command = "x", enabled = false)),
        )
        manager.refresh()
        assertFalse(conectado, "un servidor deshabilitado no se toca")
        assertFalse(manager.statuses()[0].connected)
        assertTrue(manager.statuses()[0].error.contains("Deshabilitado"))
    }

    @Test
    fun `un servidor que falla no tira abajo a los demas`() = runTest {
        val manager = McpManager(
            connector = { server ->
                if (server.id == "malo") throw McpUnavailableException("no se pudo abrir")
                FakeConnection(server.id, server.id)
            },
            servers = listOf(
                McpServerConfig(id = "malo", command = "x"),
                McpServerConfig(id = "bueno", command = "y"),
            ),
        )
        val tools = manager.refresh()
        assertEquals(1, tools.size, "el servidor bueno sigue dando herramientas")
        val estados = manager.statuses()
        assertFalse(estados[0].connected)
        assertTrue(estados[0].error.contains("no se pudo abrir"))
        assertTrue(estados[1].connected)
    }

    @Test
    fun `un servidor sin comando se marca como incompleto`() = runTest {
        val manager = McpManager(servers = listOf(McpServerConfig(id = "a", command = "")))
        manager.refresh()
        val estado = manager.statuses()[0]
        assertFalse(estado.connected)
        assertTrue(estado.error.contains("comando"), "el error tiene que decir qué falta: ${estado.error}")
    }

    @Test
    fun `un servidor http sin url se marca como incompleto`() = runTest {
        val manager = McpManager(servers = listOf(McpServerConfig(id = "a", transport = "http", url = "")))
        manager.refresh()
        assertTrue(manager.statuses()[0].error.contains("URL"))
    }

    @Test
    fun `refrescar cierra las conexiones viejas`() = runTest {
        val conexiones = mutableListOf<FakeConnection>()
        val manager = McpManager(
            connector = { FakeConnection(it.id, it.id).also { c -> conexiones += c } },
            servers = listOf(McpServerConfig(id = "a", command = "x")),
        )
        manager.refresh()
        manager.refresh()
        assertTrue(conexiones[0].closed, "la conexión anterior se cierra al refrescar")
        assertFalse(conexiones[1].closed)
    }

    @Test
    fun `cerrar cierra las conexiones`() = runTest {
        val conexiones = mutableListOf<FakeConnection>()
        val manager = McpManager(
            connector = { FakeConnection(it.id, it.id).also { c -> conexiones += c } },
            servers = listOf(McpServerConfig(id = "a", command = "x")),
        )
        manager.refresh()
        manager.close()
        assertTrue(conexiones[0].closed)
        assertTrue(manager.tools().isEmpty())
    }

    @Test
    fun `el conector por defecto dice que falta el transporte`() = runTest {
        val manager = McpManager(servers = listOf(McpServerConfig(id = "a", command = "x")))
        manager.refresh()
        val estado = manager.statuses()[0]
        assertFalse(estado.connected)
        assertTrue(
            estado.error.contains("todavía no está implementado"),
            "el estado tiene que explicar por qué no conecta: ${estado.error}",
        )
    }

    @Test
    fun `la config de un servidor se valida`() {
        assertTrue(McpServerConfig(id = "a", command = "x").problems().isEmpty())
        assertTrue(McpServerConfig(id = "a", command = "").problems().isNotEmpty())
        assertTrue(McpServerConfig(id = "", command = "x").problems().isNotEmpty())
        assertTrue(McpServerConfig(id = "a", transport = "http", url = "http://x").problems().isEmpty())
    }
}
