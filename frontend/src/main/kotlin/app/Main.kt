package app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val vm = remember { ChatViewModel() }
    Window(
        onCloseRequest = { vm.shutdown(); exitApplication() },
        title = "Docs Chatbot",
        state = rememberWindowState(width = 950.dp, height = 720.dp)
    ) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
            ChatScreen(vm)
        }
    }
}
