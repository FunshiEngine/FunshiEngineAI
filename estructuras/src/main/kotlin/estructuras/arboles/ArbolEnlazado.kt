package estructuras.arboles

import estructuras.nodos.TNodo
import estructuras.posicion.Position

/**
 * Árbol general (N-ario) con nodos [TNodo]. Cada nodo conoce a su padre y a
 * su lista de hijos.
 *
 * Soporta crear la raíz, agregar hijos a cualquier nodo y remover nodos
 * externos o el subárbol de la raíz. Réplica de `ArbolEnlazado` de
 * FunshiEngineOpenGL.
 */
class ArbolEnlazado<E> : Tree<E> {
    private var raiz: TNodo<E>? = null
    private var count = 0

    override fun isEmpty(): Boolean = raiz == null

    override fun size(): Int = count

    override fun root(): Position<E>? = raiz

    /** Crea la raíz del árbol (sólo si aún no existe) y devuelve su posición. */
    fun createRoot(e: E): Position<E> {
        check(raiz == null) { "El árbol ya tiene raíz." }
        val nodo = TNodo(e)
        raiz = nodo
        count = 1
        return nodo
    }

    override fun parent(p: Position<E>): Position<E>? = asNode(p).rootDad

    override fun children(p: Position<E>): List<Position<E>> = asNode(p).childs

    /** Agrega [e] como hijo de [p] y devuelve la nueva posición. */
    fun addChild(p: Position<E>, e: E): Position<E> {
        val node = asNode(p)
        val child = TNodo(e, node)
        node.childs.add(child)
        count++
        return child
    }

    override fun isRoot(p: Position<E>): Boolean = asNode(p) === raiz

    override fun isExternal(p: Position<E>): Boolean = asNode(p).childs.isEmpty()

    override fun replace(p: Position<E>, e: E): E {
        val node = asNode(p)
        val old = node.getElement()
        node.setElement(e)
        return old
    }

    /** Remueve y devuelve el nodo externo (sin hijos) [p]. */
    fun removeExternal(p: Position<E>): E {
        val node = asNode(p)
        check(node.childs.isEmpty()) { "Sólo se pueden remover nodos externos (sin hijos)." }
        val padre = node.rootDad ?: error("No se puede remover la raíz con removeExternal; use removeRoot.")
        padre.childs.remove(node)
        node.rootDad = null
        count--
        return node.getElement()
    }

    /** Remueve el subárbol completo cuya raíz es [p] y devuelve su elemento. */
    fun remove(p: Position<E>): E {
        val node = asNode(p)
        val e = node.getElement()
        val padre = node.rootDad
        val eliminados = contarSubarbol(node)
        if (padre == null) {
            raiz = null
        } else {
            padre.childs.remove(node)
        }
        node.rootDad = null
        count -= eliminados
        return e
    }

    /**
     * Mueve el subárbol [p] para que quede como hijo de [newParent] en la
     * posición [index] (0 = primero, size = último). Útil para reordenar por
     * arrastre (drag & drop) sin perder los subnodos.
     */
    fun move(p: Position<E>, newParent: Position<E>, index: Int) {
        val node = asNode(p)
        val destino = asNode(newParent)
        require(!esAncestro(node, destino)) {
            "No se puede mover un nodo dentro de su propio subárbol."
        }
        val viejoPadre = node.rootDad
        viejoPadre?.childs?.remove(node)
        val idx = index.coerceIn(0, destino.childs.size)
        destino.childs.add(idx, node)
        node.rootDad = destino
    }

    /** ¿Es [candidato] el propio [ancestro] o un descendiente suyo? */
    private fun esAncestro(ancestro: TNodo<E>, candidato: TNodo<E>): Boolean {
        var cur: TNodo<E>? = candidato
        while (cur != null) {
            if (cur === ancestro) return true
            cur = cur.rootDad
        }
        return false
    }

    private fun contarSubarbol(n: TNodo<E>?): Int {
        if (n == null) return 0
        var total = 1
        for (hijo in n.childs) total += contarSubarbol(hijo)
        return total
    }

    @Suppress("UNCHECKED_CAST")
    private fun asNode(p: Position<E>): TNodo<E> {
        require(p is TNodo<*>) { "La posición no pertenece a un árbol enlazado." }
        return p as TNodo<E>
    }
}
