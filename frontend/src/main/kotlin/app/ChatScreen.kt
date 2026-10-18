package app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Pantalla principal: sidebar a la izquierda + contenido a la derecha. */
@Composable
fun ChatScreen(vm: ChatViewModel) {
    // Row (no Column): el sidebar queda a la IZQUIERDA y el contenido ocupa el
    // resto del ancho/alto. Con Column el rail se apilaba encima y el contenido
    // (incluida la barra de envío del chat) quedaba aplastado contra el fondo.
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavigationRailPane(vm, onNavigate = { vm.go(it) })

        Column(
            Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Error global: visible en CUALQUIER pantalla (antes sólo aparecía en
            // la de carga, así que un fallo de descarga en Modelos pasaba inadvertido).
            if (vm.error.isNotEmpty()) {
                ErrorBanner(vm)
                Spacer(Modifier.height(8.dp))
            }
            // Banner de progreso: mientras se prepara el modelo, o durante una
            // descarga lanzada desde Modelos aunque el chat ya esté listo.
            if ((!vm.ready || vm.progress != null) && vm.screen == Screen.CHAT) {
                ModelLoadingBanner(vm)
                Spacer(Modifier.height(8.dp))
            }

            when (vm.screen) {
                Screen.CHAT -> ChatPane(vm)
                Screen.MODELS -> ModelsPane(vm)
                Screen.APIS -> ApisPane(vm)
                Screen.SETTINGS -> SettingsPane(vm)
                Screen.LOADING -> LoadingPane(vm) // Carga con progreso por fases
            }
        }
    }
}

/** Banner de error descartable (fallo de descarga, API mal configurada, etc.). */
@Composable
fun ErrorBanner(vm: ChatViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠ ${vm.error}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 4,
            )
            TextButton(onClick = vm::dismissError) { Text("Cerrar") }
        }
    }
}

/** Banner superior con el progreso real: fase, porcentaje, barra y MB. */
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
                Column(Modifier.weight(1f)) {
                    Text(
                        p?.label ?: "Preparando el asistente…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        vm.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                    )
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
            Spacer(Modifier.height(8.dp))
            if (p?.percent != null) {
                LinearProgressIndicator(
                    progress = { (p.percent ?: 0).coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
            }
        }
    }
}
