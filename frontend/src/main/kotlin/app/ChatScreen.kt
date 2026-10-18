package app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
            when (vm.screen) {
                Screen.LOADING -> LoadingPane(vm)
                Screen.CHAT -> ChatPane(vm)
                Screen.MODELS -> ModelsPane(vm)
                Screen.APIS -> ApisPane(vm)
                Screen.SETTINGS -> SettingsPane(vm, onThemeChange)
            }
        }
    }
}