package estructuras.arboles

import estructuras.posicion.Position

/**
 * Árbol genérico (interfaz común a árbol binario y general).
 *
 * Réplica de `Tree<E>` de FunshiEngineOpenGL: las operaciones trabajan sobre
 * [Position]. Las operaciones específicas de cada forma (agregar hijos,
 * remover subárbol) viven en las implementaciones concretas.
 */
interface Tree<E> {
    fun isEmpty(): Boolean
    fun size(): Int

    fun root(): Position<E>?
    fun parent(p: Position<E>): Position<E>?
    fun children(p: Position<E>): List<Position<E>>

    fun isRoot(p: Position<E>): Boolean
    fun isExternal(p: Position<E>): Boolean
    fun isInternal(p: Position<E>): Boolean = !isExternal(p)

    /** Reemplaza el elemento en [p] por [e] y devuelve el anterior. */
    fun replace(p: Position<E>, e: E): E
}
