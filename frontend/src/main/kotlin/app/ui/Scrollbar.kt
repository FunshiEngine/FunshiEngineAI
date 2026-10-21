package app.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Scrollbar vertical de la app: con el **estilo del tema** y **teclado**.
 *
 * Dos problemas que resuelve:
 *
 * 1. **El color**: el scrollbar de Compose es negro con 12% de opacidad, así
 *    que en el tema oscuro (el de la app) el thumb es invisible: el panel
 *    parecía que no se desplazaba. Con `onSurface` se ve en claro y en
 *    oscuro, y al recibir el foco se pinta como el de hover.
 * 2. **El teclado**: en Compose 1.7 el scrollbar no es focusable ni atiende
 *    teclas. Con Tab se llega al thumb y con las flechas (↑ ↓) se mueve el
 *    contenido; `Re Pág` / `Av Pág` saltan una pantalla y `Inicio` / `Fin`
 *    van al principio o al final. Sirve para moverse sin mouse.
 */
@Composable
fun ThemedVerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** Cuánto se puede desplazar como máximo (contenido - ventana). */
    val maxScroll = (adapter.contentSize - adapter.viewportSize).coerceAtLeast(0.0)

    fun goTo(target: Double) {
        val destino = target.coerceIn(0.0, maxScroll)
        scope.launch { adapter.scrollTo(destino) }
    }

    VerticalScrollbar(
        adapter = adapter,
        modifier = modifier
            // Focusable: el thumb se puede alcanzar con Tab y así usar las
            // flechas sin tocar el mouse.
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val destino = scrollTargetForKey(
                    key = event.key,
                    offset = adapter.scrollOffset,
                    viewportSize = adapter.viewportSize,
                    maxScroll = maxScroll,
                ) ?: return@onKeyEvent false
                goTo(destino)
                true
            },
        style = themedScrollbarStyle(focused),
    )
}

/**
 * Estilo del scrollbar acorde al tema.
 *
 * El de Compose por defecto es negro al 12%: invisible sobre el tema oscuro.
 * Cuando el scrollbar tiene el foco se usa el color de hover, para que se
 * vea dónde está el cursor del teclado.
 */
@Composable
fun themedScrollbarStyle(focused: Boolean = false): ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 24.dp,
    thickness = 12.dp,
    shape = RoundedCornerShape(6.dp),
    hoverDurationMillis = 300,
    unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
).let { if (focused) it.copy(unhoverColor = it.hoverColor) else it }

/** Cuánto se mueve el contenido con una pulsación de flecha (px). */
private const val LINE_STEP = 48.0

/**
 * A dónde hay que desplazarse con una tecla (o `null` si la tecla no mueve).
 *
 * Función **pura** (sin Compose ni I/O) para poder testearla: las flechas
 * mueven una línea, `Re Pág`/`Av Pág` una pantalla, e `Inicio`/`Fin` van al
 * principio o al final. El resultado se acota al rango desplazable.
 */
internal fun scrollTargetForKey(
    key: Key,
    offset: Double,
    viewportSize: Double,
    maxScroll: Double,
): Double? {
    val destino = when (key) {
        Key.DirectionUp -> offset - LINE_STEP
        Key.DirectionDown -> offset + LINE_STEP
        Key.PageUp -> offset - viewportSize
        Key.PageDown -> offset + viewportSize
        Key.MoveHome -> 0.0
        Key.MoveEnd -> maxScroll
        else -> return null
    }
    return destino.coerceIn(0.0, maxScroll.coerceAtLeast(0.0))
}
