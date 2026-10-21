package app.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra de envío: campo de texto, botón (Enviar / Detener / Cancelar envío),
 * el micrófono del dictado y la cola de mensajes escritos mientras la IA
 * respondía.
 *
 * El texto vive en el ViewModel (`composerInput`) para que la tecla Enter, el
 * botón y el **dictado por voz** compartan exactamente el mismo camino de
 * envío.
 */
@Composable
internal fun ComposerBar(
    vm: ChatViewModel,
    focusRequester: FocusRequester,
) {
    fun submit() {
        vm.send(vm.composerInput)
        vm.setComposerInput("")
        // El foco se queda en el redactor: si la IA está respondiendo, el
        // mensaje se encola y se podrá seguir escribiendo.
        if (vm.ready) focusRequester.requestFocus()
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = vm.composerInput,
                    onValueChange = vm::setComposerInput,
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
                Spacer(Modifier.width(4.dp))
                // Micrófono: dicta el texto al redactor (no lo envía solo).
                DictationButton(vm)
                Spacer(Modifier.width(4.dp))
                if (vm.busy) {
                    Button(onClick = vm::cancel) { Text("Detener") }
                } else if (vm.queued) {
                    Button(onClick = vm::cancel) { Text("Cancelar envío") }
                } else {
                    Button(onClick = ::submit, enabled = vm.ready && vm.composerInput.isNotBlank()) { Text("Enviar") }
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
