package app.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.core.ChatNode
import app.core.ChatRow
import app.core.ChatTree
import app.core.Screen
import app.core.UiDocsFile
import app.core.UiMessage
import app.core.UiProgress
import app.core.UiToolCall
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import llm.api.OpenAiCompatibleGateway
import llm.catalog.HfGgufFile
import llm.catalog.HfRepoHit
import llm.catalog.HuggingFaceDownloader
import llm.catalog.ModelEntry
import llm.catalog.ModelManager
import llm.catalog.ModelOpProgress
import llm.config.ApiPreset
import llm.config.ChatMessage
import llm.config.LlmMode
import llm.config.ModelPreset
import llm.error.LlmException
import llm.service.LlmService
import rag.agent.AgentEvent
import rag.agent.AgentLoop
import rag.agent.ToolContext
import rag.agent.ToolRegistry
import rag.agent.tools.GetTimeTool
import rag.config.AppConfig
import rag.config.AppPaths
import rag.config.ColorProfile
import rag.engine.RagEngine
import rag.retrieval.KnowledgeBase
import rag.store.ChatStore
import rag.store.StoredMessage
import rag.store.StoredToolCall

/**
 * Biblioteca de chats: árbol de carpetas y persistencia.
 *
 * Operaciones sobre el árbol de chats (crear, renombrar, mover, borrar) y su
 * guardado en `config/chats.json`. Son funciones de extensión sobre el
 * ViewModel, que sigue siendo el dueño del estado.
 */
// ---- árbol de chats/carpetas ----

/** Recalcula las filas visibles del sidebar a partir del árbol. */
internal fun ChatViewModel.refreshRows() {
    chatRows = tree.flatten(expandedIds, activeChatId)
}

/** Guarda el árbol completo en disco. */
internal fun ChatViewModel.persistTree() {
    ChatStore.save(tree.toStored())
}

/** Guarda los mensajes del chat activo en el árbol y en disco. */
internal fun ChatViewModel.persistActiveChat() {
    val chat = tree.nodeOf(activeChatId) as? ChatNode.Chat ?: return
    val stored = messages
        .filter { it.text.isNotBlank() && it.text != "…" }
        .map { ChatMessages.toStored(it) }
    // El nombre sigue derivándose del primer mensaje sólo si el usuario
    // nunca lo renombró a mano.
    if (chat.autoName) {
        stored.firstOrNull { it.role == "user" }
            ?.text?.lineSequence()?.firstOrNull()?.take(40)?.ifBlank { null }
            ?.let { chat.name = it }
    }
    chat.messages = stored
    persistTree()
    refreshRows()
}

/** Crea un chat nuevo bajo [parentId] (null = raíz) y lo deja activo. */
internal fun ChatViewModel.newChat(parentId: String? = null) {
    persistActiveChat()
    val id = newChatId()
    tree.addChild(parentId, ChatNode.Chat(id, "Chat nuevo"))
    activeChatId = id
    messages = emptyList()
    error = ""
    testResult = ""
    persistTree()
    refreshRows()
}

/** Crea una carpeta nueva bajo [parentId] (null = raíz) y la deja expandida. */
internal fun ChatViewModel.newFolder(parentId: String? = null) {
    persistActiveChat()
    val id = newChatId()
    tree.addChild(parentId, ChatNode.Folder(id, "Carpeta nueva"))
    expandedIds = expandedIds + id
    persistTree()
    refreshRows()
}

/** Alterna el expandido/colapsado de la carpeta [id]. */
internal fun ChatViewModel.toggleFolder(id: String) {
    expandedIds = if (id in expandedIds) expandedIds - id else expandedIds + id
    refreshRows()
}

/**
 * Renombra un chat o carpeta. El chat renombrado deja de auto-titularse
 * por el primer mensaje.
 */
internal fun ChatViewModel.renameNode(id: String, newName: String) {
    val n = newName.trim()
    if (n.isEmpty()) return
    val node = tree.nodeOf(id) ?: return
    node.name = n
    if (node is ChatNode.Chat) node.autoName = false
    persistTree()
    refreshRows()
}

/** ¿El nodo [id] (o su subárbol) contiene al chat activo? */
internal fun ChatViewModel.containsActive(id: String): Boolean =
    activeChatId == id || tree.isSelfOrDescendant(activeChatId, id)

/** Mueve el nodo [id] dentro del árbol (drag & drop). */
internal fun ChatViewModel.moveNode(id: String, newParentId: String?, index: Int) {
    if (id == newParentId || (newParentId != null && tree.isSelfOrDescendant(newParentId, id))) {
        error = "No podés soltar una carpeta dentro de sí misma."
        return
    }
    tree.move(id, newParentId, index)
    persistTree()
    refreshRows()
}

/**
 * Suelta el nodo [draggedId] sobre [targetId]: si el destino es una carpeta
 * entra al FINAL de esa carpeta (y se expande); si es un chat, queda justo
 * después de él en el mismo nivel.
 */
internal fun ChatViewModel.dropOnto(draggedId: String, targetId: String, intoFolder: Boolean) {
    if (busy || draggedId == targetId) return
    if (intoFolder) {
        moveNode(draggedId, targetId, Int.MAX_VALUE)
        expandedIds = expandedIds + targetId
        refreshRows()
    } else {
        val parentId = tree.parentIdOf(targetId)
        val siblings = tree.childrenOf(parentId)
        val index = siblings.indexOfFirst { it.id == targetId } + 1
        moveNode(draggedId, parentId, index)
    }
}

/** (chats, carpetas) del subárbol [id], para el diálogo de confirmación. */
internal fun ChatViewModel.subtreeCounts(id: String): Pair<Int, Int> = tree.subtreeCounts(id)

internal fun ChatViewModel.nodeIsFolder(id: String): Boolean = tree.isFolder(id)

internal fun ChatViewModel.nodeName(id: String): String = tree.nameOf(id)

/**
 * Borra el subárbol [id]. Si el chat activo estaba ahí, salta a otro.
 * Mientras la IA responde SÓLO se corta la generación si el subárbol
 * contiene al chat activo: borrar un chat ajeno no interrumpe la
 * respuesta (antes el borrado entero fallaba en silencio con busy=true).
 */
internal fun ChatViewModel.deleteNode(id: String) {
    val wasInside = containsActive(id)
    if (busy && wasInside) {
        // Corta la generación del chat que se va a borrar: cancel() limpia
        // la cola y el finally del job pone busy=false solo.
        cancel()
    }
    tree.remove(id)
    // Garantiza que siempre quede al menos un chat visible.
    if (tree.firstChatId() == null) {
        tree.addChild(null, ChatNode.Chat(newChatId(), "Chat nuevo"))
    }
    if (wasInside) {
        activeChatId = tree.firstChatId() ?: ""
        messages = (tree.nodeOf(activeChatId) as? ChatNode.Chat)
            ?.messages?.map { ChatMessages.toUi(it) } ?: emptyList()
        error = ""
        testResult = ""
    }
    persistTree()
    refreshRows()
}

/** Cambia al chat con el id dado (guarda el actual y carga el destino). */
internal fun ChatViewModel.switchChat(id: String) {
    if (id == activeChatId || busy) return
    val target = tree.nodeOf(id) as? ChatNode.Chat ?: return
    persistActiveChat()
    activeChatId = id
    messages = target.messages.map { ChatMessages.toUi(it) }
    error = ""
    testResult = ""
    refreshRows()
}

/** Cambia el modo del chat (GENERAL u DOCS) y lo persiste. */
internal fun ChatViewModel.changeChatMode(mode: String) {
    val m = if (mode == "DOCS") "DOCS" else "GENERAL"
    chatMode = m
    config = config.copy(llm = config.llm.copy(chatMode = m)).save()
}
