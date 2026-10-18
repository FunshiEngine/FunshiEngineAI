package estructuras.listas

import estructuras.nodos.DNodo
import estructuras.posicion.Position

/**
 * Lista doblemente enlazada con nodos [DNodo]. Implementa [PositionList] con
 * punteros a la cabeza ([head]) y a la cola ([tail]).
 *
 * Réplica de `ListaDE<E>` de FunshiEngineOpenGL, adaptada a Kotlin (nodos
 * nullable en vez de centinelas, para evitar la construcción imposible de un
 * elemento `E` de relleno).
 */
class ListaDE<E> : PositionList<E> {
    private var head: DNodo<E>? = null
    private var tail: DNodo<E>? = null
    private var count = 0

    override fun isEmpty(): Boolean = head == null

    override fun size(): Int = count

    override fun first(): Position<E>? = head

    override fun last(): Position<E>? = tail

    override fun addFirst(e: E) {
        val node = DNodo(e)
        val h = head
        if (h == null) {
            head = node
            tail = node
        } else {
            node.next = h
            h.prev = node
            head = node
        }
        count++
    }

    override fun addLast(e: E) {
        val node = DNodo(e)
        val t = tail
        if (t == null) {
            head = node
            tail = node
        } else {
            node.prev = t
            t.next = node
            tail = node
        }
        count++
    }

    override fun addAfter(p: Position<E>, e: E) {
        val node = asNode(p)
        val nuevo = DNodo(e)
        nuevo.prev = node
        nuevo.next = node.next
        node.next?.prev = nuevo
        node.next = nuevo
        if (node === tail) tail = nuevo
        count++
    }

    override fun addBefore(p: Position<E>, e: E) {
        val node = asNode(p)
        val nuevo = DNodo(e)
        nuevo.next = node
        nuevo.prev = node.prev
        node.prev?.next = nuevo
        node.prev = nuevo
        if (node === head) head = nuevo
        count++
    }

    override fun next(p: Position<E>): Position<E>? = asNode(p).next

    override fun prev(p: Position<E>): Position<E>? = asNode(p).prev

    override fun remove(p: Position<E>): E {
        val node = asNode(p)
        val e = node.getElement()
        val prevNode = node.prev
        val nextNode = node.next
        prevNode?.next = nextNode
        nextNode?.prev = prevNode
        if (node === head) head = nextNode
        if (node === tail) tail = prevNode
        node.prev = null
        node.next = null
        count--
        return e
    }

    override fun replace(p: Position<E>, e: E): E {
        val node = asNode(p)
        val old = node.getElement()
        node.setElement(e)
        return old
    }

    override fun clear() {
        head = null
        tail = null
        count = 0
    }

    /** Convierte una [Position] en nodo interno, validando que sea un [DNodo]. */
    @Suppress("UNCHECKED_CAST")
    private fun asNode(p: Position<E>): DNodo<E> {
        require(p is DNodo<*>) { "La posición no pertenece a una lista doblemente enlazada." }
        return p as DNodo<E>
    }
}
