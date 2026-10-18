package app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import llm.HfGgufFile
import llm.HfRepoHit
import llm.ModelEntry

/**
 * Gestor de modelos locales. Orden pensado para no perderse:
 *  1) BUSCADOR de Hugging Face (arriba) → 2) modelo activo + progreso real
 *  (%, barra, MB) → 3) catálogo con peso y estado → 4) alta manual.
 * Todo con scrollbar vertical propio.
 */
@Composable
fun ModelsPane(vm: ChatViewModel) {
    val scroll = rememberScrollState()

    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<HfRepoHit>>(emptyList()) }
    var repo by remember { mutableStateOf("") }
    var file by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    // Acordeón por resultado: VARIOS repos pueden estar expandidos a la vez y
    // cada uno tiene sus propios archivos (mapa repo → archivos).
    var expandedRepos by remember { mutableStateOf(setOf<String>()) }
    var files by remember { mutableStateOf(mapOf<String, List<HfGgufFile>>()) }
    var listing by remember { mutableStateOf(setOf<String>()) }

    /** Abre/cierra el acordeón de un repo y le pide sus .gguf si faltan. */
    fun pickRepo(id: String) {
        if (id in expandedRepos) {
            expandedRepos = expandedRepos - id
            return
        }
        expandedRepos = expandedRepos + id
        if (repo != id) {
            repo = id
            file = ""
        }
        if (files[id] == null && id !in listing) {
            listing = listing + id
            vm.listRepoFiles(id) { list ->
                files = files + (id to list)
                listing = listing - id
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ---------- 1. BUSCADOR (parte superior) ----------
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Buscar modelos en Hugging Face", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Buscá por nombre, tocá un resultado y elegí el archivo .gguf con su peso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Buscar (ej. phi-4, qwen3, llama-3.2, mistral)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                searching = true
                                vm.searchModels(query) { results = it; searching = false }
                            },
                            enabled = query.isNotBlank() && !searching && listing.isEmpty(),
                        ) { Text(if (searching) "Buscando…" else "Buscar") }
                        OutlinedButton(
                            onClick = { pickRepo(repo) },
                            enabled = repo.isNotBlank() && repo !in listing,
                        ) { Text(if (repo in listing) "Listando…" else "Listar .gguf del repo") }
                    }

                    if (results.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Resultados (${results.size})", style = MaterialTheme.typography.labelMedium)
                        // Cada resultado es un acordeón: varios expandidos a la vez,
                        // con sus archivos DENTRO del propio resultado.
                        results.forEach { hit ->
                            val expanded = hit.id in expandedRepos
                            Column(Modifier.animateContentSize()) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { pickRepo(hit.id) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(hit.id, fontSize = 13.sp, maxLines = 1)
                                        Text(
                                            "${hit.downloads} descargas",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { pickRepo(hit.id) }) {
                                        Text(if (expanded) "Ocultar archivos" else "Ver archivos")
                                    }
                                }
                                AnimatedVisibility(expanded) {
                                    RepoFilesList(
                                        files = files[hit.id].orEmpty(),
                                        loading = hit.id in listing,
                                        selectedFile = if (repo == hit.id) file else null,
                                        onSelect = { f ->
                                            repo = hit.id
                                            file = f.name
                                            if (label.isBlank()) label = f.display
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Resumen de la selección: NO duplica la lista de archivos
                    // (esa vive en el acordeón de cada resultado).
                    if (repo.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Repo elegido: $repo", fontSize = 12.sp, maxLines = 1)
                        if (file.isNotBlank()) {
                            Text(
                                "Archivo: $file",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // ---------- 2. MODELO ACTIVO + PROGRESO ----------
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Modelo activo: ${vm.activeModelLabel}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    val p = vm.progress
                    if (p != null) {
                        Text(p.label, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        if (p.percent != null) {
                            LinearProgressIndicator(
                                progress = { p.percent.coerceIn(0, 100) / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(progressText(p), fontSize = 12.sp)
                        if (vm.busy) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = vm::cancelDownload) { Text("Cancelar descarga") }
                        }
                    } else {
                        Text(vm.status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ---------- 3. CATÁLOGO ----------
            Text("Catálogo", style = MaterialTheme.typography.titleMedium)
            vm.modelEntries.forEach { entry -> ModelCard(vm, entry) }

            // ---------- 4. ALTA MANUAL / DESDE EL BUSCADOR ----------
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Agregar modelo al catálogo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Elegí un archivo del buscador de arriba (se completa solo) o pegá repo y archivo a mano.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repo,
                        onValueChange = { repo = it },
                        label = { Text("Repo (ej. unsloth/Phi-4-mini-instruct-GGUF)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = file,
                        onValueChange = { file = it },
                        label = { Text("Archivo (ej. Phi-4-mini-instruct-Q4_K_M.gguf)") },
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
                        onClick = {
                            vm.addModel(repo, file, label)
                            file = ""
                            label = ""
                        },
                        enabled = repo.isNotBlank() && file.isNotBlank(),
                    ) { Text("Agregar al catálogo") }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * Lista de .gguf de UN repo, mostrada dentro de su acordeón. Muestra estado de
 * carga, el vacío, o los archivos con nombre + tamaño y el seleccionado.
 */
@Composable
private fun RepoFilesList(
    files: List<HfGgufFile>,
    loading: Boolean,
    selectedFile: String?,
    onSelect: (HfGgufFile) -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        when {
            loading -> Text(
                "Listando archivos…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )

            files.isEmpty() -> Text(
                "Sin archivos .gguf en este repo.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )

            else -> files.forEach { f ->
                val selected = selectedFile == f.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(f) }
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.display, fontSize = 12.sp, maxLines = 1)
                        Text(
                            humanSize(f.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) Text("✔", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Texto del progreso: porcentaje + peso bajado / peso total. */
private fun progressText(p: UiProgress): String = buildString {
    p.percent?.let { append("$it%") }
    if (p.totalBytes > 0) {
        if (isNotEmpty()) append(" · ")
        append("${humanSize(p.doneBytes)} / ${humanSize(p.totalBytes)}")
    } else if (p.doneBytes > 0) {
        if (isNotEmpty()) append(" · ")
        append(humanSize(p.doneBytes))
    }
}.ifBlank { "Trabajando…" }

@Composable
private fun ModelCard(vm: ChatViewModel, entry: ModelEntry) {
    val size = when {
        entry.downloaded -> humanSize(entry.sizeBytes)
        entry.partBytes > 0 -> "${humanSize(entry.partBytes)} de ${humanSize(entry.expectedBytes)}"
        entry.preset.sizeGb > 0 -> "~${entry.preset.sizeGb} GB"
        else -> "peso desconocido"
    }
    val state = when {
        entry.active -> "Activo · $size"
        entry.downloaded -> "Descargado · $size"
        entry.partBytes > 0 -> "Descarga parcial · $size"
        else -> "No descargado · $size"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(entry.preset.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "${entry.preset.repo} / ${entry.preset.file}",
                fontSize = 11.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.preset.description.isNotBlank()) {
                Text(entry.preset.description, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                state,
                fontSize = 12.sp,
                color = if (entry.active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.downloaded) {
                    Button(onClick = { vm.downloadModel(entry, activateAfter = true) }, enabled = !vm.busy) {
                        Text("Descargar y usar")
                    }
                    TextButton(onClick = { vm.downloadModel(entry) }, enabled = !vm.busy) {
                        Text(if (entry.partBytes > 0) "Reanudar" else "Descargar", fontSize = 12.sp)
                    }
                } else if (!entry.active) {
                    Button(onClick = { vm.activateModel(entry) }, enabled = !vm.busy) { Text("Usar") }
                }
                if (!entry.active) {
                    OutlinedButton(onClick = { vm.removeModel(entry.preset.id) }, enabled = !vm.busy) { Text("Quitar") }
                }
            }
        }
    }
}
