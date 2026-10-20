package app.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.humanSize

/**
 * Pantalla de carga del arranque con progreso REAL por fases:
 * indexado de docs → descarga del modelo (%, MB) → clonado/compilación de
 * llama-server (%, sólo si falta) → carga del modelo en memoria (%).
 */
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
        Text(vm.status, fontSize = 13.sp, textAlign = TextAlign.Center)

        Spacer(Modifier.height(20.dp))
        if (p?.percent != null) {
            val pct = p.percent.coerceIn(0, 100)
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("${p.label} — $pct%${sizeSuffix(p.doneBytes, p.totalBytes)}", fontSize = 12.sp)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            val detail = if (p != null) humanSize(p.doneBytes) else null
            Text(
                listOfNotNull(p?.label, detail).joinToString(" — ").ifBlank { "Trabajando…" },
                fontSize = 12.sp,
            )
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

private fun sizeSuffix(done: Long, total: Long): String = when {
    total > 0 -> " · ${humanSize(done)} / ${humanSize(total)}"
    done > 0 -> " · ${humanSize(done)}"
    else -> ""
}
