package app.chat

import app.core.UiAttachment
import app.core.UiCitation
import app.core.UiMessage
import app.core.UiToolCall
import rag.store.StoredAttachment
import rag.store.StoredCitation
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Conversión entre el mensaje de la UI y el mensaje persistido en
 * `config/chats.json` (las tarjetas de herramientas, los adjuntos y la
 * verificación de citas viajan en los dos lados).
 */
internal object ChatMessages {

    /** Conversión mensaje persistido → UI (con tarjetas de tools y adjuntos). */
    fun toUi(m: StoredMessage): UiMessage = UiMessage(
        m.role, m.text, m.sources,
        m.toolCards.map { c -> UiToolCall(c.name, c.args, c.ok, c.result) },
        m.attachments.map { a -> UiAttachment(a.name, a.path, a.sizeBytes, a.mime) },
        m.citation?.let { c -> UiCitation(c.verified, c.unsupported, c.unknown) },
    )

    /** Conversión mensaje UI → persistido. */
    fun toStored(m: UiMessage): StoredMessage = StoredMessage(
        m.role, m.text, m.sources,
        m.toolCalls.map { c -> StoredToolCall(c.name, c.args, c.ok, c.result) },
        m.attachments.map { a -> StoredAttachment(a.name, a.path, a.sizeBytes, a.mime) },
        m.citation?.let { c -> StoredCitation(c.verified, c.unsupported, c.unknown) },
    )
}
