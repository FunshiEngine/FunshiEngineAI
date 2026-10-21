package app.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Barra de voz (V1): el estado del micrófono y del chat por voz.
 *
 * Aparece sólo cuando hay algo que contar (está escuchando, el chat por voz
 * está activo o hay un error): si no, no ocupa espacio.
 */
@Composable
internal fun VoiceBar(vm: ChatViewModel) {
    val show = vm.voiceActive || vm.voiceListening || vm.voiceSpeaking ||
        vm.voiceError.isNotBlank()
    if (!show) return

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        vm.voiceSpeaking -> Icons.Filled.RecordVoiceOver
                        vm.voiceListening -> Icons.Filled.Mic
                        else -> Icons.Filled.Stop
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (vm.voiceError.isBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = vm.voiceStatus.ifBlank {
                        if (vm.voiceError.isNotBlank()) "La voz no pudo continuar" else "Voz"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (vm.voiceActive) {
                    TextButton(onClick = vm::stopVoiceChat) { Text("Terminar", fontSize = 12.sp) }
                }
            }
            // Lo que se entendió: se ve mientras se piensa y mientras habla.
            if (vm.voiceTranscript.isNotBlank()) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "“${vm.voiceTranscript}”",
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vm.voiceError.isNotBlank()) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    vm.voiceError,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Botón del chat por voz: arranca o corta el bucle
 * `escuchar → responder → hablar`.
 */
@Composable
internal fun VoiceChatButton(vm: ChatViewModel) {
    TextButton(
        onClick = { if (vm.voiceActive) vm.stopVoiceChat() else vm.startVoiceChat() },
        enabled = vm.ready && !vm.busy,
    ) {
        Icon(
            imageVector = if (vm.voiceActive) Icons.Filled.Stop else Icons.Filled.RecordVoiceOver,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(if (vm.voiceActive) "Terminar voz" else "Chat por voz", fontSize = 12.sp)
    }
}

/** Botón de dictado: aparece junto al redactor. */
@Composable
internal fun DictationButton(vm: ChatViewModel) {
    val habilitado = vm.ready && !vm.voiceActive
    IconButton(onClick = vm::toggleDictation, enabled = habilitado) {
        Icon(
            imageVector = if (vm.voiceListening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (vm.voiceListening) "Detener el dictado" else "Dictar por voz",
            tint = if (vm.voiceListening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
