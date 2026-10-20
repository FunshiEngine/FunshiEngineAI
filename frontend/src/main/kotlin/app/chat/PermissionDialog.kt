package app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest

/**
 * Diálogo de permiso de herramienta (F3).
 *
 * Aparece cuando el modelo pide una herramienta de una categoría que el
 * usuario todavía no concedió en este chat. Tres respuestas:
 *  · **Permitir siempre** — ejecuta y concede la categoría en el chat.
 *  · **Una vez** — ejecuta sólo esta llamada.
 *  · **Cancelar** — no ejecuta; el modelo recibe el feedback y sigue.
 */
@Composable
internal fun PermissionDialog(vm: ChatViewModel) {
    val req: PermissionRequest = vm.permissionRequest ?: return
    AlertDialog(
        onDismissRequest = { vm.answerPermission(PermissionDecision.DENY) },
        title = { Text("Permiso: ${req.category.label}") },
        text = {
            Column {
                Text("El modelo quiere usar la herramienta `${req.tool}`.")
                Spacer(Modifier.height(6.dp))
                Text(req.summary, fontSize = 12.sp)
                if (req.argsJson.isNotBlank() && req.argsJson != "{}") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        req.argsJson,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.answerPermission(PermissionDecision.ALLOW_ALWAYS) }) {
                Text("Permitir siempre")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.answerPermission(PermissionDecision.ALLOW_ONCE) }) {
                    Text("Una vez")
                }
                TextButton(onClick = { vm.answerPermission(PermissionDecision.DENY) }) {
                    Text("Cancelar")
                }
            }
        },
    )
}
