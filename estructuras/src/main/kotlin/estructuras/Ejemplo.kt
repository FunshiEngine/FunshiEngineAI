package estructuras

import estructuras.arboles.ArbolBinarioEnlazado
import estructuras.arboles.ArbolEnlazado
import estructuras.colas.ColaListaDE
import estructuras.colas.ColaPrioridadHeap
import estructuras.heap.MaxHeap
import estructuras.heap.MinHeap
import estructuras.listas.ListaDE
import estructuras.listas.PositionList
import estructuras.pilas.PilaDE
import estructuras.posicion.Position

/**
 * Demostración de las estructuras de datos dinámicas. No es un test formal:
 * sólo ejercita cada estructura e imprime el resultado, como referencia de uso.
 */
fun main() {
    // Lista doblemente enlazada basada en posiciones.
    val lista = ListaDE<String>()
    lista.addLast("b")
    lista.addFirst("a")
    val posB = lista.next(lista.first()!!)!!   // posición de "b"
    lista.addAfter(posB, "c")                  // a, b, c
    println("ListaDE: ${recorrer(lista)}")

    // Cola FIFO.
    val cola = ColaListaDE<Int>()
    cola.enqueue(1); cola.enqueue(2); cola.enqueue(3)
    println("Cola (dequeue=1): ${cola.dequeue()} · front=${cola.front()}")

    // Cola con prioridad (menor prioridad numérica sale primero).
    val cp = ColaPrioridadHeap<String>()
    cp.enqueue("bajo", 3.0)
    cp.enqueue("urgente", 1.0)
    cp.enqueue("medio", 2.0)
    println("ColaPrioridad (dequeue=urgente): ${cp.dequeue()}")

    // Pila LIFO.
    val pila = PilaDE<Int>()
    pila.push(1); pila.push(2); pila.push(3)
    println("Pila (pop=3): ${pila.pop()} · top=${pila.top()}")

    // Heaps.
    val min = MinHeap<Int>(compareBy { it })
    listOf(5, 1, 4, 2).forEach { min.insert(it) }
    println("MinHeap (extract=1): ${min.extract()}")
    val max = MaxHeap<Int>(compareBy { it })
    listOf(5, 1, 4, 2).forEach { max.insert(it) }
    println("MaxHeap (extract=5): ${max.extract()}")

    // Árbol binario enlazado.
    val bin = ArbolBinarioEnlazado<String>()
    val raiz = bin.createRoot("raiz")
    val izq = bin.addLeft(raiz, "izq")
    bin.addRight(raiz, "der")
    bin.addLeft(izq, "hijo-izq")
    println("Árbol binario (hijos de raíz): ${bin.children(raiz).map { it.getElement() }}")

    // Árbol general (N-ario).
    val nario = ArbolEnlazado<String>()
    val r = nario.createRoot("raiz")
    val a = nario.addChild(r, "a")
    nario.addChild(r, "b")
    nario.addChild(a, "a1")
    println("Árbol general (hijos de raíz): ${nario.children(r).map { it.getElement() }}")
}

/** Recorre una [PositionList] desde el primero con next() y devuelve los elementos. */
private fun recorrer(lista: PositionList<String>): List<String> {
    val out = mutableListOf<String>()
    var pos: Position<String>? = lista.first()
    while (pos != null) {
        out += pos.getElement()
        pos = lista.next(pos)
    }
    return out
}
