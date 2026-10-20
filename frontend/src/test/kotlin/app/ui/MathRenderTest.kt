package app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del render de matemática (F5a).
 *
 * La matemática del chat viene del modelo como texto LaTeX. Dos cosas tienen
 * que ser ciertas: que el texto se parte bien en tramos (para no comerse texto
 * que no es fórmula) y que el render produzca una imagen con tinta.
 */
class MathRenderTest {

    private fun formulas(src: String): List<MathSegment.Formula> =
        splitMath(src).filterIsInstance<MathSegment.Formula>()

    private fun plainText(src: String): String =
        splitMath(src).filterIsInstance<MathSegment.Plain>().joinToString("") { it.text }

    @Test
    fun `un texto sin matematicas queda entero`() {
        val segs = splitMath("hola mundo")
        assertEquals(1, segs.size)
        assertEquals("hola mundo", plainText("hola mundo"))
    }

    @Test
    fun `la formula centrada se reconoce`() {
        val f = formulas("\$\$x^2\$\$")
        assertEquals(1, f.size)
        assertTrue(f[0].display, "los dobles signo dollar son fórmula centrada")
        assertEquals("x^2", f[0].latex)
    }

    @Test
    fun `la formula en linea se reconoce`() {
        val f = formulas("ver \$x^2\$ aqui")
        assertEquals(1, f.size)
        assertTrue(!f[0].display, "el signo dollar simple es fórmula en línea")
        assertEquals("x^2", f[0].latex)
    }

    @Test
    fun `el texto alrededor de la formula se conserva`() {
        assertEquals("ver  aqui", plainText("ver \$x^2\$ aqui"))
    }

    @Test
    fun `varias formulas en el mismo texto`() {
        val f = formulas("a \$x\$ b \$y\$ c")
        assertEquals(2, f.size)
        assertEquals("x", f[0].latex)
        assertEquals("y", f[1].latex)
    }

    @Test
    fun `una formula sin cerrar queda como texto`() {
        // Importante con streaming: el modelo escribe "$x" y después el cierre.
        assertEquals(0, formulas("\$x + 1").size)
        assertEquals("\$x + 1", plainText("\$x + 1"))
    }

    @Test
    fun `un precio no es una formula`() {
        val text = "cuesta \$5 y antes \$10"
        assertEquals(0, formulas(text).size, "los precios no son matemática")
        assertEquals(text, plainText(text))
    }

    @Test
    fun `el codigo en linea no es matematica`() {
        val text = "usá `\$x\$` así"
        assertEquals(0, formulas(text).size)
        assertEquals(text, plainText(text))
    }

    @Test
    fun `una formula multilinea centrada se toma entera`() {
        val f = formulas("\$\$\n\\frac{a}{b}\n\$\$")
        assertEquals(1, f.size)
        assertTrue(f[0].display)
        assertTrue("\\frac{a}{b}" in f[0].latex)
    }

    @Test
    fun `cada formula tiene su propio id`() {
        val f = formulas("\$a\$ y \$b\$")
        assertEquals(2, f.size)
        assertTrue(f[0].id != f[1].id, "los ids tienen que ser distintos para no pisarse")
    }

    @Test
    fun `el render de una formula valida produce tinta`() {
        val img = MathRender.render("x^2 + y^2 = z^2", display = true)
        assertNotNull(img, "una fórmula válida tiene que renderizar")
        val ink = countInk(img!!)
        assertTrue(ink > 0, "la imagen no puede estar vacía")
    }

    @Test
    fun `el render en linea tambien produce tinta`() {
        val img = MathRender.render("\\alpha", display = false)
        assertNotNull(img)
        assertTrue(countInk(img!!) > 0)
    }

    @Test
    fun `una formula vacia no renderiza`() {
        assertNull(MathRender.render("   ", display = true))
    }

    @Test
    fun `una formula invalida no rompe la app`() {
        // No tiene que dar excepción: el chat la muestra como texto.
        // JLaTeXMath es tolerante con algunas formulas raras, asi que se prueban
        // varias: ninguna puede lanzar, y alguna tiene que devolver null (ese
        // es el camino que activa el fallback a texto plano).
        val candidatas = listOf("\\frac{", "\\unknowncmd", "\\begin{desconocido}", "}{", "\\sqrt")
        var nulls = 0
        for (c in candidatas) {
            val img = MathRender.render(c, display = true)   // no debe lanzar
            if (img == null) {
                nulls++
            } else {
                assertTrue(img.width > 0 && img.height > 0, "si renderiza, tiene que tener tamaño: $c")
            }
        }
        assertTrue(nulls > 0, "alguna fórmula inválida tiene que devolver null para caer al texto plano")
    }

    @Test
    fun `el render usa el color pedido`() {
        val color = java.awt.Color(0x22, 0x66, 0xCC)
        val img = MathRender.render("x", display = false, color = color)
        assertNotNull(img)
        var encontrado = false
        outer@ for (y in 0 until img!!.height) {
            for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                if ((argb ushr 24) and 0xFF != 0) {
                    assertEquals(color.red, (argb ushr 16) and 0xFF, "el rojo del trazo debe ser el pedido")
                    assertEquals(color.green, (argb ushr 8) and 0xFF)
                    assertEquals(color.blue, argb and 0xFF)
                    encontrado = true
                    break@outer
                }
            }
        }
        assertTrue(encontrado, "no hay ningún trazo en la imagen")
    }

    @Test
    fun `el fondo del render es transparente`() {
        val img = MathRender.render("x", display = false)
        assertNotNull(img)
        var transparentes = 0
        for (y in 0 until img!!.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) ushr 24) and 0xFF == 0) transparentes++
            }
        }
        assertTrue(transparentes > 0, "el fondo tiene que ser transparente")
    }

    private fun countInk(img: java.awt.image.BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) ushr 24) and 0xFF != 0) n++
            }
        }
        return n
    }
}
