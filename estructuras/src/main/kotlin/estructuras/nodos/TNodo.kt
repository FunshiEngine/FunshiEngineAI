package estructuras.nodos

import estructuras.posicion.Position

/**
 * Nodo de un árbol general (N-ario): referencia a su padre ([rootDad]) y a su
 * lista de hijos ([childs]).
 *
 * Los modificadores de [childs] los maneja la estructura
 * ([estructuras.arboles.ArbolEnlazado]); el nodo sólo guarda el vínculo.
 *
 * Réplica de `TNodo<E>` de FunshiEngineOpenGL.
 */
class TNodo<E>(private var element: E, var rootDad: TNodo<E>? = null) : Position<E> {
    /** Hijos directos de este nodo. */
    val childs: MutableList<TNodo<E>> = mutableListOf()

    override fun getElement(): E = element

    fun setElement(e: E) {
        element = e
    }
}
