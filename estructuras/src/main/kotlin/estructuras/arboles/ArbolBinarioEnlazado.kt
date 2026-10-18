package estructuras.arboles

import estructuras.nodos.BNodo
import estructuras.posicion.Position

/**
 * Árbol binario enlazado con nodos [BNodo]. Cada nodo conoce a su padre y a
 * sus (a lo sumo dos) hijos.
 *
 * Soporta crear la raíz, agregar hijo izquierdo/derecho y remover un subárbol
 * completo. Réplica de `ArbolBinarioEnlazado` de FunshiEngineOpenGL.
 */
class ArbolBinarioEnlazado<E> : Tree<E> {
    private var raiz: BNodo<E>? = null
    private var count = 0

    override fun isEmpty(): Boolean = raiz == null

    override fun size(): Int = count

    override fun root(): Position<E>? = raiz

    /** Crea la raíz del árbol (sólo si aún no existe) y devuelve su posición. */
    fun createRoot(e: E): Position<E> {
        check(raiz == null) { "El árbol ya tiene raíz." }
        val nodo = BNodo(e)
        raiz = nodo
        count = 1
        return nodo
    }

    override fun parent(p: Position<E>): Position<E>? = asNode(p).dad

    fun left(p: Position<E>): Position<E>? = asNode(p).left

    fun right(p: Position<E>): Position<E>? = asNode(p).right

    override fun children(p: Position<E>): List<Position<E>> {
        val n = asNode(p)
        return listOfNotNull(n.left, n.right)
    }

    /** Agrega [e] como hijo izquierdo de [p] (debe estar libre). */
    fun addLeft(p: Position<E>, e: E): Position<E> {
        val node = asNode(p)
        check(node.left == null) { "El hijo izquierdo ya existe." }
        val nuevo = BNodo(e, node)
        node.left = nuevo
        count++
        return nuevo
    }

    /** Agrega [e] como hijo derecho de [p] (debe estar libre). */
    fun addRight(p: Position<E>, e: E): Position<E> {
        val node = asNode(p)
        check(node.right == null) { "El hijo derecho ya existe." }
        val nuevo = BNodo(e, node)
        node.right = nuevo
        count++
        return nuevo
    }

    override fun isRoot(p: Position<E>): Boolean = asNode(p) === raiz

    override fun isExternal(p: Position<E>): Boolean {
        val n = asNode(p)
        return n.left == null && n.right == null
    }

    override fun replace(p: Position<E>, e: E): E {
        val node = asNode(p)
        val old = node.getElement()
        node.setElement(e)
        return old
    }

    /**
     * Remueve el subárbol cuya raíz es [p] (desconectándolo del padre) y
     * devuelve el elemento de [p]. Los descendientes se liberan.
     */
    fun remove(p: Position<E>): E {
        val node = asNode(p)
        val e = node.getElement()
        val padre = node.dad
        if (padre == null) {
            raiz = null
            count = 0
            node.borrarSubarbol()
            return e
        }
        if (padre.left === node) padre.left = null else padre.right = null
        count -= contarSubarbol(node)
        node.borrarSubarbol()
        return e
    }

    private fun contarSubarbol(n: BNodo<E>?): Int {
        if (n == null) return 0
        return 1 + contarSubarbol(n.left) + contarSubarbol(n.right)
    }

    @Suppress("UNCHECKED_CAST")
    private fun asNode(p: Position<E>): BNodo<E> {
        require(p is BNodo<*>) { "La posición no pertenece a un árbol binario enlazado." }
        return p as BNodo<E>
    }
}
