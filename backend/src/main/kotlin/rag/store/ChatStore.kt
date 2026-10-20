package rag.store

import java.nio.file.Files
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import rag.config.AppJson
import rag.config.AppPaths

/** Tarjeta de tool call persistida junto al mensaje. */
@Serializable
data class StoredToolCall(
    val name: String,
    val args: String = "",
    val ok: Boolean? = null,
    val result: String = "",
)

/**
 * Adjunto del mensaje: archivo que el modelo creó o descargó (se puede abrir
 * o guardar desde la tarjeta del chat).
 */
@Serializable
data class StoredAttachment(
    val name: String,
    val path: String,
    val sizeBytes: Long = 0L,
    val mime: String = "",
)

/** Mensaje persistido de un chat. */
@Serializable
data class StoredMessage(
    val role: String,
    val text: String,
    val sources: List<String> = emptyList(),
    /** Tarjetas de herramientas usadas en esta respuesta (opcional). */
    val toolCards: List<StoredToolCall> = emptyList(),
    /** Archivos generados o descargados en esta respuesta (opcional). */
    val attachments: List<StoredAttachment> = emptyList(),
)

/**
 * Nodo del árbol de chats: una carpeta (con hijos) o un chat (con mensajes).
 * El esquema es recursivo, así que las carpetas pueden anidarse.
 */
@Serializable
data class StoredNode(
    val id: String,
    val folder: Boolean = false,
    val name: String = "",
    val createdAt: Long = 0L,
    /** Sólo chats: sus mensajes. */
    val messages: List<StoredMessage> = emptyList(),
    /** Sólo carpetas: sus hijos (chats u otras carpetas). */
    val children: List<StoredNode> = emptyList(),
    /** Sólo chats: categorías de herramientas concedidas (FILES, WEB…). */
    val grantedCats: List<String> = emptyList(),
)

/**
 * Persiste el árbol de chats/carpetas en `config/chats.json`. Se guarda la
 * estructura completa tras cada cambio (crear, renombrar, mover, borrar,
 * enviar), así los chats sobreviven al reinicio de la app.
 *
 * Soporta migración desde el formato v1 plano (`List<StoredChat>` con `title`).
 */
object ChatStore {
    /** Formato v1 (lista plana): sólo se usa para migrar títulos existentes. */
    @Serializable
    private data class LegacyChat(
        val id: String,
        val title: String,
        val createdAt: Long = 0L,
        val messages: List<StoredMessage> = emptyList(),
    )

    private fun file() = AppPaths.resolve("config/chats.json")

    /**
     * Carga los hijos de la raíz. Primero intenta el formato v1 (plano): el JSON
     * v2 no tiene el campo "title", así que esa decodificación falla y seguimos
     * con el esquema de árbol.
     */
    fun load(): List<StoredNode> {
        val f = file()
        if (!Files.exists(f)) return emptyList()
        val text = try {
            Files.readString(f)
        } catch (_: Exception) {
            return emptyList()
        }
        try {
            val legacy = AppJson.decodeFromString<List<LegacyChat>>(text)
            return legacy.map {
                StoredNode(
                    id = it.id,
                    folder = false,
                    name = it.title.ifBlank { "Chat nuevo" },
                    createdAt = it.createdAt,
                    messages = it.messages,
                )
            }
        } catch (_: Exception) {
            // No era el formato v1: seguimos con el de árbol.
        }
        return try {
            AppJson.decodeFromString<List<StoredNode>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(nodes: List<StoredNode>) {
        val f = file()
        Files.createDirectories(f.parent)
        Files.writeString(f, AppJson.encodeToString(nodes))
    }
}
