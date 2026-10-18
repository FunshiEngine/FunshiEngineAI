package app

import androidx.compose.foundation.background
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
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatScreen(vm: ChatViewModel) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // NavegaciÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â³n superior
        TabRow(selectedTabIndex = vm.screen.ordinal.coerceIn(0, 3)) {
            Tab(selected = vm.screen == Screen.CHAT, onClick = { vm.go(Screen.CHAT) }, text = { Text("Chat") })
            Tab(selected = vm.screen == Screen.MODELS, onClick = { vm.go(Screen.MODELS) }, text = { Text("Modelos") })
            Tab(selected = vm.screen == Screen.APIS, onClick = { vm.go(Screen.APIS) }, text = { Text("APIs") })
            Tab(selected = vm.screen == Screen.LOADING, onClick = { }, enabled = false, text = { Text("Estado") })
        }
        if (vm.error.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.error, Modifier.weight(1f), fontSize = 12.sp, maxLines = 4)
                    TextButton(onClick = vm::dismissError) { Text("OK") }
                }
            }
        }
        when (vm.screen) {
            Screen.LOADING -> LoadingPane(vm)
            Screen.CHAT -> ChatPane(vm)
            Screen.MODELS -> ModelsPane(vm)
            Screen.APIS -> ApisPane(vm)
        }
    }
}


/** Pantalla de carga: clonar/compilar/descargar/arrancar con barra. */
@Composable
fun LoadingPane(vm: ChatViewModel) {
    val p = vm.progress
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Preparando el asistenteÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(vm.status, fontSize = 13.sp, maxLines = 3)
        Spacer(Modifier.height(16.dp))
        if (p?.percent != null) {
            LinearProgressIndicator(progress = { (p.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${p.label} ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã‚Â ${p.percent}%${bytesSuffix(p)}", fontSize = 12.sp)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(p?.label ?: "TrabajandoÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦", fontSize = 12.sp)
        }
        if (vm.busy && p?.label?.startsWith("Descargando") == true) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = vm::cancelDownload) { Text("Cancelar descarga") }
        }
        if (vm.error.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::retryBoot) { Text("Reintentar") }
        }
    }
}

fun bytesSuffix(p: UiProgress): String =
    if (p.totalBytes > 0) " (${p.doneBytes shr 20} / ${p.totalBytes shr 20} MB)" else ""

/** Chat bÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¡sico con cancelar y reindexar. */
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
        if (!vm.ready) LinearProgressIndicator(Modifier.fillMaxWidth())

        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(vm.messages) { MessageBubble(it) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                        submit(); true
                    } else false
                },
                placeholder = { Text(if (vm.ready) "Pregunta sobre la documentaciÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â³nÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦ (Enter envÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â­a, Shift+Enter nueva lÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â­nea)" else "PreparandoÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦") },
                enabled = vm.ready && !vm.busy,
                maxLines = 5
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 700.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(m.text.ifEmpty { "ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â¦" })
                if (m.sources.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Fuentes:", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    m.sources.forEachIndexed { i, s -> Text("[${i + 1}] $s", fontSize = 11.sp) }
                }
            }
        }
    }
}
