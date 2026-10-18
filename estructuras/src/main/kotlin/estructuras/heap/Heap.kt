package estructuras.heap

/**
 * Heap binario genérico indexado en un [ArrayList].
 *
 * [comparator] define el orden: en un [MinHeap] el mínimo queda en la raíz; un
 * [MaxHeap] invierte el comparador. Es la base de la cola con prioridad.
 *
 * Réplica del patrón `Heap` / `MinHeap` / `MaxHeap` de FunshiEngineOpenGL.
 */
abstract class Heap<E>(protected val comparator: Comparator<E>) {
    protected val data = ArrayList<E>()

    fun size(): Int = data.size

    fun isEmpty(): Boolean = data.isEmpty()

    fun clear() = data.clear()

    /** Elemento en la raíz (el de mayor prioridad) sin extraerlo. */
    fun peek(): E? = data.firstOrNull()

    /** Inserta [value] y reordena hacia arriba. */
    fun insert(value: E) {
        data.add(value)
        siftUp(data.size - 1)
    }

    /** Extrae y devuelve el elemento de mayor prioridad (raíz). */
    fun extract(): E? {
        if (data.isEmpty()) return null
        val top = data[0]
        val last = data.removeAt(data.size - 1)
        if (data.isNotEmpty()) {
            data[0] = last
            siftDown(0)
        }
        return top
    }

    private fun siftUp(startIndex: Int) {
        var idx = startIndex
        while (idx > 0) {
            val parent = (idx - 1) / 2
            if (comparator.compare(data[idx], data[parent]) >= 0) break
            swap(idx, parent)
            idx = parent
        }
    }

    private fun siftDown(startIndex: Int) {
        var idx = startIndex
        val n = data.size
        while (true) {
            val left = 2 * idx + 1
            val right = 2 * idx + 2
            var smallest = idx
            if (left < n && comparator.compare(data[left], data[smallest]) < 0) smallest = left
            if (right < n && comparator.compare(data[right], data[smallest]) < 0) smallest = right
            if (smallest == idx) break
            swap(idx, smallest)
            idx = smallest
        }
    }

    private fun swap(i: Int, j: Int) {
        val tmp = data[i]
        data[i] = data[j]
        data[j] = tmp
    }
}

/** Heap de mínimos: siempre extrae el elemento menor según el comparador. */
class MinHeap<E>(comparator: Comparator<E>) : Heap<E>(comparator)

/** Heap de máximos: siempre extrae el elemento mayor según el comparador. */
class MaxHeap<E>(comparator: Comparator<E>) :
    Heap<E>(Comparator { a, b -> comparator.compare(b, a) })
