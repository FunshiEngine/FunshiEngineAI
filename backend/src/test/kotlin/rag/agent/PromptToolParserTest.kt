package rag.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del parser del protocolo por prompt (F1, modo LOCAL).
 *
 * El modelo local no tiene tool calls nativos: escribe un bloque
 * ```tool_call ... ``` y el loop lo recorta del stream. El parser es
 * línea-buffered y tiene que ser tolerante con el streaming partido.
 */
class PromptToolParserTest {

    @Test
    fun `el texto normal pasa sin cambios`() {
        val parser = PromptToolParser()
        val out = parser.feed("Hola, ¿cómo estás?\n")
        assertEquals("Hola, ¿cómo estás?\n", out)
        assertTrue(parser.drain().isEmpty())
    }

    @Test
    fun `recorta el bloque de tool call`() {
        val parser = PromptToolParser()
        val out = parser.feed("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```\n")
        assertEquals("", out, "el bloque no es texto visible")
        val blocks = parser.drain()
        assertEquals(1, blocks.size)
        assertEquals("{\"tool\":\"get_time\",\"arguments\":{}}", blocks[0])
    }

    @Test
    fun `el texto de antes y de después del bloque se conserva`() {
        val parser = PromptToolParser()
        parser.feed("Antes.\n")
        parser.feed("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```\n")
        val out = parser.feed("Después.\n")
        assertEquals("Después.\n", out)
        assertEquals(1, parser.drain().size)
    }

    @Test
    fun `el drain vacia los bloques ya leidos`() {
        val parser = PromptToolParser()
        parser.feed("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```\n")
        assertEquals(1, parser.drain().size)
        assertTrue(parser.drain().isEmpty(), "el segundo drain no debe repetir el bloque")
    }

    @Test
    fun `aguanta el stream partido en cualquier punto`() {
        val completo = "```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```\n"
        // Se parte en cada posición: el resultado debe ser el mismo.
        for (corte in 1 until completo.length) {
            val parser = PromptToolParser()
            parser.feed(completo.substring(0, corte))
            parser.feed(completo.substring(corte))
            val blocks = parser.drain()
            assertEquals(1, blocks.size, "se rompió el bloque con el corte en $corte")
            assertEquals(
                "{\"tool\":\"get_time\",\"arguments\":{}}",
                blocks[0],
                "el contenido del bloque cambió con el corte en $corte",
            )
        }
    }

    @Test
    fun `un bloque sin cerrar se descarta en silencio`() {
        val parser = PromptToolParser()
        val out = parser.feed("```tool_call\n{\"tool\":\"get_time\"")
        assertEquals("", out)
        assertTrue(parser.drain().isEmpty(), "un bloque incompleto no se ejecuta")
    }

    @Test
    fun `la apertura tiene que ser exacta`() {
        // ```tool_calls o ```tool_call con texto no abren un bloque.
        val parser = PromptToolParser()
        val out = parser.feed("```tool_calls\nno soy un bloque\n```\n")
        assertTrue(out.contains("no soy un bloque"))
        assertTrue(parser.drain().isEmpty())
    }

    @Test
    fun `el cierre tiene que estar en su propia linea`() {
        val parser = PromptToolParser()
        parser.feed("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}```\n")
        assertTrue(parser.drain().isEmpty(), "el cierre pegado al JSON no cierra el bloque")
    }

    @Test
    fun `el texto sin newline se suelta pronto salvo que pueda ser la apertura`() {
        // No puede ser la apertura: se suelta de inmediato (streaming fluido).
        val parser = PromptToolParser()
        assertEquals("texto sin salto", parser.feed("texto sin salto"))
        assertEquals("", parser.flush())

        // "``" sí podría ser la apertura: se retiene hasta el newline.
        val parser2 = PromptToolParser()
        assertEquals("", parser2.feed("``"))
        assertEquals("``", parser2.flush())
    }
}
