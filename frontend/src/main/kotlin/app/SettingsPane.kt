package app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pantalla de Configuración: tema (claro/oscuro), color de acento, modo (Local/API). */
@Composable
fun SettingsPane(vm: ChatViewModel, onThemeChange: (AppTheme) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Sección: Tema
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Apariencia", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))

                Text("Tema", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    themeOption(vm, "Claro", false, onThemeChange)
                    themeOption(vm, "Oscuro", true, onThemeChange)
                }
                Spacer(Modifier.height(16.dp))

                Text("Color de acento", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFF6200EE.toInt(), 0xFF03DAC6.toInt(), 0xFFCF6679.toInt(), 0xFFFDD835.toInt()).forEach { color ->
                        val selected = vm.accentColor == color
                        Box(
                            Modifier
                                .size(40.dp)
                                .clickable { onThemeChange(AppTheme(dark = vm.isDark, accent = color)) }
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                .background(Color(color), RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }

        // Sección: Modo de inferencia (Local vs API)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Motor de inferencia", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))

                val mode = vm.configMode
                RadioGroup(
                    selected = mode,
                    onChange = { newMode -> vm.setConfigMode(newMode) },
                    options = listOf(
                        "LOCAL" to "Modelo local (llama.cpp)",
                        "REMOTE" to "API remota (OpenAI-compatible)",
                    ),
                    labelFormatter = { it.second },
                )
                Spacer(Modifier.height(16.dp))

                if (mode == "REMOTE") {
                    Text("API activa: ${vm.activeApiId ?: "ninguna"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Cambia de API en la pestaña APIs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Modelo local activo: ${vm.activeModelLabel}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Cambia de modelo en la pestaña Modelos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Versión / info
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Acerca de", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("FunshiEngineAI — Asistente RAG local", style = MaterialTheme.typography.bodyMedium)
                Text("Versión 0.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}