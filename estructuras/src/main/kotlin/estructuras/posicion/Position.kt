package estructuras.posicion

/**
 * Referencia genérica a un elemento dentro de una estructura de datos.
 *
 * Réplica de `Position<E>` de FunshiEngineOpenGL: las estructuras exponen sus
 * elementos como posiciones ([estructuras.listas.PositionList],
 * [estructuras.arboles.Tree]) para poder insertar, remover y recorrer de
 * forma polimórfica sin exponer los nodos concretos.
 */
interface Position<E> {
    /** Elemento almacenado en esta posición. */
    fun getElement(): E
}
