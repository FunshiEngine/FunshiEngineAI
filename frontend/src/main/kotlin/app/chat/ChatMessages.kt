package app.chat

import app.core.UiAttachment
import app.core.UiMessage
import app.core.UiToolCall
import rag.store.StoredAttachment
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Conversión entre el mensaje de la UI y el mensaje persistido en
 * `config/chats.json` (las tarjetas de herramientas y los adjuntos viajan en
 * los dos lados).
 */
internal object ChatMessages {

    /** Conversión mensaje persistido → UI (con tarjetas de tools y adjuntos). */
    fun toUi(m: StoredMessage): UiMessage = UiMessage(
        m.role, m.text, m.sources,
        m.toolCards.map { c -> UiToolCall(c.name, c.args, c.ok, c.result) },
        m.attachments.map { a -> UiAttachment(a.name, a.path, a.sizeBytes, a.mime) },
    )

    /** Conversión mensaje UI → persistido. */
    fun toStored(m: UiMessage): StoredMessage = StoredMessage(
        m.role, m.text, m.sources,
        m.toolCalls.map { c -> StoredToolCall(c.name, c.args, c.ok, c.result) },
        m.attachments.map { a -> StoredAttachment(a.name, a.path, a.sizeBytes, a.mime) },
    )
}
