package rag.retrieval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del troceador de documentos (RAG).
 *
 * El chunking decide qué se le muestra al modelo como contexto. Si parte mal
 * un documento, el modelo recibe media sección y responde sobre eso.
 */
class ChunkerTest {

    private fun doc(text: String, path: String = "docs/guia.md") = Doc("Manual", path, text)

    @Test
    fun `un documento vacio no da chunks`() {
        assertTrue(Chunker.split(doc(""), 100, 0).isEmpty())
        assertTrue(Chunker.split(doc("   \n  \n"), 100, 0).isEmpty())
    }

    @Test
    fun `un documento chico es un solo chunk`() {
        val chunks = Chunker.split(doc("hola mundo"), 1000, 0)
        assertEquals(1, chunks.size)
        assertEquals("hola mundo", chunks[0].text)
    }

    @Test
    fun `cada encabezado abre un chunk`() {
        val texto = """
            # Título uno
            contenido uno

            ## Título dos
            contenido dos
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), 1000, 0)
        assertEquals(2, chunks.size)
        assertEquals("Título uno", chunks[0].heading)
        assertEquals("Título dos", chunks[1].heading)
        assertTrue("contenido uno" in chunks[0].text)
        assertTrue("contenido dos" in chunks[1].text)
    }

    @Test
    fun `un texto sin encabezado usa el nombre del archivo`() {
        val chunks = Chunker.split(doc("contenido", "docs/introduccion.md"), 1000, 0)
        assertEquals(1, chunks.size)
        assertEquals("introduccion.md", chunks[0].heading)
    }

    @Test
    fun `un chunk grande se parte por tamaño`() {
        val largo = (1..200).joinToString("\n") { "linea $it de relleno" }
        val chunks = Chunker.split(doc(largo), 200, 0)
        assertTrue(chunks.size > 1, "un texto largo tiene que partirse")
        chunks.forEach {
            assertTrue(it.text.length <= 260, "ningún chunk puede pasar el tamaño pedido: ${it.text.length}")
        }
    }

    @Test
    fun `el solapamiento repite contenido entre chunks`() {
        val largo = (1..100).joinToString("\n") { "linea $it" }
        val chunks = Chunker.split(doc(largo), 200, 40)
        assertTrue(chunks.size > 1)
        // Con solapamiento hay texto que aparece en dos chunks seguidos.
        val primero = chunks[0].text
        val segundo = chunks[1].text
        val comunes = primero.lines().filter { it.isNotBlank() && it in segundo }
        assertTrue(comunes.isNotEmpty(), "con solapamiento hay que repetir contenido")
    }

    @Test
    fun `un heading dentro de un bloque de codigo no corta el chunk`() {
        val texto = """
            # Real

            ```python
            # esto es un comentario, no un encabezado
            print("hola")
            ```
        """.trimIndent()
        val chunks = Chunker.split(doc(texto), 1000, 0)
        assertEquals(1, chunks.size, "el # de un fence no puede abrir un chunk nuevo")
        assertTrue("print" in chunks[0].text)
    }

    @Test
    fun `el chunk guarda de donde vino`() {
        val chunks = Chunker.split(doc("texto", "docs/guia.md"), 1000, 0)
        assertEquals("Manual", chunks[0].source)
        assertEquals("docs/guia.md", chunks[0].path)
        assertEquals("Manual: docs/guia.md", chunks[0].label)
    }

    @Test
    fun `un encabezado vacio no rompe nada`() {
        val chunks = Chunker.split(doc("#\ncontenido"), 1000, 0)
        assertEquals(1, chunks.size)
    }
}
