package app.ui

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * El scrollbar se mueve con el teclado: las flechas suben y bajan una línea,
 * Re Pág / Av Pág saltan una pantalla, e Inicio / Fin van a los extremos.
 *
 * La lógica está en una función pura ([scrollTargetForKey]) para poder
 * testearla sin Compose.
 */
class ScrollbarKeysTest {

    @Test
    fun `la flecha abajo baja una linea`() {
        val destino = scrollTargetForKey(
            key = Key.DirectionDown,
            offset = 100.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(148.0, destino)
    }

    @Test
    fun `la flecha arriba sube una linea`() {
        val destino = scrollTargetForKey(
            key = Key.DirectionUp,
            offset = 100.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(52.0, destino)
    }

    @Test
    fun `la pagina abajo salta una pantalla`() {
        val destino = scrollTargetForKey(
            key = Key.PageDown,
            offset = 100.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(900.0, destino)
    }

    @Test
    fun `la pagina arriba salta una pantalla hacia atras`() {
        val destino = scrollTargetForKey(
            key = Key.PageUp,
            offset = 1000.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(200.0, destino)
    }

    @Test
    fun `inicio va al principio`() {
        val destino = scrollTargetForKey(
            key = Key.MoveHome,
            offset = 1500.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(0.0, destino)
    }

    @Test
    fun `fin va al final`() {
        val destino = scrollTargetForKey(
            key = Key.MoveEnd,
            offset = 150.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(2000.0, destino)
    }

    @Test
    fun `el destino nunca se pasa del tope`() {
        val destino = scrollTargetForKey(
            key = Key.DirectionDown,
            offset = 1990.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(2000.0, destino)
    }

    @Test
    fun `el destino nunca es negativo`() {
        val destino = scrollTargetForKey(
            key = Key.DirectionUp,
            offset = 10.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertEquals(0.0, destino)
    }

    @Test
    fun `una tecla que no es de scroll no mueve nada`() {
        val destino = scrollTargetForKey(
            key = Key.Enter,
            offset = 100.0,
            viewportSize = 800.0,
            maxScroll = 2000.0,
        )
        assertNull(destino)
    }
}
