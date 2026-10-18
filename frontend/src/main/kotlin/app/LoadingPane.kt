package app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

private fun bytesSuffix(p: UiProgress): String =
    if (p.totalBytes > 0) " (${p.doneBytes shr 20} / ${p.totalBytes shr 20} MB)" else ""