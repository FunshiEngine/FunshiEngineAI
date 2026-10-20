package app.core

import app.chat.subtreeCounts
import estructuras.arboles.ArbolEnlazado
import estructuras.posicion.Position
import rag.store.StoredMessage
import rag.store.StoredNode

/** Nodo del árbol de chats: una carpeta o un chat. */
sealed class ChatNode {
    abstract val id: String
    abstract var name: String

    /** Carpeta: contenedor de chats y otras carpetas (anidadas). */
    data class Folder(override val id: String, override var name: String) : ChatNode()

    /** Chat: hoja del árbol con sus mensajes. */
    class Chat(
        override val id: String,
        override var name: String,
        var messages: List<StoredMessage> = emptyList(),
        /** true mientras el nombre siga derivándose del primer mensaje del usuario. */
        var autoName: Boolean = true,
    ) : ChatNode()
}

/** Fila visible de la barra lateral (un nodo aplanado del árbol). */
data class ChatRow(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val expanded: Boolean,
    val depth: Int,
    val active: Boolean,
    val hasChildren: Boolean,
)

/**
 * Árbol de chats/carpetas apoyado en [ArbolEnlazado] (módulo `estructuras`).
 * Mantiene un índice id → posición para acceso directo a cada nodo.
 * La raíz es virtual (no se muestra en la GUI): sus hijos son los items de
 * nivel superior de la barra lateral.
 */
class ChatTree {
    private val tree = ArbolEnlazado<ChatNode>()
    private val pos = HashMap<String, Position<ChatNode>>()
    private val rootPos: Position<ChatNode>

    init {
        rootPos = tree.createRoot(ChatNode.Folder(ROOT_ID, ""))
        pos[ROOT_ID] = rootPos
    }

    fun nodeOf(id: String): ChatNode? = pos[id]?.getElement()

    fun nameOf(id: String): String = nodeOf(id)?.name ?: ""

    fun isFolder(id: String): Boolean = nodeOf(id) is ChatNode.Folder

    /** Padre de un nodo (null si es de nivel superior). */
    fun parentIdOf(id: String): String? =
        pos[id]?.let { tree.parent(it) }?.getElement()?.id?.takeIf { it != ROOT_ID }

    /** Hijos de un nodo (de la raíz si [id] es null). */
    fun childrenOf(id: String?): List<ChatNode> {
        val p = if (id == null) rootPos else pos[id] ?: return emptyList()
        return tree.children(p).map { it.getElement() }
    }

    /** Agrega un nodo hijo bajo [parentId] (null = raíz). */
    fun addChild(parentId: String?, node: ChatNode) {
        val p = if (parentId == null) rootPos else pos[parentId] ?: rootPos
        pos[node.id] = tree.addChild(p, node)
    }

    /**
     * Mueve [id] para quedar en la posición [index] bajo [newParentId] (null =
     * raíz). Lanza error si el destino es el propio nodo o un descendiente.
     */
    fun move(id: String, newParentId: String?, index: Int) {
        val p = pos[id] ?: return
        val np = if (newParentId == null) rootPos else pos[newParentId] ?: return
        tree.move(p, np, index)
    }

    /** ¿[id] es el propio [ancestor] o un descendiente suyo? */
    fun isSelfOrDescendant(id: String, ancestor: String): Boolean {
        var cur: String? = id
        while (cur != null) {
            if (cur == ancestor) return true
            cur = parentIdOf(cur)
        }
        return false
    }

    /** Borra el subárbol [id] y devuelve el nodo removido. */
    fun remove(id: String): ChatNode {
        val p = pos[id] ?: error("Nodo inexistente: $id")
        fun collect(x: Position<ChatNode>) {
            pos.remove(x.getElement().id)
            tree.children(x).forEach { collect(it) }
        }
        collect(p)
        return tree.remove(p)
    }

    /** (chats, carpetas) de todo el subárbol [id], incluido el propio nodo. */
    fun subtreeCounts(id: String): Pair<Int, Int> {
        var chats = 0
        var folders = 0
        fun walk(x: Position<ChatNode>) {
            val n = x.getElement()
            if (n is ChatNode.Chat) chats++ else folders++
            tree.children(x).forEach { walk(it) }
        }
        pos[id]?.let { walk(it) }
        return chats to folders
    }

    /** Aplana las filas visibles respetando qué carpetas están expandidas. */
    fun flatten(expanded: Set<String>, activeChatId: String): List<ChatRow> {
        val out = ArrayList<ChatRow>()
        fun walk(parent: Position<ChatNode>, depth: Int) {
            for (c in tree.children(parent)) {
                val n = c.getElement()
                val isFolder = n is ChatNode.Folder
                val isExpanded = isFolder && n.id in expanded
                out += ChatRow(
                    id = n.id,
                    name = n.name,
                    isFolder = isFolder,
                    expanded = isExpanded,
                    depth = depth,
                    active = n.id == activeChatId,
                    hasChildren = tree.children(c).isNotEmpty(),
                )
                if (isExpanded) walk(c, depth + 1)
            }
        }
        walk(rootPos, 0)
        return out
    }

    /** Primer chat en recorrido profundidad-primero (para activar tras borrar). */
    fun firstChatId(): String? {
        fun walk(x: Position<ChatNode>): String? {
            for (c in tree.children(x)) {
                val n = c.getElement()
                if (n is ChatNode.Chat) return n.id
                walk(c)?.let { return it }
            }
            return null
        }
        return walk(rootPos)
    }

    /** Serializa los hijos de la raíz al esquema persistido. */
    fun toStored(): List<StoredNode> {
        fun toNode(n: ChatNode, kids: List<Position<ChatNode>>): StoredNode = when (n) {
            is ChatNode.Chat -> StoredNode(n.id, folder = false, name = n.name, messages = n.messages)
            is ChatNode.Folder -> StoredNode(
                n.id,
                folder = true,
                name = n.name,
                children = kids.map { toNode(it.getElement(), tree.children(it)) },
            )
        }
        return tree.children(rootPos).map { toNode(it.getElement(), tree.children(it)) }
    }

    /** Reconstruye el árbol desde el esquema persistido. */
    fun loadFrom(nodes: List<StoredNode>) {
        fun add(parentId: String?, s: StoredNode) {
            val n: ChatNode = if (s.folder) {
                ChatNode.Folder(s.id, s.name.ifBlank { "Carpeta nueva" })
            } else {
                ChatNode.Chat(
                    s.id,
                    s.name.ifBlank { "Chat nuevo" },
                    s.messages,
                    autoName = s.name.isBlank() || s.name == "Chat nuevo",
                )
            }
            addChild(parentId, n)
            if (s.folder) s.children.forEach { add(s.id, it) }
        }
        nodes.forEach { add(null, it) }
    }

    companion object {
        const val ROOT_ID = "__raiz__"
    }
}
