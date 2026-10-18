package app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Chat básico con cancelar y reindexar. */
@Composable
fun ChatPane(vm: ChatViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text?.length) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
    }

    fun submit() {
        vm.send(input)
        input = ""
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(vm.status, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2)
                TextButton(onClick = vm::reindex, enabled = vm.ready && !vm.busy) { Text("Reindexar docs") }
            }
        }
        if (!vm.ready) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                items(vm.messages) { MessageBubble(it) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.key == androidx.compose.ui.input.key.Key.Enter && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && !e.isShiftPressed) {
                        submit(); true
                    } else false
                },
                placeholder = { Text(if (vm.ready) "Pregunta sobre la documentación… (Enter envía, Shift+Enter nueva línea)" else "Preparando…") },
                enabled = vm.ready && !vm.busy,
                maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            if (vm.busy) {
                Button(onClick = vm::cancel) { Text("Detener") }
            } else {
                Button(onClick = ::submit, enabled = vm.ready && input.isNotBlank()) { Text("Enviar") }
            }
        }
    }
}

@Composable
fun MessageBubble(m: UiMessage) {
    val isUser = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) androidx.compose.foundation.layout.Arrangement.End else androidx.compose.foundation.layout.Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 700.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(m.text.ifEmpty { "…" })
                if (m.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Fuentes:", fontSize = 11.sp)
                    m.sources.forEachIndexed { i, s -> Text("[${i + 1}] $s", fontSize = 11.sp) }
                }
            }
        }
    }
}