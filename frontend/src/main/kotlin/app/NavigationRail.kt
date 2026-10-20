package app

import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chat.ChatViewModel
import app.core.Screen

/** Barra lateral con navegación principal, colapsable. */
@Composable
fun NavigationRailPane(
    vm: ChatViewModel,
    onNavigate: (Screen) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 200.dp else 72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp))
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        
        if (expanded) {
            Text(
                "FunshiEngineAI",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(16.dp))
        
        // Botón colapsar/expandir
        Text(
            text = if (expanded) "≡" else "☰",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable { expanded = !expanded }
                .background(Color.Transparent, RoundedCornerShape(8.dp)),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        if (expanded) {
            railItem("Chat", Screen.CHAT, vm, onNavigate)
            railItem("Modelos", Screen.MODELS, vm, onNavigate)
            railItem("Motor", Screen.APIS, vm, onNavigate)

            Spacer(Modifier.weight(1f))

            railItem("Config", Screen.SETTINGS, vm, onNavigate)
        } else {
            // Letras cuando está colapsado
            railItem("C", Screen.CHAT, vm, onNavigate)
            railItem("M", Screen.MODELS, vm, onNavigate)
            railItem("Mot", Screen.APIS, vm, onNavigate)

            Spacer(Modifier.weight(1f))

            railItem("⚙", Screen.SETTINGS, vm, onNavigate)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun railItem(
    label: String,
    screen: Screen,
    vm: ChatViewModel,
    onNavigate: (Screen) -> Unit,
) {
    val selected = vm.screen == screen
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable { onNavigate(screen) }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}