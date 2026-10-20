package rag.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolOrigin
import rag.agent.ToolResult
import rag.test.runTest

/**
 * Tests del adaptador MCP (F7).
 *
 * El adaptador es el puente: convierte una herramienta de un servidor MCP en
 * una tool de la app. Lo que importa es que se parezca a una tool más (mismo
 * contrato, mismos permisos, mismos adjuntos) y que no deje pasar errores en
 * silencio.
 */
class McpToolAdapterTest {

    /** Conexión falsa: responde lo que se le Configure. */
    private class FakeConnection(
        override val serverId: String = "servidor",
        override val displayName: String = "Servidor de prueba",
        private val tools: List<McpToolDescriptor> = listOf(
            McpToolDescriptor("buscar", "Busca cosas", """{"type":"object"}"""),
        ),
        private val response: McpToolResult = McpToolResult.Ok(listOf(McpContent.Text("resultado"))),
        private val errorOnCall: Exception? = null,
    ) : McpConnection {
        var closed = false
        val llamadas = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun listTools(): List<McpToolDescriptor> = tools

        override suspend fun callTool(name: String, args: JsonObject): McpToolResult {
            llamadas += name to args
            errorOnCall?.let { throw it }
            return response
        }

        override suspend fun close() {
            closed = true
        }
    }

    private fun ctx(dir: Path) = ToolContext(chatId = "test", outputsDir = dir)

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `el nombre califica al servidor`() {
        val adapter = McpToolAdapter(
            FakeConnection(serverId = "docs", displayName = "Documentación"),
            McpToolDescriptor("buscar", "Busca cosas"),
        )
        assertEquals("mcp__docs__buscar", adapter.spec.name)
    }

    @Test
    fun `el nombre limpio de caracteres raros`() {
        val adapter = McpToolAdapter(
            FakeConnection(serverId = "mi servidor", displayName = "X"),
            McpToolDescriptor("buscar archivos", "Busca"),
        )
        assertEquals("mcp__mi_servidor__buscar_archivos", adapter.spec.name)
    }

    @Test
    fun `una tool MCP pide permiso MCP`() {
        val adapter = McpToolAdapter(FakeConnection(), McpToolDescriptor("buscar"))
        assertEquals(ToolCategory.MCP, adapter.category)
    }

    @Test
    fun `una tool MCP declara su origen`() {
        val adapter = McpToolAdapter(FakeConnection(), McpToolDescriptor("buscar"))
        assertEquals(ToolOrigin.MCP, adapter.origin)
    }

    @Test
    fun `la descripcion menciona el servidor`() {
        val adapter = McpToolAdapter(
            FakeConnection(displayName = "Documentación"),
            McpToolDescriptor("buscar", "Busca cosas"),
        )
        assertTrue("Documentación" in adapter.spec.description)
        assertTrue("Busca cosas" in adapter.spec.description)
    }

    @Test
    fun `el esquema de argumentos viene del servidor`() {
        val adapter = McpToolAdapter(
            FakeConnection(),
            McpToolDescriptor("buscar", "Busca", """{"type":"object","properties":{"q":{"type":"string"}}}"""),
        )
        assertEquals("""{"type":"object","properties":{"q":{"type":"string"}}}""", adapter.spec.parametersSchema)
    }

    @Test
    fun `la llamada va al servidor con el nombre original`() = runTest {
        val connection = FakeConnection()
        val adapter = McpToolAdapter(connection, McpToolDescriptor("buscar"))
        adapter.execute(args("q" to "kotlin"), ctx(Files.createTempDirectory("mcp-")))
        assertEquals(1, connection.llamadas.size)
        assertEquals("buscar", connection.llamadas[0].first)
    }

    @Test
    fun `el texto del servidor vuelve al modelo`() = runTest {
        val adapter = McpToolAdapter(
            FakeConnection(response = McpToolResult.Ok(listOf(McpContent.Text("encontré 3 cosas")))),
            McpToolDescriptor("buscar"),
        )
        val result = adapter.execute(args(), ctx(Files.createTempDirectory("mcp-")))
        assertTrue(result is ToolResult.Ok)
        assertEquals("encontré 3 cosas", (result as ToolResult.Ok).text)
    }

    @Test
    fun `varios textos se unen`() = runTest {
        val adapter = McpToolAdapter(
            FakeConnection(
                response = McpToolResult.Ok(listOf(McpContent.Text("uno"), McpContent.Text("dos"))),
            ),
            McpToolDescriptor("buscar"),
        )
        val result = adapter.execute(args(), ctx(Files.createTempDirectory("mcp-")))
        assertTrue((result as ToolResult.Ok).text.contains("uno"))
        assertTrue(result.text.contains("dos"))
    }

    @Test
    fun `un resultado marcado como error vuelve como error`() = runTest {
        val adapter = McpToolAdapter(
            FakeConnection(response = McpToolResult.Ok(listOf(McpContent.Text("no encontré nada")), isError = true)),
            McpToolDescriptor("buscar"),
        )
        val result = adapter.execute(args(), ctx(Files.createTempDirectory("mcp-")))
        assertTrue(result is ToolResult.Error, "un error del servidor no es un éxito")
        assertTrue("no encontré nada" in (result as ToolResult.Error).message)
    }

    @Test
    fun `un error de transporte vuelve como error controlado`() = runTest {
        val adapter = McpToolAdapter(
            FakeConnection(errorOnCall = IllegalStateException("conexión caída")),
            McpToolDescriptor("buscar"),
        )
        val result = adapter.execute(args(), ctx(Files.createTempDirectory("mcp-")))
        assertTrue(result is ToolResult.Error)
        assertTrue("conexión caída" in (result as ToolResult.Error).message)
    }

    @Test
    fun `un contenido binario se guarda como adjunto`() = runTest {
        val dir = Files.createTempDirectory("mcp-")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val adapter = McpToolAdapter(
            FakeConnection(
                response = McpToolResult.Ok(
                    listOf(
                        McpContent.Text("acá está"),
                        McpContent.Binary("image/png", Base64.getEncoder().encodeToString(bytes)),
                    ),
                ),
            ),
            McpToolDescriptor("captura"),
        )
        val result = adapter.execute(args(), ctx(dir))
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertEquals(1, ok.artifacts.size)
        assertEquals("captura.png", ok.artifacts[0].name)
        assertEquals("image/png", ok.artifacts[0].mime)
        assertEquals(5L, ok.artifacts[0].sizeBytes)
        assertTrue(Files.isRegularFile(Path.of(ok.artifacts[0].path)))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `un binario que no se puede decodificar no rompe la llamada`() = runTest {
        val dir = Files.createTempDirectory("mcp-")
        val adapter = McpToolAdapter(
            FakeConnection(
                response = McpToolResult.Ok(
                    listOf(McpContent.Text("texto"), McpContent.Binary("image/png", "no-es-base64!!")),
                ),
            ),
            McpToolDescriptor("captura"),
        )
        val result = adapter.execute(args(), ctx(dir))
        assertTrue(result is ToolResult.Ok, "el texto sigue valiendo: $result")
        assertEquals(0, (result as ToolResult.Ok).artifacts.size)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `el resumen para el dialogo de permiso dice de donde viene`() {
        val adapter = McpToolAdapter(
            FakeConnection(displayName = "Documentación"),
            McpToolDescriptor("buscar"),
        )
        val resumen = adapter.describeCall(args())
        assertTrue("buscar" in resumen)
        assertTrue("Documentación" in resumen)
    }

    @Test
    fun `el identificador sucio se limpia`() {
        assertEquals("a_b", McpToolAdapter.sanitizeIdent("a b"))
        assertEquals("a-b_c", McpToolAdapter.sanitizeIdent("a-b.c"))
        assertEquals("tool", McpToolAdapter.sanitizeIdent("   "))
    }

    @Test
    fun `el texto del servidor sin texto no rompe nada`() = runTest {
        val adapter = McpToolAdapter(
            FakeConnection(response = McpToolResult.Ok(emptyList())),
            McpToolDescriptor("buscar"),
        )
        val result = adapter.execute(args(), ctx(Files.createTempDirectory("mcp-")))
        assertTrue(result is ToolResult.Ok)
        assertFalse((result as ToolResult.Ok).text.isBlank(), "tiene que decir algo, aunque sea")
    }
}
