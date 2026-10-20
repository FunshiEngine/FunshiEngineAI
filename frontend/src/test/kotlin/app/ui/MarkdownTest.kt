package app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del parser de markdown (F2) y de los nodos de matemática (F5a).
 *
 * El parser es el que decide qué se ve en el chat. Si rompe un bloque de
 * código o se come texto, el modelo escribió bien y la app lo arruinó.
 */
class MarkdownTest {

    private fun nodes(src: String): List<MdNode> = parseMarkdown(src)

    @Test
    fun `un texto vacio no da nodos`() {
        assertTrue(nodes("").isEmpty())
    }

    @Test
    fun `un encabezado conserva su nivel`() {
        val h = nodes("## Título").first()
        assertTrue(h is MdNode.Heading)
        assertEquals(2, (h as MdNode.Heading).level)
        assertEquals("Título", h.text)
    }

    @Test
    fun `un parrafo une las lineas`() {
        val p = nodes("primera línea\nsegunda línea").first()
        assertTrue(p is MdNode.Paragraph)
        assertEquals("primera línea segunda línea", (p as MdNode.Paragraph).text)
    }

    @Test
    fun `un bloque de codigo conserva el lenguaje`() {
        val c = nodes("```kotlin\nval x = 1\n```").first()
        assertTrue(c is MdNode.Code)
        assertEquals("kotlin", (c as MdNode.Code).lang)
        assertEquals("val x = 1", c.text)
    }

    @Test
    fun `un bloque de codigo sin lenguaje es texto`() {
        val c = nodes("```\nlo que sea\n```").first()
        assertEquals("text", (c as MdNode.Code).lang)
    }

    @Test
    fun `un bloque de codigo sin cerrar se marca`() {
        val c = nodes("```py\nprint(1)").first()
        assertTrue(c is MdNode.Code)
        assertTrue(!(c as MdNode.Code).closed, "un bloque sin cerrar tiene que quedar marcado")
        assertEquals("py", c.lang)
    }

    @Test
    fun `el contenido del codigo no se interpreta como markdown`() {
        val c = nodes("```md\n# no soy un encabezado\n```").first()
        assertTrue(c is MdNode.Code)
        assertEquals("# no soy un encabezado", (c as MdNode.Code).text)
    }

    @Test
    fun `una lista con vinetas`() {
        val b = nodes("- uno\n- dos").first()
        assertTrue(b is MdNode.Bullets)
        assertEquals(listOf("uno", "dos"), (b as MdNode.Bullets).items)
        assertTrue(!(b as MdNode.Bullets).ordered)
    }

    @Test
    fun `una lista numerada`() {
        val b = nodes("1. uno\n2. dos").first()
        assertTrue(b is MdNode.Bullets)
        assertEquals(listOf("uno", "dos"), (b as MdNode.Bullets).items)
        assertTrue((b as MdNode.Bullets).ordered)
    }

    @Test
    fun `una cita`() {
        val q = nodes("> texto citado").first()
        assertTrue(q is MdNode.Quote)
        assertEquals("texto citado", (q as MdNode.Quote).text)
    }

    @Test
    fun `una regla horizontal`() {
        assertTrue(nodes("---").first() is MdNode.Divider)
    }

    @Test
    fun `una tabla con encabezado y filas`() {
        val t = nodes("| a | b |\n|---|---|\n| 1 | 2 |").first()
        assertTrue(t is MdNode.Table)
        assertEquals(listOf("a", "b"), (t as MdNode.Table).header)
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test
    fun `una formula centrada en una linea`() {
        val m = nodes("\$\$x^2\$\$").first()
        assertTrue(m is MdNode.Math)
        assertEquals("x^2", (m as MdNode.Math).latex)
    }

    @Test
    fun `una formula centrada de varias lineas`() {
        val m = nodes("\$\$\n\\frac{a}{b}\n\$\$").first()
        assertTrue(m is MdNode.Math)
        assertTrue("\\frac{a}{b}" in (m as MdNode.Math).latex)
    }

    @Test
    fun `una formula sin cerrar no se dibuja pero el texto no se pierde`() {
        // Durante el streaming la fórmula llega a medias: no hay que mostrar
        // media fórmula, pero tampoco borrar lo que el modelo puso.
        val out = nodes("\$\$x^2")
        assertTrue(out.none { it is MdNode.Math }, "una fórmula sin cerrar no se dibuja")
        assertTrue(out.any { it is MdNode.Paragraph }, "el texto tiene que quedar")
    }

    @Test
    fun `el texto que sigue a una formula no se lo come`() {
        val out = nodes("\$\$\nx\n\$\$\ndespues")
        assertTrue(out.any { it is MdNode.Math })
        val last = out.last()
        assertTrue(last is MdNode.Paragraph)
        assertEquals("despues", (last as MdNode.Paragraph).text)
    }

    @Test
    fun `un parrafo y una formula dan dos nodos`() {
        val out = nodes("texto\n\n\$\$x\$\$")
        assertEquals(2, out.size)
        assertTrue(out[0] is MdNode.Paragraph)
        assertTrue(out[1] is MdNode.Math)
    }
}
