package estructuras.colas

import estructuras.heap.MinHeap

/**
 * Cola con prioridad genérica: [enqueue] recibe el elemento y su prioridad
 * (menor número = mayor prioridad); [dequeue] siempre extrae el de mayor
 * prioridad.
 *
 * Réplica del patrón de listas con prioridad de FunshiEngineOpenGL.
 */
interface ColaPrioridad<E> {
    fun isEmpty(): Boolean
    fun size(): Int

    /** Encola [e] con la [priority] dada (menor = sale primero). */
    fun enqueue(e: E, priority: Double)

    /** Extrae y devuelve el elemento de mayor prioridad (o `null`). */
    fun dequeue(): E?

    /** Devuelve el elemento de mayor prioridad sin extraerlo (o `null`). */
    fun peek(): E?

    fun clear()
}

/**
 * Cola con prioridad implementada con un [MinHeap]: el elemento de menor
 * prioridad numérica queda en la raíz y sale primero.
 */
class ColaPrioridadHeap<E> : ColaPrioridad<E> {
    private data class Entrada<E>(val element: E, val priority: Double)

    private val heap = MinHeap<Entrada<E>>(compareBy { it.priority })

    override fun isEmpty(): Boolean = heap.isEmpty()

    override fun size(): Int = heap.size()

    override fun enqueue(e: E, priority: Double) = heap.insert(Entrada(e, priority))

    override fun dequeue(): E? = heap.extract()?.element

    override fun peek(): E? = heap.peek()?.element

    override fun clear() = heap.clear()
}
