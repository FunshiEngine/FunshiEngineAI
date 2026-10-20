package rag.agent.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.agent.ToolContext
import rag.agent.ToolResult
import rag.test.runTest

/**
 * Tests de las tools de archivo (F3).
 *
 * La regla de oro de `create_file` es simple: escribe DENTRO de la carpeta de
 * salidas del chat y nada más. Un nombre con `..` o una ruta absoluta rompería
 * esa regla, así que la validación se testea caso por caso.
 */
class FileToolsTest {

    private fun ctx(dir: Path) = ToolContext(chatId = "test", outputsDir = dir)

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `crea el archivo en la carpeta del chat`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(
            args("name" to "escena.py", "content" to "print('hola')"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Ok, "se esperaba un OK: $result")
        val ok = result as ToolResult.Ok
        assertEquals(1, ok.artifacts.size)
        assertEquals("escena.py", ok.artifacts[0].name)
        assertEquals("text/x-python", ok.artifacts[0].mime)
        assertEquals("print('hola')", Files.readString(Path.of(ok.artifacts[0].path)))
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `la categoria es FILES`() {
        assertEquals(rag.agent.ToolCategory.FILES, CreateFileTool().category)
        assertEquals(rag.agent.ToolCategory.FILES, DownloadUrlTool().category)
    }

    @Test
    fun `rechaza un nombre con ruta`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(
            args("name" to "sub/carpeta/archivo.py", "content" to "x"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Error)
        assertTrue("ruta" in (result as ToolResult.Error).message.lowercase())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `rechaza un nombre con puntos suspensivos`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(
            args("name" to "../fuera.py", "content" to "x"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Error)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `rechaza una extension no permitida`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(
            args("name" to "virus.exe", "content" to "x"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Error)
        assertTrue("xtensión" in (result as ToolResult.Error).message)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `rechaza un nombre vacio`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(args("name" to "  ", "content" to "x"), ctx(dir))
        assertTrue(result is ToolResult.Error)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `acepta los nombres clasicos sin extension`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val result = CreateFileTool().execute(args("name" to "README", "content" to "x"), ctx(dir))
        assertTrue(result is ToolResult.Ok, "README es un nombre clásico: $result")
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `acepta las extensiones de codigo`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        for (nombre in listOf("a.kt", "b.cpp", "c.glsl", "d.html", "e.css", "f.js", "g.json", "h.rs", "i.tex")) {
            val result = CreateFileTool().execute(args("name" to nombre, "content" to "x"), ctx(dir))
            assertTrue(result is ToolResult.Ok, "$nombre tiene que aceptarse: $result")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `rechaza un archivo demasiado grande`() = runTest {
        val dir = Files.createTempDirectory("files-test-")
        val grande = "a".repeat((CreateFileTool.MAX_FILE_BYTES + 1).toInt())
        val result = CreateFileTool().execute(args("name" to "grande.txt", "content" to grande), ctx(dir))
        assertTrue(result is ToolResult.Error)
        assertTrue("grande" in (result as ToolResult.Error).message.lowercase())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `el mime se deduce de la extension`() {
        assertEquals("application/pdf", mimeOf("informe.pdf"))
        assertEquals("text/markdown", mimeOf("LEEME.md"))
        assertEquals("application/json", mimeOf("datos.json"))
        assertEquals("", mimeOf("sin-extension"))
    }

    @Test
    fun `un argumento ausente se lee como texto vacio`() {
        val obj = JsonObject(mapOf("a" to JsonPrimitive("x")))
        assertEquals("x", obj.str("a"))
        assertEquals("", obj.str("b"))
        assertEquals("", JsonObject(emptyMap()).str("a"))
    }
}
