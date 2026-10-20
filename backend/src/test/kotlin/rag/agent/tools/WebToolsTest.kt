package rag.agent.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests de las utilidades de la tool web (F4).
 *
 * Son funciones puras sobre HTML: no tocan la red, así que se pueden testear
 * de forma determinista. Son las que deciden qué texto le llega al modelo, así
 * que un error acá se traduce en "el modelo leyó cualquier cosa".
 */
class WebToolsTest {

    @Test
    fun `decodifica las entidades nombradas`() {
        assertEquals("a & b", decodeEntities("a &amp; b"))
        assertEquals("<etiqueta>", decodeEntities("&lt;etiqueta&gt;"))
        assertEquals("\"citado\"", decodeEntities("&quot;citado&quot;"))
    }

    @Test
    fun `decodifica las entidades numericas`() {
        assertEquals("ñ", decodeEntities("&#241;"))
        assertEquals("ñ", decodeEntities("&#xF1;"))
        assertEquals("€", decodeEntities("&#8364;"))
    }

    @Test
    fun `decodifica en una sola pasada`() {
        // `&amp;lt;` es el texto "&lt;": decodificar dos veces daría "<".
        assertEquals("&lt;", decodeEntities("&amp;lt;"))
    }

    @Test
    fun `las entidades distingue mayusculas`() {
        assertEquals("Á", decodeEntities("&Aacute;"))
        assertEquals("á", decodeEntities("&aacute;"))
    }

    @Test
    fun `una entidad desconocida se deja como estaba`() {
        assertEquals("&noexiste;", decodeEntities("&noexiste;"))
    }

    @Test
    fun `un codigo numerico invalido se deja como estaba`() {
        assertEquals("&#xZZ;", decodeEntities("&#xZZ;"))
    }

    @Test
    fun `el espacio duro se vuelve un espacio normal`() {
        assertEquals("a b", decodeEntities("a&nbsp;b"))
    }

    @Test
    fun `quita los scripts y los estilos`() {
        val html = "<html><head><style>b { color: red }</style></head><body><script>alert(1)</script><p>texto</p></body></html>"
        val limpio = stripNoise(html)
        assertFalse("alert(1)" in limpio, "el script tiene que desaparecer")
        assertFalse("color: red" in limpio, "el estilo tiene que desaparecer")
        assertTrue("texto" in limpio)
    }

    @Test
    fun `quita los comentarios`() {
        val limpio = stripNoise("<p>antes<!-- comentario -->despues</p>")
        assertFalse("comentario" in limpio)
        assertTrue("antes" in limpio && "despues" in limpio)
    }

    @Test
    fun `saca el titulo de la pagina`() {
        assertEquals("Mi pagina", htmlTitle("<html><head><title>Mi pagina</title></head></html>"))
        assertEquals("", htmlTitle("<html><body>sin titulo</body></html>"))
    }

    @Test
    fun `convierte el html en texto legible`() {
        val texto = htmlToText("<h1>Titulo</h1><p>Primer parrafo.</p><ul><li>uno</li><li>dos</li></ul>")
        assertTrue("Titulo" in texto)
        assertTrue("Primer parrafo." in texto)
        assertTrue("uno" in texto && "dos" in texto)
        assertFalse("<" in texto, "no puede quedar HTML en el texto")
        assertFalse(">" in texto, "no puede quedar HTML en el texto")
    }

    @Test
    fun `el texto conserva los saltos entre bloques`() {
        val texto = htmlToText("<p>uno</p><p>dos</p>")
        assertTrue("uno" in texto && "dos" in texto)
        assertTrue(texto.contains("\n"), "los bloques van separados por salto de línea")
    }

    @Test
    fun `filtra los anuncios del buscador`() {
        assertTrue(isAd("https://duckduckgo.com/y.js?ad_provider=x"), "las rutas de anuncios se descartan")
        assertFalse(isAd("https://duckduckgo.com/?q=kotlin"), "una búsqueda normal no es un anuncio")
    }

    @Test
    fun `deduce el charset del content type`() {
        assertEquals("UTF-8", charsetOf("text/html; charset=UTF-8").name())
        assertEquals("ISO-8859-1", charsetOf("text/html; charset=iso-8859-1").name())
    }

    @Test
    fun `un content type sin charset cae en utf-8`() {
        assertEquals("UTF-8", charsetOf("text/html").name())
        assertEquals("UTF-8", charsetOf("").name())
    }
}
