package app.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.UiMessage
import app.ui.MarkdownText

/**
 * Burbuja de un mensaje del chat.
 *
 * Las respuestas del modelo se renderizan como markdown ([MarkdownText]); los
 * mensajes del usuario se muestran en texto plano (lo que se escribe no se
 * interpreta como markdown).
 */
@Composable
internal fun MessageBubble(
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
                            // Tarjetas de herramientas usadas en esta respuesta
                            // (F1: tool calling). Antes del texto del mensaje.
                            m.toolCalls.forEach { tc -> ToolCallCard(tc) }
                            if (m.role == "assistant") {
                                MarkdownText(text = m.text.ifEmpty { "…" })
                            } else {
                                Text(m.text.ifEmpty { "…" })
                            }
                            if (m.sources.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Fuentes:", fontSize = 11.sp)
                                m.sources.forEachIndexed { i, s -> Text("[${i + 1}] $s", fontSize = 11.sp) }
                            }
                            // Archivos que el modelo creó o descargó (F3).
                            if (m.attachments.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                m.attachments.forEach { a -> AttachmentCard(a) }
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
