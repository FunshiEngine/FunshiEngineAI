package app.chat

import app.core.UiCitation
import app.core.UiMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests de la conversión entre el mensaje de la UI y el persistido.
 *
 * El chat se guarda en `config/chats.json`: lo que no viaja bien en esta
 * conversión se pierde al reiniciar la app (adjuntos, tarjetas de herramientas
 * y la verificación de citas).
 */
class ChatMessagesTest {

    private fun ui() = UiMessage(
        role = "assistant",
        text = "El motor usa Vulkan [1].",
        sources = listOf("Manual: guia.md"),
        toolCalls = listOf(app.core.UiToolCall("get_time", "{}", true, "10:00")),
        attachments = listOf(app.core.UiAttachment("a.pdf", "/tmp/a.pdf", 10, "application/pdf")),
        citation = UiCitation(verified = 1, unsupported = 0, unknown = 0),
    )

    @Test
    fun `el mensaje conserva todo al persistirse`() {
        val original = ui()
        val guardado = ChatMessages.toStored(original)
        assertEquals(original.text, guardado.text)
        assertEquals(original.sources, guardado.sources)
        assertEquals(1, guardado.toolCards.size)
        assertEquals(1, guardado.attachments.size)
    }

    @Test
    fun `la verificacion de citas viaja en los dos sentidos`() {
        val guardado = ChatMessages.toStored(ui())
        assertEquals(1, guardado.citation?.verified)
        val vuelto = ChatMessages.toUi(guardado)
        assertEquals(1, vuelto.citation?.verified)
        assertEquals(0, vuelto.citation?.unsupported)
    }

    @Test
    fun `un mensaje sin verificacion de citas no la inventa`() {
        val guardado = ChatMessages.toStored(UiMessage("assistant", "hola"))
        assertNull(guardado.citation)
        assertNull(ChatMessages.toUi(guardado).citation)
    }

    @Test
    fun `una cita sin respaldo se conserva`() {
        val guardado = ChatMessages.toStored(
            UiMessage("assistant", "texto", citation = UiCitation(verified = 2, unsupported = 1, unknown = 1)),
        )
        val vuelto = ChatMessages.toUi(guardado)
        assertEquals(2, vuelto.citation?.verified)
        assertEquals(1, vuelto.citation?.unsupported)
        assertEquals(1, vuelto.citation?.unknown)
        assertEquals(4, vuelto.citation?.total)
    }
}
