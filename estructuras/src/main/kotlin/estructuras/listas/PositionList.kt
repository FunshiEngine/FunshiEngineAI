package estructuras.listas

import estructuras.posicion.Position

/**
 * Lista genérica basada en posiciones (interfaz).
 *
 * Réplica de `PositionList<E>` de FunshiEngineOpenGL: todas las operaciones
 * trabajan sobre [Position] devueltas por la propia estructura.
 */
interface PositionList<E> {
    fun isEmpty(): Boolean
    fun size(): Int

    fun first(): Position<E>?
    fun last(): Position<E>?

    fun addFirst(e: E)
    fun addLast(e: E)
    fun addAfter(p: Position<E>, e: E)
    fun addBefore(p: Position<E>, e: E)

    fun next(p: Position<E>): Position<E>?
    fun prev(p: Position<E>): Position<E>?

    /** Remueve y devuelve el elemento en la posición [p]. */
    fun remove(p: Position<E>): E

    /** Reemplaza el elemento en [p] por [e] y devuelve el anterior. */
    fun replace(p: Position<E>, e: E): E

    fun clear()
}
