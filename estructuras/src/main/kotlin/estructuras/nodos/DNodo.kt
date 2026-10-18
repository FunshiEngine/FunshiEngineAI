package estructuras.nodos

import estructuras.posicion.Position

/**
 * Nodo de una lista doblemente enlazada. Referencia a su anterior ([prev]) y a
 * su siguiente ([next]). Es el nodo que usa [estructuras.listas.ListaDE].
 *
 * Réplica de `DNodo<E>` de FunshiEngineOpenGL.
 */
class DNodo<E>(private var element: E) : Position<E> {
    var prev: DNodo<E>? = null
    var next: DNodo<E>? = null

    override fun getElement(): E = element

    fun setElement(e: E) {
        element = e
    }
}
