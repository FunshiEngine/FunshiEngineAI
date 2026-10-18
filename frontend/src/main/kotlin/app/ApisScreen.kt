package app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import llm.ApiPreset

/** Colección de APIs OpenAI-compatibles: guardar, activar y volver a local. */
@Composable
fun ApisPane(vm: ChatViewModel) {
    var label by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Las APIs activas usan el modo REMOTO. Volver a local reinicia llama-server.", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = vm::useLocal, enabled = !vm.busy) { Text("Volver a modelo local") }
        }
        items(vm.apiPresets, key = { it.id }) { preset ->
            ApiCard(vm, preset)
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Agregar API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Nombre (ej. Mi Ollama)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("URL base (ej. http://localhost:11434/v1)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Modelo (ej. phi4-mini)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Clave (vacío = sin clave)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.addApi(label, baseUrl, apiKey, model)
                    label = ""
                    baseUrl = ""
                    apiKey = ""
                    model = ""
                },
                enabled = label.isNotBlank() && baseUrl.isNotBlank(),
            ) { Text("Guardar API") }
        }
    }
}

@Composable
private fun ApiCard(vm: ChatViewModel, preset: ApiPreset) {
    val isActive = vm.activeApiId == preset.id
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(preset.label, style = MaterialTheme.typography.titleSmall)
            Text(preset.baseUrl, fontSize = 11.sp, maxLines = 1)
            if (preset.model.isNotBlank()) Text("Modelo: ${preset.model}", fontSize = 12.sp)
            if (isActive) Text("Activa", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    Button(onClick = { vm.activateApi(preset) }, enabled = !vm.busy) { Text("Activar") }
                }
                OutlinedButton(onClick = { vm.removeApi(preset.id) }, enabled = !vm.busy) { Text("Quitar") }
            }
        }
    }
}