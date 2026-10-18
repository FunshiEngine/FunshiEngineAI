package app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val vm = remember { ChatViewModel() }
    Window(
        onCloseRequest = { vm.shutdown(); exitApplication() },
        title = "Docs Chatbot",
        state = rememberWindowState(width = 950.dp, height = 720.dp),
    ) {
        // Tema dinámico: se lee el estado del ViewModel directamente en cada
        // composition; cualquier cambio (setTheme) repinta todo al instante.
        val scheme = if (vm.isDark) {
            darkColorScheme(primary = Color(vm.accentColor))
        } else {
            lightColorScheme(primary = Color(vm.accentColor))
        }
        MaterialTheme(colorScheme = scheme) {
            ChatScreen(vm)
        }
    }
}