package rag.retrieval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del índice BM25 (RAG).
 *
 * El índice es el que decide qué documentos se le pasan al modelo. Si ordena
 * mal, el modelo responde con la documentación equivocada.
 */
class Bm25IndexTest {

    private fun chunk(text: String, path: String = "doc.md", heading: String = "") =
        Chunk("Manual", path, heading, text)

    @Test
    fun `la tokenizacion parte el camel case`() {
        assertTrue("llama" in Bm25Index.tokenize("llamaServer"))
        assertTrue("server" in Bm25Index.tokenize("llamaServer"))
    }

    @Test
    fun `la tokenizacion saca los acentos`() {
        val tokens = Bm25Index.tokenize("cación áéíóú")
        assertTrue("cacion" in tokens, "los acentos no pueden romper la búsqueda: $tokens")
    }

    @Test
    fun `la tokenizacion saca las palabras vacias`() {
        val tokens = Bm25Index.tokenize("el motor de la app")
        assertTrue("el" !in tokens)
        assertTrue("de" !in tokens)
        assertTrue("motor" in tokens)
    }

    @Test
    fun `la tokenizacion ignora las palabras de una letra`() {
        val tokens = Bm25Index.tokenize("a b cd")
        assertTrue("a" !in tokens)
        assertTrue("b" !in tokens)
        assertTrue("cd" in tokens)
    }

    @Test
    fun `la tokenizacion parte por lo que no es alfanumerico`() {
        val tokens = Bm25Index.tokenize("hola, mundo; otra vez")
        assertTrue("hola" in tokens && "mundo" in tokens && "otra" in tokens && "vez" in tokens)
    }

    @Test
    fun `un indice vacio no devuelve nada`() {
        val index = Bm25Index(emptyList())
        assertEquals(0, index.size)
        assertTrue(index.search("cualquier cosa", 5).isEmpty())
    }

    @Test
    fun `la busqueda devuelve el chunk que mas coincide`() {
        val index = Bm25Index(
            listOf(
                chunk("el motor renderiza en Vulkan y OpenGL"),
                chunk("la configuracion del motor esta en el archivo"),
                chunk("un tutorial de shaders"),
            ),
        )
        val resultados = index.search("shaders", 3)
        assertEquals(1, resultados.size, "sólo un chunk habla de shaders")
        assertTrue("shaders" in resultados[0].first.text)
    }

    @Test
    fun `la busqueda ordena por relevancia`() {
        val index = Bm25Index(
            listOf(
                chunk("motor"),
                chunk("motor motor motor render"),
                chunk("no dice nada"),
            ),
        )
        val resultados = index.search("motor", 3)
        assertEquals(2, resultados.size)
        assertTrue(
            "render" in resultados[0].first.text,
            "el chunk con más apariciones tiene que ir primero: ${resultados[0].first.text}",
        )
    }

    @Test
    fun `la busqueda respeta el limite de resultados`() {
        val index = Bm25Index(List(10) { chunk("motor $it") })
        assertEquals(3, index.search("motor", 3).size)
    }

    @Test
    fun `una busqueda sin coincidencias devuelve nada`() {
        val index = Bm25Index(listOf(chunk("el motor renderiza")))
        assertTrue(index.search("teclado", 5).isEmpty())
    }

    @Test
    fun `una consulta vacia no devuelve nada`() {
        val index = Bm25Index(listOf(chunk("el motor renderiza")))
        assertTrue(index.search("", 5).isEmpty())
        assertTrue(index.search("   ", 5).isEmpty())
    }

    @Test
    fun `el titulo y el path tambien cuentan para la busqueda`() {
        val index = Bm25Index(listOf(chunk("contenido cualquiera", "guia-de-shaders.md")))
        val resultados = index.search("shaders", 5)
        assertEquals(1, resultados.size, "el nombre del archivo tiene que ayudar a encontrarlo")
    }
}
