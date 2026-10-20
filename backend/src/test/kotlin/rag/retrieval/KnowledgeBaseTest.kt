package rag.retrieval

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.config.RagConfig
import rag.test.runTest

/**
 * Tests de la base de conocimiento (RAG).
 *
 * La base indexa la documentación y responde qué fragmentos son relevantes.
 * Se prueba con carpetas temporales: nada de red, nada del disco del usuario.
 */
class KnowledgeBaseTest {

    /** Crea una carpeta con los archivos dados y devuelve su ruta absoluta. */
    private fun docs(vararg archivos: Pair<String, String>): Path {
        val dir = Files.createTempDirectory("kb-test-")
        archivos.forEach { (nombre, contenido) ->
            Files.createDirectories(dir.resolve(nombre).parent)
            Files.writeString(dir.resolve(nombre), contenido)
        }
        return dir.toAbsolutePath()
    }

    private fun kb(dir: Path) = KnowledgeBase(
        RagConfig(
            internalDocsDirs = listOf(dir.toString()),
            githubSources = emptyList(),
        ),
    )

    @Test
    fun `una base vacia no tiene fragmentos`() = runTest {
        val base = kb(docs())
        base.sync { }
        assertEquals(0, base.chunkCount)
    }

    @Test
    fun `los documentos markdown se indexan`() = runTest {
        val base = kb(docs("guia.md" to "# Guía\n\nEl motor usa Vulkan para renderizar."))
        base.sync { }
        assertTrue(base.chunkCount > 0, "el documento tiene que quedar indexado")
    }

    @Test
    fun `los archivos de otra extension se ignoran`() = runTest {
        val base = kb(
            docs(
                "guia.md" to "# Guía\n\ntexto",
                "imagen.png" to "esto no es un documento",
                "codigo.py" to "print('hola')",
            ),
        )
        base.sync { }
        assertEquals(1, base.chunkCount, "sólo el markdown entra al índice")
    }

    @Test
    fun `la busqueda encuentra el fragmento relevante`() = runTest {
        val base = kb(
            docs(
                "render.md" to "# Render\n\nEl motor usa Vulkan y OpenGL para dibujar.",
                "input.md" to "# Input\n\nEl teclado se lee con SDL.",
            ),
        )
        base.sync { }
        val hits = base.retrieve("vulkan", 3)
        assertEquals(1, hits.size)
        assertTrue("vulkan" in hits[0].text.lowercase())
    }

    @Test
    fun `la busqueda respeta el limite de resultados`() = runTest {
        val base = kb(
            docs(
                "a.md" to "# A\n\nmotor",
                "b.md" to "# B\n\nmotor",
                "c.md" to "# C\n\nmotor",
            ),
        )
        base.sync { }
        assertEquals(2, base.retrieve("motor", 2).size)
    }

    @Test
    fun `una busqueda sin resultados devuelve la lista vacia`() = runTest {
        val base = kb(docs("a.md" to "# A\n\ncontenido"))
        base.sync { }
        assertTrue(base.retrieve("teclado", 3).isEmpty())
    }

    @Test
    fun `el estado se va informingando`() = runTest {
        val estados = mutableListOf<String>()
        val base = kb(docs("a.md" to "# A\n\ncontenido"))
        base.sync { estados += it }
        assertTrue(estados.isNotEmpty(), "sync tiene que informar el progreso")
        assertTrue(estados.any { "Índice listo" in it }, "el último estado tiene que decir que terminó: $estados")
    }

    @Test
    fun `una carpeta que no existe no rompe la carga`() = runTest {
        val base = KnowledgeBase(
            RagConfig(
                internalDocsDirs = listOf("/no/existe/esta/carpeta"),
                githubSources = emptyList(),
            ),
        )
        base.sync { }
        assertEquals(0, base.chunkCount)
    }

    @Test
    fun `una segunda sincronizacion reemplaza el indice`() = runTest {
        val dir = docs("a.md" to "# A\n\ncontenido")
        val base = kb(dir)
        base.sync { }
        val primero = base.chunkCount
        Files.writeString(dir.resolve("b.md"), "# B\n\notro contenido")
        base.sync { }
        assertTrue(base.chunkCount > primero, "el segundo sync tiene que ver el archivo nuevo")
    }
}
