package app.chat

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.ChatRow

// ---------------------------------------------------------------------------
// Sidebar de chats: árbol de carpetas con click derecho, renombrar y
// arrastre (drag & drop) para ordenar y anidar.
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ChatListSidebar(vm: ChatViewModel, modifier: Modifier = Modifier) {
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
                ChatRowItem(
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

        // Barra de desplazamiento de la lista de chats (visible con muchos chats).
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )

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

/** Fila del árbol de chats (chat o carpeta). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChatRowItem(
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
