package app.screens

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
import app.chat.ChatViewModel

@Composable
fun themeOption(
    vm: ChatViewModel,
    label: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val selected = vm.isDark == isDark
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val borderWidth = if (selected) 2.dp else 0.dp
    Card(
        modifier = modifier
            // Llamada DIRECTA al ViewModel: antes pasaba por la cadena
            // SettingsPane → ChatScreen → Main (onThemeChange) y si un eslabón
            // no estaba conectado el click no hacía nada visible.
            .clickable { vm.setTheme(isDark, vm.accentColor) }
            .border(borderWidth, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RadioGroup(
    selected: String,
    onChange: (String) -> Unit,
    options: List<Pair<String, String>>,
    labelFormatter: (Pair<String, String>) -> String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = selected == value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onChange(value) }
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isSelected) "●" else "○",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(labelFormatter(value to label), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}