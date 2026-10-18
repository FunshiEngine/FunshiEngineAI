package app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import llm.ModelEntry

/** Barra lateral con navegación y ajustes de tema/color. */
@Composable
fun NavigationRailPane(
    vm: ChatViewModel,
    onThemeChange: (AppTheme) -> Unit,
) {
    Column(
        Modifier
            .width(72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "AI",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))

        navItem(emoji = "💬", label = "Chat", screen = Screen.CHAT, vm = vm)
        navItem(emoji = "📦", label = "Modelos", screen = Screen.MODELS, vm = vm)
        navItem(emoji = "🔌", label = "APIs", screen = Screen.APIS, vm = vm)

        Spacer(Modifier.weight(1f))

        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tema", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val items = listOf("☀️" to false, "🌙" to true)
                items.forEachIndexed { index, pair ->
                    val (emoji, isDark) = pair
                    IconButton(
                        onClick = { onThemeChange(AppTheme(dark = isDark, accent = vm.accentColor)) },
                    ) { Text(emoji, fontSize = 20.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Color de acento", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0xFF6200EE.toInt(), 0xFF03DAC6.toInt(), 0xFFCF6679.toInt(), 0xFFFDD835.toInt()).forEach { color ->
                    val hex = String.format("#%06X", color and 0xFFFFFF)
                    Box(
                        Modifier
                            .size(20.dp)
                            .clickable { onThemeChange(AppTheme(dark = vm.isDark, accent = color)) }
                            .border(if (vm.accentColor == color) 2.dp else 0.dp, Color(color), RoundedCornerShape(10.dp))
                            .background(Color(color)),
                    )
                }
            }
        }
    }
}

@Composable
private fun navItem(
    emoji: String,
    label: String,
    screen: Screen,
    vm: ChatViewModel,
) {
    val selected = vm.screen == screen
    IconButton(
        onClick = { vm.go(screen) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(emoji, fontSize = 20.sp)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** Pantalla principal: zona de contenido (a la derecha de la barra lateral). */
@Composable
fun ChatScreen(vm: ChatViewModel, onThemeChange: (AppTheme) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRailPane(vm, onThemeChange)

        Column(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            when (vm.screen) {
                Screen.LOADING -> LoadingPane(vm)
                Screen.CHAT -> ChatPane(vm)
                Screen.MODELS -> ModelsPane(vm)
                Screen.APIS -> ApisPane(vm)

            }
        }
    }
}

/** Pantalla de carga: clonar/compilar/descargar/arrancar con barra y botón Reintentar. */
/** Pantalla de carga: clonar/compilar/descargar/arrancar con barra y botón Reintentar. */
@Composable
fun LoadingPane(vm: ChatViewModel) {
    val p = vm.progress
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Preparando el asistente…", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(vm.status, fontSize = 13.sp, maxLines = 3)
        Spacer(Modifier.height(16.dp))
        if (p?.percent != null) {
            val frac = (p.percent ?: 0).coerceIn(0, 100) / 100f
            LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("${p.label} — ${p.percent}%${bytesSuffix(p)}", fontSize = 12.sp)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(p?.label ?: "Trabajando…", fontSize = 12.sp)
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
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
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
private fun bytesSuffix(p: UiProgress): String =
    if (p.totalBytes > 0) " (${p.doneBytes shr 20} / ${p.totalBytes shr 20} MB)" else ""
