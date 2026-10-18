package app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pantalla principal: sidebar + contenido. */
@Composable
fun ChatScreen(vm: ChatViewModel, onThemeChange: (AppTheme) -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRailPane(vm, onNavigate = { vm.go(it) })

        Column(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // Banner de estado si el modelo está cargando (pero chat visible)
            if (!vm.ready && vm.screen == Screen.CHAT) {
                ModelLoadingBanner(vm)
                Spacer(Modifier.height(8.dp))
            }
            
            when (vm.screen) {
                Screen.CHAT -> ChatPane(vm)
                Screen.MODELS -> ModelsPane(vm)
                Screen.APIS -> ApisPane(vm)
                Screen.SETTINGS -> SettingsPane(vm, onThemeChange)
                Screen.LOADING -> LoadingPane(vm) // Fallback, no debería usarse
            }
        }
    }
}

/** Banner superior informativo mientras el modelo carga en background. */
@Composable
fun ModelLoadingBanner(vm: ChatViewModel) {
    val p = vm.progress
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Modelo cargando en segundo plano…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (p != null) {
                        Text(
                            p.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            maxLines = 2,
                        )
                    }
                }
                if (vm.busy && p?.label?.startsWith("Descargando") == true) {
                    OutlinedButton(
                        onClick = vm::cancelDownload,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    ) { Text("Cancelar") }
                }
            }
            if (p?.percent != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (p.percent ?: 0).coerceIn(0, 100) / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
            }
        }
    }
}