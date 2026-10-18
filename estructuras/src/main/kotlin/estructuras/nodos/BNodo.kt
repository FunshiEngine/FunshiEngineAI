package estructuras.nodos

import estructuras.posicion.Position

/**
 * Nodo de un árbol binario enlazado: referencia a su padre ([dad]) y a sus (a
 * lo sumo dos) hijos ([left] / [right]).
 *
 * Es dueño de su subárbol: [borrarSubarbol] libera los descendientes. Los
 * elementos `E` NO son propiedad del nodo (no se destruyen aquí).
 *
 * Réplica de `BNodo<E>` de FunshiEngineOpenGL.
 */
class BNodo<E>(private var element: E, var dad: BNodo<E>? = null) : Position<E> {
    var left: BNodo<E>? = null
    var right: BNodo<E>? = null

    override fun getElement(): E = element

    fun setElement(e: E) {
        element = e
    }

    /** Libera recursivamente el subárbol de este nodo (desconecta los hijos). */
    fun borrarSubarbol() {
        left?.borrarSubarbol()
        right?.borrarSubarbol()
        left = null
        right = null
        dad = null
    }
}
