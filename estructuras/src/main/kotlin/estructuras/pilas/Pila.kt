package estructuras.pilas

import estructuras.listas.ListaDE

/**
 * Pila genérica LIFO (Last-In First-Out).
 *
 * Réplica del patrón de pilas de FunshiEngineOpenGL.
 */
interface Pila<E> {
    fun isEmpty(): Boolean
    fun size(): Int

    /** Apila [e] en la cima. */
    fun push(e: E)

    /** Desapila y devuelve la cima (o `null` si está vacía). */
    fun pop(): E?

    /** Devuelve la cima sin quitarla (o `null` si está vacía). */
    fun top(): E?

    fun clear()
}

/**
 * Pila LIFO implementada con una [ListaDE]: [push] y [pop] ocurren en el
 * frente, en O(1).
 */
class PilaDE<E> : Pila<E> {
    private val list = ListaDE<E>()

    override fun isEmpty(): Boolean = list.isEmpty()

    override fun size(): Int = list.size()

    override fun push(e: E) = list.addFirst(e)

    override fun pop(): E? = list.first()?.let { list.remove(it) }

    override fun top(): E? = list.first()?.getElement()

    override fun clear() = list.clear()
}
