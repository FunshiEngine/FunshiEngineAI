package app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import llm.ModelEntry

/** Gestor de modelos locales: catálogo, descarga con barra, activar y agregar de HF. */
@Composable
fun ModelsPane(vm: ChatViewModel) {
    var repo by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var repoFiles by remember { mutableStateOf(listOf<String>()) }
    var listing by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Modelo activo: ${vm.activeModelLabel}", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val p = vm.progress
            if (p != null) {
                Text(p.label, fontSize = 12.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                if (p.percent != null) {
                    LinearProgressIndicator(progress = { (p.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${p.percent}%${if (p.totalBytes > 0) " (${p.doneBytes shr 20} / ${p.totalBytes shr 20} MB)" else ""}", fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (vm.busy) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = vm::cancelDownload) { Text("Cancelar descarga") }
                }
            }
        }
        items(vm.modelEntries, key = { it.preset.id }) { entry ->
            ModelCard(vm, entry)
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Agregar modelo de Hugging Face", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = repo,
                onValueChange = { repo = it },
                label = { Text("Repo (ej. unsloth/Phi-4-mini-instruct-GGUF)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        listing = true
                        vm.listRepoFiles(repo) { files -> repoFiles = files; listing = false }
                    },
                    enabled = repo.isNotBlank() && !listing,
                ) { Text(if (listing) "Buscando…" else "Listar .gguf") }
            }
            if (repoFiles.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Archivos del repo (toca para elegir):", fontSize = 12.sp)
                repoFiles.take(20).forEach { name ->
                    TextButton(onClick = { file = name }) { Text(name, fontSize = 12.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = file,
                onValueChange = { file = it },
                label = { Text("Archivo (ej. modelo-Q4_K_M.gguf)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Etiqueta (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.addModel(repo, file, label); file = "" },
                enabled = repo.isNotBlank() && file.isNotBlank(),
            ) { Text("Agregar al catálogo") }
        }
    }
}

@Composable
private fun ModelCard(vm: ChatViewModel, entry: ModelEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.preset.label, style = MaterialTheme.typography.titleSmall)
            Text("${entry.preset.repo} / ${entry.preset.file}", fontSize = 11.sp, maxLines = 2)
            if (entry.preset.description.isNotBlank()) {
                Text(entry.preset.description, fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            val state = when {
                entry.active -> "Activo"
                entry.downloaded -> "Descargado (${entry.sizeBytes shr 20} MB)"
                else -> "No descargado${if (entry.preset.sizeGb > 0) " (~${entry.preset.sizeGb} GB)" else ""}"
            }
            Text(state, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.downloaded) {
                    Button(onClick = { vm.downloadModel(entry) }, enabled = !vm.busy) { Text("Descargar") }
                } else if (!entry.active) {
                    Button(onClick = { vm.activateModel(entry) }, enabled = !vm.busy) { Text("Activar") }
                }
                if (!entry.active) {
                    OutlinedButton(onClick = { vm.removeModel(entry.preset.id) }, enabled = !vm.busy) { Text("Quitar") }
                }
            }
        }
    }
}