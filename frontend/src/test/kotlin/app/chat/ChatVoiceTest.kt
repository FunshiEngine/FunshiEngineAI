package app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests de la preparación del texto para la voz: lo que el modelo escribe es
 * markdown y hay que quitarlo antes de leerlo en voz alta.
 */
class ChatVoiceTest {

    @Test
    fun `el markdown de negrita se quita`() {
        assertEquals("hola", plainForSpeech("**hola**"))
    }

    @Test
    fun `los enlaces se leen por su texto`() {
        assertEquals("la documentación", plainForSpeech("[la documentación](https://ejemplo.com)"))
    }

    @Test
    fun `el codigo en linea se lee sin las comillas invertidas`() {
        assertEquals("print(1)", plainForSpeech("`print(1)`"))
    }

    @Test
    fun `un bloque de codigo se reemplaza`() {
        val texto = plainForSpeech("Mirá esto:\n```kotlin\nval x = 1\n```\nlisto")
        assertFalse(texto.contains("val x = 1"), "el código no se lee: $texto")
        assertTrue(texto.contains("código"))
    }

    @Test
    fun `los titulos pierden las almohadillas`() {
        assertEquals("Resumen", plainForSpeech("## Resumen"))
    }

    @Test
    fun `las listas pierden las vinetas`() {
        assertEquals("uno dos", plainForSpeech("- uno\n- dos"))
    }

    @Test
    fun `los espacios repetidos se colapsan`() {
        assertEquals("a b", plainForSpeech("a\n\n  b"))
    }
}
