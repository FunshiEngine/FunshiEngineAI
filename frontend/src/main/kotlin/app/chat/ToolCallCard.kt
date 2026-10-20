package app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.UiToolCall

/**
 * Tarjeta de una herramienta ejecutada por el modelo (F1): nombre + argumentos
 * + estado (en curso / OK / Falló) y el resultado, colapsado a pocas líneas.
 * Sin emojis: los estados son texto.
 */
@Composable
internal fun ToolCallCard(call: UiToolCall) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Herramienta: ${call.name}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            if (call.args.isNotBlank() && call.args != "{}") {
                Text(
                    call.args,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            when (call.ok) {
                null -> Text("Ejecutando…", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                true -> {
                    Text("OK", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                    if (call.result.isNotBlank()) {
                        Text(
                            call.result,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                        )
                    }
                }
                false -> Text("Falló", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
