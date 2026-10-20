package app.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * Adapter de scrollbar del chat con tamaño de thumb ESTABLE.
 *
 * El adapter de Compose (`rememberScrollbarAdapter`) estima el tamaño total
 * del contenido como `promedio(ítems VISIBLES) * total`. Como el promedio
 * cambia con lo que esté al lado del scrollbar, el thumb se agrandaba y
 * encogía al desplazarse (medido en la misma conversación: ~32px con un
 * mensaje largo a la vista contra ~350px con mensajes cortos arriba).
 *
 * Este adapter acumula el tamaño medido de cada ítem que se muestra alguna
 * vez (mapa índice→px, invalidado por chat) y estima los todavía no vistos
 * con el promedio de los conocidos: el total converge al real a medida que
 * se recorre la conversación y el thumb pasa a depender del tamaño del chat,
 * no del mensaje contiguo. Las convenciones de offset, spacing y padding
 * replican las del adapter original para que las posiciones del thumb, el
 * arrastre y el click en la pista sigan siendo los mismos.
 *
 * Nota: la estimación se corrige al volver a medir (por ejemplo, al
 * redimensionar la ventana el texto se reflowea y cada ítem visible
 * sobreescribe su tamaño al entrar en pantalla).
 */
class StableChatScrollbarAdapter(
    private val listState: LazyListState,
    private val sizes: Map<Int, Float>,
) : ScrollbarAdapter {

    /** Promedio de los ítems ya medidos; 0 si aún no se midió ninguno. */
    private fun averageKnown(): Double =
        if (sizes.isEmpty()) 0.0 else sizes.values.average()

    /** Tamaño exacto si el ítem ya se midió; si no, el promedio conocido. */
    private fun sizeOf(index: Int, average: Double): Double =
        sizes[index]?.toDouble() ?: average

    /** Posición de contenido del inicio del ítem [index] (top del ítem 0 = 0). */
    private fun prefix(index: Int, average: Double, spacing: Double): Double {
        var acc = 0.0
        for (i in 0 until index) acc += sizeOf(i, average) + spacing
        return acc
    }

    override val scrollOffset: Double
        get() {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull() ?: return 0.0
            val spacing = info.mainAxisItemSpacing.toDouble()
            return prefix(first.index, averageKnown(), spacing) - first.offset
        }

    override val contentSize: Double
        get() {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return 0.0
            val spacing = info.mainAxisItemSpacing.toDouble()
            val average = averageKnown()
            var acc = 0.0
            for (i in 0 until total) acc += sizeOf(i, average)
            return acc + spacing * (total - 1) + info.beforeContentPadding + info.afterContentPadding
        }

    override val viewportSize: Double
        get() = listState.layoutInfo.viewportSize.height.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return
        val spacing = info.mainAxisItemSpacing.toDouble()
        val average = averageKnown()
        val target = scrollOffset.coerceAtLeast(0.0)
        var index = 0
        var itemStart = 0.0
        while (index < total) {
            val size = sizeOf(index, average)
            // El target cae dentro de este ítem (o ya no queda ítem: último).
            if (target < itemStart + size || index == total - 1) {
                val inner = (target - itemStart).toInt()
                    .coerceIn(0, (size.toInt() - 1).coerceAtLeast(0))
                listState.scrollToItem(index, inner)
                return
            }
            itemStart += size + spacing
            index++
        }
    }
}

/**
 * [remember] del [StableChatScrollbarAdapter] para el chat: mide cada ítem
 * visible y mantiene un mapa de tamaños por conversación ([chatId]), de modo
 * que cambiar de chat arranque con estimaciones propias de ese chat.
 */
@Composable
fun rememberStableChatScrollbarAdapter(
    listState: LazyListState,
    chatId: String,
): ScrollbarAdapter {
    val sizes = remember(chatId) { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(listState, chatId) {
        var lastTotal = 0
        snapshotFlow { listState.layoutInfo.totalItemsCount to listState.layoutInfo.visibleItemsInfo }
            .collect { (total, visibles) ->
                // Si la conversación se ACORTA (borrar mensaje, "Reiniciar",
                // "Editar y reenviar") los índices corren: un tamaño medido en
                // el índice N ya no corresponde al mensaje que hoy ocupa ese N
                // y el total quedaría pisado con medidas viejas. Se empieza de
                // cero con lo que esté a la vista (se vuelve a medir al toque).
                if (total < lastTotal) sizes.clear()
                lastTotal = total
                for (item in visibles) {
                    val size = item.size.toFloat()
                    if (sizes[item.index] != size) sizes[item.index] = size
                }
            }
    }
    return remember(listState, sizes) { StableChatScrollbarAdapter(listState, sizes) }
}
