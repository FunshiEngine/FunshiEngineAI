package estructuras.colas

import estructuras.listas.ListaDE

/**
 * Cola genérica FIFO (First-In First-Out).
 *
 * Réplica del patrón de colas de FunshiEngineOpenGL.
 */
interface Cola<E> {
    fun isEmpty(): Boolean
    fun size(): Int

    /** Encola [e] al final de la cola. */
    fun enqueue(e: E)

    /** Desencola y devuelve el primer elemento (o `null` si está vacía). */
    fun dequeue(): E?

    /** Devuelve el primer elemento sin quitarlo (o `null` si está vacía). */
    fun front(): E?

    fun clear()
}

/**
 * Cola FIFO implementada con una [ListaDE]: [enqueue] al final y [dequeue]
 * desde el frente, en O(1) amortizado.
 */
class ColaListaDE<E> : Cola<E> {
    private val list = ListaDE<E>()

    override fun isEmpty(): Boolean = list.isEmpty()

    override fun size(): Int = list.size()

    override fun enqueue(e: E) = list.addLast(e)

    override fun dequeue(): E? = list.first()?.let { list.remove(it) }

    override fun front(): E? = list.first()?.getElement()

    override fun clear() = list.clear()
}
