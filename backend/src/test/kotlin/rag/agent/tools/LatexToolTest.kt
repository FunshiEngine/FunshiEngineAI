package rag.agent.tools

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.agent.ToolResult
import rag.test.runTest

/**
 * Tests de la tool `latex_compile` (F5b).
 *
 * Los tests no necesitan TeX instalado: la búsqueda del motor es inyectable,
 * así que se puede simular uno. Eso permite probar el camino feliz (PDF
 * generado) y el camino de error (LaTeX falla) de forma determinista.
 */
class LatexToolTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun ctx(dir: Path) = rag.agent.ToolContext(chatId = "test", outputsDir = dir)

    /**
     * Escribe un motor falso que crea un PDF junto al `.tex` que recibe.
     * Devuelve la ruta del ejecutable.
     */
    private fun fakeEngine(dir: Path, name: String, exitCode: Int, output: String = ""): String {
        if (isWindows) {
            val bat = dir.resolve("$name.cmd")
            Files.write(
                bat,
                listOf(
                    "@echo off",
                    "set BASE=",
                    "for %%A in (%*) do set BASE=%%~nA",
                    if (output.isNotEmpty()) "echo $output" else "echo compilando",
                    "echo %%PDF-1.4 falso> %BASE%.pdf",
                    "exit /b $exitCode",
                ),
            )
            return bat.toAbsolutePath().toString()
        }
        val sh = dir.resolve(name)
        Files.write(
            sh,
            listOf(
                "#!/bin/sh",
                "base=\"\"",
                "for a in \"\$@\"; do base=\"\${a%.*}\"; done",
                "echo '${output.ifEmpty { "compilando" }}'",
                "printf '%%PDF-1.4 falso' > \"\$base.pdf\"",
                "exit $exitCode",
            ),
        )
        sh.toFile().setExecutable(true)
        return sh.toAbsolutePath().toString()
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun `sin motor instalado falla rapido con la guia de instalacion`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val tool = LatexCompileTool(engineFinder = { null })
        val result = tool.execute(args("document" to "\\section{Hola}"), ctx(dir))
        assertTrue(result is ToolResult.Error)
        val message = (result as ToolResult.Error).message
        assertTrue("TeX Live" in message, "el error debe decir cómo instalarlo: $message")
        assertTrue("MiKTeX" in message, "el error debe decir cómo instalarlo: $message")
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `un motor desconocido se rechaza antes de tocar el disco`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val tool = LatexCompileTool(engineFinder = { null })
        val result = tool.execute(
            args("document" to "x", "engine" to "latex"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Error)
        assertTrue("desconocido" in (result as ToolResult.Error).message.lowercase())
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `el motor pedido que no esta instalado dice cual falta`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val tool = LatexCompileTool(engineFinder = { null })
        val result = tool.execute(
            args("document" to "x", "engine" to "xelatex"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Error)
        assertTrue("xelatex" in (result as ToolResult.Error).message)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `un documento vacio se rechaza`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val tool = LatexCompileTool(engineFinder = { null })
        val result = tool.execute(args("document" to "   "), ctx(dir))
        assertTrue(result is ToolResult.Error)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `compila y deja el pdf y el tex como adjuntos`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val engineDir = Files.createTempDirectory("latex-engine-")
        val engine = fakeEngine(engineDir, "pdflatex", exitCode = 0)
        val tool = LatexCompileTool(engineFinder = { if (it == "pdflatex") engine else null })

        val result = tool.execute(
            args("document" to "\\section{Hola}\nCon acentos.", "name" to "informe"),
            ctx(dir),
        )

        assertTrue(result is ToolResult.Ok, "se esperaba un PDF: $result")
        val ok = result as ToolResult.Ok
        assertEquals(2, ok.artifacts.size, "deben salir el PDF y el .tex")
        assertEquals("informe.pdf", ok.artifacts[0].name)
        assertEquals("application/pdf", ok.artifacts[0].mime)
        assertEquals("informe.tex", ok.artifacts[1].name)
        assertEquals("application/x-tex", ok.artifacts[1].mime)
        assertTrue(Files.isRegularFile(Path.of(ok.artifacts[0].path)), "el PDF debe existir")
        assertTrue(Files.size(Path.of(ok.artifacts[0].path)) > 0)

        // El .tex lleva el preámbulo estándar (el modelo mandó un fragmento).
        val tex = Files.readString(Path.of(ok.artifacts[1].path))
        assertTrue("\\documentclass" in tex, "el fragmento debe recibir un preámbulo")
        assertTrue("\\begin{document}" in tex)
        assertTrue("\\section{Hola}" in tex)

        dir.toFile().deleteRecursively()
        engineDir.toFile().deleteRecursively()
    }

    @Test
    fun `un documento completo se respeta tal cual`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val engineDir = Files.createTempDirectory("latex-engine-")
        val engine = fakeEngine(engineDir, "pdflatex", exitCode = 0)
        val tool = LatexCompileTool(engineFinder = { if (it == "pdflatex") engine else null })
        val completo = "\\documentclass{book}\n\\begin{document}\nHola\n\\end{document}"

        val result = tool.execute(args("document" to completo), ctx(dir))
        assertTrue(result is ToolResult.Ok)
        val tex = Files.readString(Path.of((result as ToolResult.Ok).artifacts[1].path))
        assertTrue("\\documentclass{book}" in tex)
        assertTrue("\\documentclass[11pt,a4paper]" !in tex, "no se debe sobreescribir el documento del modelo")

        dir.toFile().deleteRecursively()
        engineDir.toFile().deleteRecursively()
    }

    @Test
    fun `el nombre se sanea y no puede traer rutas`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val engineDir = Files.createTempDirectory("latex-engine-")
        val engine = fakeEngine(engineDir, "pdflatex", exitCode = 0)
        val tool = LatexCompileTool(engineFinder = { if (it == "pdflatex") engine else null })

        val result = tool.execute(
            args("document" to "x", "name" to "../../etc/passwd"),
            ctx(dir),
        )
        assertTrue(result is ToolResult.Ok)
        val name = (result as ToolResult.Ok).artifacts[0].name
        assertTrue("/" !in name && "\\" !in name, "el nombre no puede traer separadores: $name")
        assertTrue(".." !in name, "el nombre no puede traer ..: $name")

        dir.toFile().deleteRecursively()
        engineDir.toFile().deleteRecursively()
    }

    @Test
    fun `un error de latex vuelve al modelo con el motivo`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val engineDir = Files.createTempDirectory("latex-engine-")
        val engine = fakeEngine(engineDir, "pdflatex", exitCode = 1, output = "! Undefined control sequence.")
        val tool = LatexCompileTool(engineFinder = { if (it == "pdflatex") engine else null })

        val result = tool.execute(args("document" to "\\foo"), ctx(dir))
        assertTrue(result is ToolResult.Error)
        val message = (result as ToolResult.Error).message
        assertTrue("Undefined control sequence" in message, "el error de LaTeX debe volver: $message")

        dir.toFile().deleteRecursively()
        engineDir.toFile().deleteRecursively()
    }

    @Test
    fun `no quedan archivos temporales en la carpeta del chat`() = runTest {
        val dir = Files.createTempDirectory("latex-test-")
        val engineDir = Files.createTempDirectory("latex-engine-")
        val engine = fakeEngine(engineDir, "pdflatex", exitCode = 0)
        val tool = LatexCompileTool(engineFinder = { if (it == "pdflatex") engine else null })

        tool.execute(args("document" to "x"), ctx(dir))

        val files = Files.list(dir).use { it.toList() }.map { it.fileName.toString() }
        assertEquals(listOf("documento.pdf", "documento.tex"), files.sorted())

        dir.toFile().deleteRecursively()
        engineDir.toFile().deleteRecursively()
    }
}
