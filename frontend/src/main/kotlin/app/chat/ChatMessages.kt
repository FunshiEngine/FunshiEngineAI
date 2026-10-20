package app.chat

import app.core.UiMessage
import app.core.UiToolCall
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Conversión entre el mensaje de la UI y el mensaje persistido en
 * `config/chats.json` (las tarjetas de herramientas viajan en los dos lados).
 */
internal object ChatMessages {

    /** Conversión mensaje persistido → UI (con tarjetas de tools). */
    fun toUi(m: StoredMessage): UiMessage = UiMessage(
        m.role, m.text, m.sources,
        m.toolCards.map { c -> UiToolCall(c.name, c.args, c.ok, c.result) },
    )

    /** Conversión mensaje UI → persistido. */
    fun toStored(m: UiMessage): StoredMessage = StoredMessage(
        m.role, m.text, m.sources,
        m.toolCalls.map { c -> StoredToolCall(c.name, c.args, c.ok, c.result) },
    )
}
