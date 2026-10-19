package app

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pixeles "de más" al hacer scroll-to-bottom: el último mensaje suele ser más alto que el viewport. */
private const val SCROLL_PX_TO_END = 1_000_000f

/** Chat con lista de conversaciones, scrollbar, cancelar, editar y borrar. */
@Composable
fun ChatPane(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current
    /** Índice del mensaje de usuario que se está editando (-1 = ninguno). */
    var editingIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }

    /** ¿El último mensaje está a la vista? (si el usuario se fue a leer arriba, no lo arrastramos). */
    fun nearBottom(): Boolean {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= info.totalItemsCount - 1
    }

    /** Baja hasta el final real de la lista (el último mensaje puede ser más alto que el viewport). */
    suspend fun scrollToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        listState.scrollToItem(last)
        listState.scroll { scrollBy(SCROLL_PX_TO_END) }
    }

    // Al entrar al chat o cambiar de conversación: abajo del todo.
    LaunchedEffect(vm.activeChatId) {
        if (vm.messages.isNotEmpty()) scrollToBottom()
    }

    // Mientras la IA responde, el chat va bajando con la respuesta
    // (sólo si el usuario no se fue a leer hacia arriba).
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty() && nearBottom()) scrollToBottom()
    }

    fun submit() {
        vm.send(input)
        input = ""
        // El foco se queda en el redactor: si la IA está respondiendo, el
        // mensaje se encola y se podrá seguir escribiendo.
        if (vm.ready) focusRequester.requestFocus()
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
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }

            // Barra de envío (fija abajo, dentro del área de contenido)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester).onPreviewKeyEvent { e ->
                            if (e.key == androidx.compose.ui.input.key.Key.Enter &&
                                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                                !e.isShiftPressed
                            ) {
                                submit(); true
                            } else false
                        },
                        placeholder = {
                            Text(
                                when {
                                    !vm.ready -> "Preparando…"
                                    // Con la IA respondiendo el campo sigue activo: lo que
                                    // se escriba se encola y se envía junto al terminar.
                                    vm.busy -> "Escribí tu mensaje… (se encolará hasta que termine la respuesta)"
                                    else -> "Escribí tu mensaje… (/help para ver comandos)"
                                }
                            )
                        },
                        // Habilitado aunque la IA esté respondiendo: permite encolar.
                        enabled = vm.ready,
                        maxLines = 5,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (vm.busy) {
                        Button(onClick = vm::cancel) { Text("Detener") }
                    } else if (vm.queued) {
                        Button(onClick = vm::cancel) { Text("Cancelar envío") }
                    } else {
                        Button(onClick = ::submit, enabled = vm.ready && input.isNotBlank()) { Text("Enviar") }
                    }
                    }
                    // Cola de mensajes escritos mientras la IA respondía:
                    // se ven los textos (no solo un contador), con Quitar por mensaje.
                    if (vm.queuedMessages.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${vm.queuedMessages.size} en cola — se enviarán juntos al terminar la respuesta",
                                    Modifier.weight(1f),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = vm::clearQueue) { Text("Descartar todo") }
                            }
                            vm.queuedMessages.forEachIndexed { i, q ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        q,
                                        Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TextButton(onClick = { vm.removeQueued(i) }) { Text("Quitar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sidebar de chats: árbol de carpetas con click derecho, renombrar y
// arrastre (drag & drop) para ordenar y anidar.
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChatListSidebar(vm: ChatViewModel, modifier: Modifier = Modifier) {
    /** Menú de click derecho: sobre qué fila (null = sobre el fondo vacío). */
    var menuRowId by remember { mutableStateOf<String?>(null) }
    var menuOnEmpty by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(Offset.Zero) }

    /** Diálogo de renombrar. */
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    /** Diálogo de borrado con confirmación. */
    var deleteId by remember { mutableStateOf<String?>(null) }

    /** Arrastre en curso. */
    var draggedId by remember { mutableStateOf<String?>(null) }
    var hoverId by remember { mutableStateOf<String?>(null) }

    // Arrastre manual: cada fila publica sus límites (en coordenadas de raíz)
    // y el puntero arrastrado se hit-testea contra ellos para saber dónde
    // soltar. boxOrigin es el origen del sidebar para convertir posiciones.
    val rowBounds = remember { mutableStateMapOf<String, Rect>() }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    fun openMenu(rowId: String?, pos: Offset) {
        menuRowId = rowId
        menuOnEmpty = rowId == null
        menuOffset = pos
    }

    fun hoverTargetAt(pos: Offset): String? =
        vm.chatRows.firstOrNull {
            it.id != draggedId && rowBounds[it.id]?.contains(pos) == true
        }?.id

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onGloballyPositioned { boxOrigin = it.positionInRoot() }
            // Click derecho sobre el FONDO vacío: sólo si el punto NO cayó
            // sobre una fila (las filas abren su propio menú y pisan este).
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.button != PointerButton.Secondary) return@onPointerEvent
                val posRoot = e.changes.first().position + boxOrigin
                val overRow = vm.chatRows.any { rowBounds[it.id]?.contains(posRoot) == true }
                if (!overRow) openMenu(null, e.changes.first().position)
            },
    ) {
        val listState = rememberLazyListState()

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(vm.chatRows, key = { it.id }) { row ->
                ChatRow(
                    vm = vm,
                    row = row,
                    isDragging = draggedId == row.id,
                    isHover = hoverId == row.id && draggedId != null && draggedId != row.id,
                    onClick = {
                        if (row.isFolder) vm.toggleFolder(row.id) else vm.switchChat(row.id)
                    },
                    onContextMenu = { posRoot -> openMenu(row.id, posRoot - boxOrigin) },
                    onBounds = { rect -> rowBounds[row.id] = rect },
                    onDragStart = { pos ->
                        draggedId = row.id
                        dragPos = pos
                        hoverId = null
                    },
                    onDragMove = { pos ->
                        dragPos = pos
                        hoverId = hoverTargetAt(pos)
                    },
                    onDragEnd = {
                        val id = draggedId
                        val target = hoverId
                        if (id != null && target != null) {
                            val intoFolder = vm.chatRows
                                .firstOrNull { it.id == target }?.isFolder == true
                            vm.dropOnto(id, target, intoFolder)
                        }
                        draggedId = null
                        hoverId = null
                    },
                    onDragCancel = { draggedId = null; hoverId = null },
                )
            }
        }

        // Fantasma del item arrastrado, siguiendo al puntero.
        draggedId?.let { id ->
            val row = vm.chatRows.firstOrNull { it.id == id } ?: return@let
            val start = rowBounds[id]?.topLeft ?: Offset.Zero
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (dragPos.x - start.x + 8f).toInt(),
                            (dragPos.y - start.y + 8f).toInt(),
                        )
                    }
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(row.name, fontSize = 12.sp, maxLines = 1)
            }
        }

        // Menú contextual (sobre un item o sobre el fondo).
        val menuRow = vm.chatRows.firstOrNull { it.id == menuRowId }
        DropdownMenu(
            expanded = menuRowId != null || menuOnEmpty,
            onDismissRequest = { menuRowId = null; menuOnEmpty = false },
            offset = DpOffset(menuOffset.x.dp, menuOffset.y.dp),
        ) {
            when {
                menuRow == null && menuOnEmpty -> {
                    DropdownMenuItem(
                        text = { Text("Nuevo chat") },
                        onClick = { menuOnEmpty = false; vm.newChat(null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Nueva carpeta") },
                        onClick = { menuOnEmpty = false; vm.newFolder(null) },
                    )
                }

                menuRow != null && menuRow.isFolder -> {
                    DropdownMenuItem(
                        text = { Text("Nuevo chat aquí") },
                        onClick = { menuRowId = null; vm.newChat(menuRow.id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Nueva carpeta aquí") },
                        onClick = { menuRowId = null; vm.newFolder(menuRow.id) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Renombrar") },
                        onClick = { renameText = vm.nodeName(menuRow.id); renameId = menuRow.id; menuRowId = null },
                    )
                    DropdownMenuItem(
                        text = { Text("Borrar") },
                        onClick = { deleteId = menuRow.id; menuRowId = null },
                    )
                }

                menuRow != null -> {
                    DropdownMenuItem(
                        text = { Text("Renombrar") },
                        onClick = { renameText = vm.nodeName(menuRow.id); renameId = menuRow.id; menuRowId = null },
                    )
                    DropdownMenuItem(
                        text = { Text("Borrar") },
                        onClick = { deleteId = menuRow.id; menuRowId = null },
                    )
                }
            }
        }

        // Diálogo de renombrar (chats y carpetas).
        renameId?.let { id ->
            AlertDialog(
                onDismissRequest = { renameId = null },
                title = { Text(if (vm.nodeIsFolder(id)) "Renombrar carpeta" else "Renombrar chat") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { vm.renameNode(id, renameText); renameId = null },
                        enabled = renameText.isNotBlank(),
                    ) { Text("Guardar") }
                },
                dismissButton = { OutlinedButton(onClick = { renameId = null }) { Text("Cancelar") } },
            )
        }

        // Diálogo de borrado: para carpetas con contenido pide confirmación.
        deleteId?.let { id ->
            val isFolder = vm.nodeIsFolder(id)
            val name = vm.nodeName(id)
            val (chats, folders) = vm.subtreeCounts(id)
            AlertDialog(
                onDismissRequest = { deleteId = null },
                title = { Text(if (isFolder) "Borrar carpeta" else "Borrar chat") },
                text = {
                    Text(
                        if (isFolder) {
                            "La carpeta \"$name\" contiene $chats chat(s) y " +
                                "${folders - 1} carpeta(s). Se eliminará todo su contenido. ¿Continuar?"
                        } else {
                            "¿Borrar el chat \"$name\"?"
                        }
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { vm.deleteNode(id); deleteId = null },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Borrar") }
                },
                dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancelar") } },
            )
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChatRow(
    vm: ChatViewModel,
    row: ChatRow,
    isDragging: Boolean,
    isHover: Boolean,
    onClick: () -> Unit,
    onContextMenu: (Offset) -> Unit,
    onBounds: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val bg = when {
        row.active -> MaterialTheme.colorScheme.primaryContainer
        isHover -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    // Origen de la fila en coordenadas de raíz: sirve para convertir la
    // posición local del gesto de arrastre a coordenadas globales.
    var origin by remember(row.id) { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val r = coords.boundsInRoot()
                origin = r.topLeft
                onBounds(r)
            }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (e.button == PointerButton.Secondary) {
                    // En coordenadas de raíz, para que el sidebar las convierta.
                    onContextMenu(origin + e.changes.first().position)
                }
            }
            .pointerInput(row.id) {
                detectDragGestures(
                    onDragStart = { offset -> onDragStart(origin + offset) },
                    onDrag = { change, _ -> onDragMove(origin + change.position) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .clickable(onClick = onClick)
            .background(bg, RoundedCornerShape(8.dp))
            .alpha(if (isDragging) 0.35f else 1f)
            .padding(start = 8.dp + 14.dp * row.depth, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.isFolder) {
            Icon(
                if (row.expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
        }
        Text(
            row.name,
            Modifier.weight(1f),
            fontSize = 13.sp,
            maxLines = 1,
            color = if (row.active) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        )
    }
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

// ---------------------------------------------------------------------------
// Burbuja de mensaje.
// ---------------------------------------------------------------------------

@Composable
fun MessageBubble(
    m: UiMessage,
    editable: Boolean,
    editableNow: Boolean,
    editText: String,
    onEditTextChange: (String) -> Unit,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
) {
    val isUser = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 700.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (editableNow) {
                    // Modo edición: se reemplaza el texto por un campo + acciones.
                    OutlinedTextField(
                        value = editText,
                        onValueChange = onEditTextChange,
                        modifier = Modifier.widthIn(max = 700.dp),
                        minLines = 2,
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSaveEdit, enabled = editText.isNotBlank()) {
                            Text("Guardar y reenviar")
                        }
                        OutlinedButton(onClick = onCancelEdit) { Text("Cancelar") }
                    }
                } else {
                    // Selección limitada al texto (no a los botones): evita el
                    // crash "ActiveParent with no focused child" al hacer clic
                    // en Editar/Borrar dentro de un contenedor de selección.
                    SelectionContainer {
                        Column {
                            Text(m.text.ifEmpty { "…" })
                            if (m.sources.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Fuentes:", fontSize = 11.sp)
                                m.sources.forEachIndexed { i, s -> Text("[${i + 1}] $s", fontSize = 11.sp) }
                            }
                        }
                    }
                    // Acciones del mensaje: Copiar (cualquier mensaje, sobre todo
                    // la respuesta de la IA), Editar/Borrar (solo usuario) y
                    // Reiniciar (solo sobre la última respuesta entregada).
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCopy) {
                            Text("Copiar", fontSize = 11.sp)
                        }
                        if (isUser && editable) {
                            TextButton(onClick = onEdit) {
                                Text("Editar", fontSize = 11.sp)
                            }
                        }
                        if (editable) {
                            TextButton(onClick = onDelete) {
                                Text("Borrar", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (onRegenerate != null) {
                            TextButton(onClick = onRegenerate) {
                                Text("Reiniciar", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
