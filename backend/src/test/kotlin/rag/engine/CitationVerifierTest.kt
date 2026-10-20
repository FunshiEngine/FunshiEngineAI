package rag.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.retrieval.Chunk

/**
 * Tests del verificador de citas (F9).
 *
 * El verificador es la pieza que hace cumplir la regla central del proyecto
 * ("no inventar sin referencia"). Como es una función pura, sus casos son
 * cerrados: se prueban los tres caminos (respaldada, sin respaldo, fuente
 * inexistente) y los bordes (sin citas, cita repetida, texto vacío).
 */
class CitationVerifierTest {

    private fun chunk(text: String, path: String = "guia.md") = Chunk("Manual", path, "Sección", text)

    private val fuente = chunk(
        "El motor usa Vulkan y OpenGL para renderizar. Se compila con CMake.",
    )

    @Test
    fun `extrae los indices citados`() {
        assertEquals(listOf(1, 2, 3), CitationVerifier.citedIndexes("uno [1] dos [2] tres [3]"))
        assertEquals(listOf(7), CitationVerifier.citedIndexes("cita [7]"))
        assertTrue(CitationVerifier.citedIndexes("sin citas").isEmpty())
    }

    @Test
    fun `una cita literal esta respaldada`() {
        val report = CitationVerifier.verify("El motor usa Vulkan [1].", listOf(fuente))
        assertTrue(report.hasCitations)
        assertTrue(report.valid, "la cita es literal: ${report.checks}")
        assertEquals(1, report.verified)
        assertEquals(0, report.unsupported)
    }

    @Test
    fun `una parafrasis con suficientes palabras esta respaldada`() {
        val report = CitationVerifier.verify(
            "El motor renderiza con Vulkan y OpenGL [1].",
            listOf(fuente),
        )
        assertTrue(report.valid, "una paráfrasis honesta tiene que validar: ${report.checks}")
    }

    @Test
    fun `una cita que la fuente no dice queda sin respaldo`() {
        val report = CitationVerifier.verify(
            "El motor usa DirectX 12 para renderizar [1].",
            listOf(fuente),
        )
        assertFalse(report.valid)
        assertEquals(1, report.unsupported)
        assertEquals(0, report.verified)
        assertTrue(report.checks[0].reason.isNotBlank(), "el motivo tiene que explicar por qué")
    }

    @Test
    fun `una cita a una fuente inexistente se marca como desconocida`() {
        val report = CitationVerifier.verify("Algo [5].", listOf(fuente))
        assertFalse(report.valid)
        assertEquals(listOf(5), report.unknown)
        assertTrue(report.checks[0].reason.contains("No hay una fuente"))
    }

    @Test
    fun `una respuesta sin citas se marca como sin citas`() {
        val report = CitationVerifier.verify("Esta respuesta no cita nada.", listOf(fuente))
        assertFalse(report.hasCitations)
        assertFalse(report.valid)
        assertEquals(listOf(1), report.uncited, "la fuente recuperada quedó sin usar")
    }

    @Test
    fun `las fuentes no citadas se listan`() {
        val report = CitationVerifier.verify(
            "El motor usa Vulkan [1].",
            listOf(fuente, chunk("Otra cosa", "otro.md")),
        )
        assertEquals(listOf(2), report.uncited)
    }

    @Test
    fun `la misma fuente citada dos veces se verifica dos veces`() {
        val report = CitationVerifier.verify(
            "El motor usa Vulkan [1]. También usa OpenGL [1].",
            listOf(fuente),
        )
        assertEquals(2, report.checks.size)
        assertEquals(2, report.verified)
    }

    @Test
    fun `la verificacion usa el numero de la fuente correcta`() {
        val a = chunk("El motor usa Vulkan.", "a.md")
        val b = chunk("El teclado se lee con SDL.", "b.md")
        // La segunda cita tiene que comprobarse contra la fuente 2.
        val report = CitationVerifier.verify("El teclado se lee con SDL [2].", listOf(a, b))
        assertTrue(report.valid, "${report.checks}")
    }

    @Test
    fun `el fragmento verificado es el de la oracion con la cita`() {
        val report = CitationVerifier.verify(
            "Primera frase sin cita. El motor usa Vulkan [1]. Otra frase.",
            listOf(fuente),
        )
        assertEquals("El motor usa Vulkan", report.checks[0].snippet)
    }

    @Test
    fun `los acentos y la puntuacion no afectan la comparacion`() {
        val report = CitationVerifier.verify("El motor usa Vulkan, y compila con CMake [1].", listOf(fuente))
        assertTrue(report.valid, "${report.checks}")
    }

    @Test
    fun `una cita sin texto alrededor cuenta como respaldada`() {
        // "Sí [1]." no afirma nada: no hay nada que contradecir.
        val report = CitationVerifier.verify("Sí [1].", listOf(fuente))
        assertTrue(report.valid)
    }

    @Test
    fun `una fuente vacia no respalda nada`() {
        val report = CitationVerifier.verify("El motor usa Vulkan [1].", listOf(chunk("")))
        assertFalse(report.valid)
        assertTrue(report.checks[0].reason.contains("vacía"))
    }

    @Test
    fun `un numero de cita enorme no rompe nada`() {
        val report = CitationVerifier.verify("Algo [999].", listOf(fuente))
        assertFalse(report.valid)
        assertEquals(listOf(999), report.unknown)
    }

    @Test
    fun `la cobertura de palabras se mide sobre las significativas`() {
        // "el" y "de" no cuentan: el fragmento tiene contenido real.
        val norm = CitationVerifier.normalize("El motor de Vulkan")
        assertEquals("el motor de vulkan", norm)
        assertEquals(listOf("motor", "vulkan"), CitationVerifier.significantWords(norm))
    }

    @Test
    fun `el verificador no necesita fuentes para encontrar las citas`() {
        val report = CitationVerifier.verify("Cito [1] y [2].", emptyList())
        assertEquals(listOf(1, 2), report.unknown)
        assertFalse(report.valid)
    }

    @Test
    fun `una cita repetida con la misma fuente no la deja sin citar`() {
        val report = CitationVerifier.verify("A [1] B [1] C [1].", listOf(fuente))
        assertTrue(report.uncited.isEmpty(), "la fuente 1 sí se usó: ${report.uncited}")
        assertEquals(3, report.checks.size, "cada aparición se verifica")
    }

    /* ------------------------------------------------------------------
     * Términos clave: lo que separa una paráfrasis de una invención
     * ------------------------------------------------------------------ */

    @Test
    fun `un nombre propio que no esta en la fuente invalida la cita`() {
        // La fuente habla de Vulkan; el modelo inventa DirectX.
        val report = CitationVerifier.verify("El motor usa DirectX para renderizar [1].", listOf(fuente))
        assertFalse(report.valid, "DirectX no está en la fuente: ${report.checks}")
        assertTrue(report.checks[0].reason.contains("DirectX"), report.checks[0].reason)
    }

    @Test
    fun `un numero inventado invalida la cita`() {
        val report = CitationVerifier.verify("El motor usa Vulkan 12 para renderizar [1].", listOf(fuente))
        assertFalse(report.valid, "el 12 no está en la fuente: ${report.checks}")
        assertTrue(report.checks[0].reason.contains("12"), report.checks[0].reason)
    }

    @Test
    fun `un nombre propio que si esta en la fuente no invalida nada`() {
        val report = CitationVerifier.verify("El motor usa Vulkan y OpenGL [1].", listOf(fuente))
        assertTrue(report.valid, "${report.checks}")
    }

    @Test
    fun `la palabra inicial en mayuscula no cuenta como nombre propio`() {
        // "El" está capitalizado por empezar la frase: no es un nombre propio.
        val report = CitationVerifier.verify("El motor renderiza con vulkan [1].", listOf(fuente))
        assertTrue(report.valid, "${report.checks}")
    }

    @Test
    fun `un digito suelto no cuenta como numero clave`() {
        // "sección 3": una referencia interna no es un detalle inventado.
        val report = CitationVerifier.verify(
            "El motor renderiza con Vulkan, ver sección 3 [1].",
            listOf(fuente),
        )
        assertTrue(report.valid, "${report.checks}")
    }

    @Test
    fun `los terminos clave se detectan`() {
        val terminos = CitationVerifier.keyTerms("El motor usa Vulkan 1.0 con DirectX 12")
        val textos = terminos.map { it.text }
        // El normalizador parte el camel case ("DirectX" -> "direct x"): da igual,
        // la fuente se normaliza igual y la comparación sigue siendo justa.
        assertTrue("vulkan" in textos, "$textos")
        assertTrue("direct x" in textos, "$textos")
        assertTrue("1 0" in textos, "$textos")
        assertTrue("12" in textos, "$textos")
        assertTrue("el" !in textos, "las palabras funcionales no son términos clave: $textos")
    }

    @Test
    fun `los terminos clave distinguen los numeros`() {
        val terminos = CitationVerifier.keyTerms("DirectX 12 y 3 y 1.0")
        assertTrue(terminos.any { it.text == "12" && it.isNumber })
        assertTrue(terminos.any { it.text == "1 0" && it.isNumber })
        assertTrue(terminos.none { it.text == "3" }, "un solo dígito no cuenta")
    }

    @Test
    fun `la primera palabra de la frase no se toma como nombre propio`() {
        // "También" arranca la frase: capitalizada, pero no es un nombre propio.
        val terminos = CitationVerifier.keyTerms("También usa OpenGL")
        assertTrue(terminos.none { it.text == "tambien" }, "$terminos")
        assertTrue(terminos.any { it.text == "open gl" }, "$terminos")
    }

    @Test
    fun `una variacion de la misma palabra cuenta como respaldo`() {
        // La fuente dice "renderizar"; el modelo escribe "renderiza".
        val report = CitationVerifier.verify("El motor renderiza con Vulkan [1].", listOf(fuente))
        assertTrue(report.valid, "una variación de la misma palabra es la misma idea: ${report.checks}")
    }

    @Test
    fun `el plural cuenta como la misma palabra`() {
        val report = CitationVerifier.verify("Los motores usan Vulkan [1].", listOf(fuente))
        assertTrue(report.valid, "${report.checks}")
    }
}
