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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Barra lateral fija (72dp) con navegación principal (solo texto, sin iconos). */
@Composable
fun NavigationRailPane(
    vm: ChatViewModel,
    onNavigate: (Screen) -> Unit,
) {
    Column(
        Modifier
            .width(72.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(0.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "AI",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))

        railItem("Chat", Screen.CHAT, vm, onNavigate)
        railItem("Modelos", Screen.MODELS, vm, onNavigate)
        railItem("APIs", Screen.APIS, vm, onNavigate)

        Spacer(Modifier.weight(1f))

        railItem("Config", Screen.SETTINGS, vm, onNavigate)
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
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .clickable { onNavigate(screen) }
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}