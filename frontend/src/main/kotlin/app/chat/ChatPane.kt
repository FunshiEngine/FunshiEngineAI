package app.chat

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pixeles "de más" al hacer scroll-to-bottom: el último mensaje suele ser más alto que el viewport. */
private const val SCROLL_PX_TO_END = 1_000_000f

/** Chat con lista de conversaciones, scrollbar, cancelar, editar y borrar. */
@Composable
fun ChatPane(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scrollbarAdapter = rememberStableChatScrollbarAdapter(listState, vm.activeChatId)
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    /** Índice del mensaje de usuario que se está editando (-1 = ninguno). */
    var editingIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }

    /**
     * ¿Seguir el pie del chat mientras la IA escribe? El flag sólo cambia cuando
     * la POSICIÓN de scroll se mueve (scroll del usuario o nuestro): NO cuando el
     * último mensaje crece. Con el chequeo anterior («último ítem a la vista») una
     * respuesta más alta que el viewport mantenía el flag en true y cada token
     * arrastraba al usuario al fondo: era imposible leer una respuesta larga
     * mientras se escribía.
     */
    val followToBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prev = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { pos ->
            if (pos != prev) {
                val subio = pos.first < prev.first ||
                    (pos.first == prev.first && pos.second < prev.second)
                // Subió = dejó de seguir; bajó = sólo sigue si llegó al pie real.
                followToBottom.value = if (subio) false else !listState.canScrollForward
                prev = pos
            }
        }
    }

    /**
     * Baja hasta el final real de la lista (el último mensaje puede ser más
     * alto que el viewport). Usa el índice de los DATOS (vm.messages), no el
     * de layoutInfo: justo después de enviar, layoutInfo todavía no midió los
     * mensajes nuevos y apuntaba al final VIEJO de la conversación.
     */
    suspend fun scrollToBottom() {
        val last = vm.messages.lastIndex
        if (last < 0) return
        listState.scrollToItem(last)
        listState.scroll { scrollBy(SCROLL_PX_TO_END) }
    }

    // Al entrar al chat o cambiar de conversación: abajo del todo.
    LaunchedEffect(vm.activeChatId) {
        if (vm.messages.isNotEmpty()) scrollToBottom()
    }

    // Mensaje NUEVO en la lista (envío del usuario, placeholder de la IA o
    // cambio de chat): SIEMPRE se ve. No se chequea nearBottom() acá: al
    // recién agregar ítems, el último visible deja de ser el último de la
    // lista y el chequeo daba falso (el mensaje salía y no se bajaba a verlo).
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) scrollToBottom()
    }

    // Mientras la IA responde el último mensaje crece SIN cambiar la cantidad
    // de ítems: el chat sigue bajando sólo si el usuario no se fue a leer
    // hacia arriba (ahí layoutInfo es confiable, no cambió el conteo).
    LaunchedEffect(vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty() && followToBottom.value) scrollToBottom()
    }

    Row(Modifier.fillMaxSize()) {
        // Sidebar de chats: crear, cambiar y borrar conversaciones.
        ChatListSidebar(vm, Modifier.width(220.dp))

        Column(Modifier.weight(1f).fillMaxSize()) {
            // Estado + modo + reindexar
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChatModeToggle(vm)
                    Spacer(Modifier.width(12.dp))
                    // Permisos de herramientas concedidos en este chat (F3).
                    Text(
                        "Permisos: ${vm.grantedCategoriesLabel()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(vm.status, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
                    TextButton(onClick = vm::reindex, enabled = vm.ready && !vm.busy) { Text("Reindexar") }
                }
            }
            if (!vm.ready) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // Mensajes: weight(1f) deja SIEMPRE espacio para la barra de envío.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(vm.messages) { index, m ->
                        MessageBubble(
                            m = m,
                            editable = !vm.busy && !vm.queued &&
                                m.role == "user" && m.text.isNotBlank() && m.text != "…",
                            editableNow = editingIndex == index,
                            editText = editText,
                            onEditTextChange = { editText = it },
                            onEdit = {
                                editingIndex = index
                                editText = m.text
                            },
                            onCancelEdit = { editingIndex = -1 },
                            onSaveEdit = {
                                vm.editAndResend(index, editText)
                                editingIndex = -1
                            },
                            onDelete = { vm.deleteMessage(index) },
                            // Copiar: útil sobre todo en la respuesta de la IA.
                            onCopy = { clipboard.setText(AnnotatedString(m.text)) },
                            // Reiniciar: sólo sobre la última respuesta entregada.
                            onRegenerate = if (
                                m.role == "assistant" && index == vm.messages.lastIndex &&
                                !vm.busy && !vm.queued && m.text.isNotBlank() && m.text != "…"
                            ) {
                                { vm.regenerate(index) }
                            } else null,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = scrollbarAdapter,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = ScrollbarStyle(
                        minimalHeight = 24.dp,
                        thickness = 12.dp,
                        shape = RoundedCornerShape(6.dp),
                        hoverDurationMillis = 300,
                        unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    ),
                )
            }

            // Barra de envío (fija abajo, dentro del área de contenido)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ComposerBar(vm, input, { input = it }, focusRequester)
        }
    }

    // Diálogo de permiso: el modelo pide una herramienta sensible (F3).
    PermissionDialog(vm)
}

// ---------------------------------------------------------------------------
// Toggle de modo del chat: General / Documentación.
// ---------------------------------------------------------------------------

@Composable
private fun ChatModeToggle(vm: ChatViewModel) {
    val isGeneral = vm.chatMode != "DOCS"
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ModeChip("General", selected = isGeneral) { vm.changeChatMode("GENERAL") }
        ModeChip("Docs", selected = !isGeneral) { vm.changeChatMode("DOCS") }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, color = fg)
    }
}
