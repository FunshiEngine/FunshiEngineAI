package app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chat.ChatViewModel
import app.chat.addMcpServer
import app.chat.removeMcpServer
import app.chat.setLatexEngine
import app.chat.setLatexTimeoutSeconds
import app.chat.setMaxDownloadMb
import app.chat.setMaxFileMb
import app.chat.setOutputsDir
import app.chat.setToolTimeoutSeconds
import app.chat.setToolsEnabled
import app.chat.updateMcpServer
import rag.mcp.McpServerConfig

/**
 * Ajustes de herramientas (F7).
 *
 * Tres bloques, que son las tres secciones nuevas de la config (`tools`,
 * `outputs` y `latex`) más los servidores MCP (`mcp`). Los topes y el motor
 * de LaTeX salen de acá: antes eran constantes en el código.
 */
@Composable
fun ToolsSettings(vm: ChatViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Herramientas", style = MaterialTheme.typography.titleMedium)
            Text(
                "Las herramientas que puede usar el modelo. Los permisos se conceden por chat " +
                    "(`/permitir-archivos`, `/permitir-web`, `/permitir-mcp`).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // ---------- Interruptor general ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Herramientas activadas", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Apagado, el modelo no recibe ninguna herramienta en su catálogo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vm.config.tools.enabled,
                    onCheckedChange = { vm.setToolsEnabled(it) },
                )
            }

            Spacer(Modifier.height(16.dp))
            NumberField(
                vm = vm,
                label = "Timeout por herramienta (segundos, 0 = sin límite)",
                value = vm.config.tools.timeoutMs / 1000,
                onValue = { vm.setToolTimeoutSeconds(it) },
            )

            Spacer(Modifier.height(16.dp))
            Text("Salidas", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TextField(
                label = "Carpeta de archivos (dentro de la carpeta de la app)",
                value = vm.config.outputs.dir,
                onValue = { vm.setOutputsDir(it) },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    vm = vm,
                    label = "Tope por archivo creado (MB)",
                    value = vm.config.outputs.maxFileMb.toLong(),
                    onValue = { vm.setMaxFileMb(it) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    vm = vm,
                    label = "Tope por descarga (MB)",
                    value = vm.config.outputs.maxDownloadMb.toLong(),
                    onValue = { vm.setMaxDownloadMb(it) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("LaTeX", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            TextField(
                label = "Motor (vacío = autodetectar: pdflatex, luego xelatex)",
                value = vm.config.latex.engine,
                onValue = { vm.setLatexEngine(it) },
            )
            Spacer(Modifier.height(8.dp))
            NumberField(
                vm = vm,
                label = "Timeout de compilación (segundos)",
                value = vm.config.latex.timeoutMs / 1000,
                onValue = { vm.setLatexTimeoutSeconds(it) },
            )

            Spacer(Modifier.height(16.dp))
            McpServers(vm)
        }
    }
}

/** Lista de servidores MCP declarados, con agregar y quitar. */
@Composable
private fun McpServers(vm: ChatViewModel) {
    val servidores = vm.config.mcp.servers
    Text("Servidores MCP", style = MaterialTheme.typography.titleSmall)
    Text(
        "Se declaran acá; el transporte MCP todavía no está implementado, así que quedan " +
            "declarados pero no conectados (el estado se ve con `/mcp`).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    servidores.forEachIndexed { index, server ->
        McpServerRow(vm, index, server)
        Spacer(Modifier.height(8.dp))
    }

    Button(onClick = { vm.addMcpServer() }) {
        Text("Agregar servidor MCP")
    }
}

/** Una fila editable de servidor MCP. */
@Composable
private fun McpServerRow(vm: ChatViewModel, index: Int, server: McpServerConfig) {
    // Los campos se editan sobre una copia: se guardan al perder el foco o al
    // cambiar el interruptor, para no escribir la config en cada tecla.
    var id by remember(server) { mutableStateOf(server.id) }
    var nombre by remember(server) { mutableStateOf(server.name) }
    var comando by remember(server) { mutableStateOf(server.command) }
    var url by remember(server) { mutableStateOf(server.url) }
    var habilitado by remember(server) { mutableStateOf(server.enabled) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (server.id.isBlank()) "Servidor nuevo" else server.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = habilitado,
                        onCheckedChange = {
                            habilitado = it
                            vm.updateMcpServer(index) { s ->
                                s.copy(enabled = it, id = id.trim(), name = nombre.trim())
                            }
                        },
                    )
                    IconButton(onClick = { vm.removeMcpServer(index) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Quitar servidor")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("Id") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = comando,
                onValueChange = { comando = it },
                label = { Text("Comando (transporte stdio)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL (transporte http)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                vm.updateMcpServer(index) {
                    it.copy(
                        id = id.trim(),
                        name = nombre.trim(),
                        command = comando.trim(),
                        url = url.trim(),
                        enabled = habilitado,
                    )
                }
            }) {
                Text("Guardar")
            }
        }
    }
}

/** Campo de texto de una línea. */
@Composable
private fun TextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Campo numérico: sólo guarda si el número es válido. */
@Composable
private fun NumberField(
    vm: ChatViewModel,
    label: String,
    value: Long,
    onValue: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var texto by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = texto,
        onValueChange = { nuevo ->
            texto = nuevo
            val n = nuevo.toLongOrNull()
            if (n != null && n >= 0) onValue(n)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
