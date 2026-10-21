package app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chat.ChatViewModel
import app.chat.setCaptureSampleRate
import app.chat.setMaxRecordingMs
import app.chat.setMaxSpeakChars
import app.chat.setMinRecordingMs
import app.chat.setSilenceThreshold
import app.chat.setSilenceTimeoutMs
import app.chat.setSpeakAnswers
import app.chat.setSttApiKey
import app.chat.setSttBaseUrl
import app.chat.setSttLanguage
import app.chat.setSttModel
import app.chat.setSttProvider
import app.chat.setSttTimeoutSeconds
import app.chat.setTtsApiKey
import app.chat.setTtsBaseUrl
import app.chat.setTtsModel
import app.chat.setTtsProvider
import app.chat.setTtsSpeed
import app.chat.setTtsTimeoutSeconds
import app.chat.setTtsVoice
import app.chat.setVoiceEnabled
import app.chat.setWhisperCliPath
import app.chat.setWhisperModelPath
import voice.config.SttProvider
import voice.config.TtsProvider

/**
 * Ajustes de voz (V1).
 *
 * Tres bloques: el interruptor general, el **dictado** (audio → texto) y la
 * **locución** (texto → audio), más la captura (umbral de silencio y
 * tiempos).
 *
 * Todo se guarda al instante en la sección `voice` de `app-config.json`.
 */
@Composable
fun VoiceSettings(vm: ChatViewModel) {
    val cfg = vm.config.voice
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Voz", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dictado por voz (el micrófono transcribe lo que hablás) y locución " +
                    "(la respuesta del modelo se lee en voz alta). El chat por voz usa el mismo " +
                    "chat de siempre: RAG, herramientas y citas funcionan igual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // ---------- Interruptor general ----------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Voz activada", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Apagada, el micrófono y la locución no hacen nada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.enabled, onCheckedChange = { vm.setVoiceEnabled(it) })
            }

            Spacer(Modifier.height(16.dp))
            Text("Dictado (audio → texto)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ProviderRow(
                selected = cfg.stt.provider,
                options = listOf(
                    SttProvider.NONE to "Ninguno",
                    SttProvider.OPENAI to "API (OpenAI-compatible)",
                    SttProvider.WHISPER_CLI to "Local (whisper.cpp)",
                ),
                onSelect = { vm.setSttProvider(it.name) },
            )
            when (cfg.stt.provider) {
                SttProvider.NONE -> Unit
                SttProvider.OPENAI -> {
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "URL base (vacío = la del motor activo)",
                        value = cfg.stt.baseUrl,
                        onValue = { vm.setSttBaseUrl(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Clave de la API (vacía = la del motor activo)",
                        value = cfg.stt.apiKey,
                        onValue = { vm.setSttApiKey(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Modelo de transcripción",
                        value = cfg.stt.model,
                        onValue = { vm.setSttModel(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Idioma (vacío = que lo detecte)",
                        value = cfg.stt.language,
                        onValue = { vm.setSttLanguage(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceNumberField(
                        label = "Timeout de la transcripción (segundos)",
                        value = cfg.stt.timeoutMs / 1000,
                        onValue = { vm.setSttTimeoutSeconds(it) },
                    )
                }
                SttProvider.WHISPER_CLI -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "El audio NO sale de la máquina: se transcribe con whisper.cpp en local. " +
                            "Necesitás el binario (whisper-cli) y su modelo GGML.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Ruta del binario (vacío = autodetectar en el PATH)",
                        value = cfg.stt.whisperCliPath,
                        onValue = { vm.setWhisperCliPath(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Ruta del modelo GGML (obligatoria)",
                        value = cfg.stt.whisperModelPath,
                        onValue = { vm.setWhisperModelPath(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Idioma (vacío = que lo detecte)",
                        value = cfg.stt.language,
                        onValue = { vm.setSttLanguage(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceNumberField(
                        label = "Timeout de la transcripción (segundos)",
                        value = cfg.stt.timeoutMs / 1000,
                        onValue = { vm.setSttTimeoutSeconds(it) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Locución (texto → audio)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ProviderRow(
                selected = cfg.tts.provider,
                options = listOf(
                    TtsProvider.NONE to "Ninguno",
                    TtsProvider.OPENAI to "API (OpenAI-compatible)",
                    TtsProvider.SYSTEM to "El del sistema (sin internet)",
                ),
                onSelect = { vm.setTtsProvider(it.name) },
            )
            when (cfg.tts.provider) {
                TtsProvider.NONE -> Unit
                TtsProvider.OPENAI -> {
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "URL base (vacío = la del motor activo)",
                        value = cfg.tts.baseUrl,
                        onValue = { vm.setTtsBaseUrl(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Clave de la API (vacía = la del motor activo)",
                        value = cfg.tts.apiKey,
                        onValue = { vm.setTtsApiKey(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Modelo de locución",
                        value = cfg.tts.model,
                        onValue = { vm.setTtsModel(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceTextField(
                        label = "Voz del motor",
                        value = cfg.tts.voice,
                        onValue = { vm.setTtsVoice(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceNumberField(
                        label = "Velocidad (0.5 - 2.0)",
                        value = (cfg.tts.speed * 100).toLong(),
                        onValue = { vm.setTtsSpeed(it / 100f) },
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceNumberField(
                        label = "Timeout de la locución (segundos)",
                        value = cfg.tts.timeoutMs / 1000,
                        onValue = { vm.setTtsTimeoutSeconds(it) },
                    )
                }
                TtsProvider.SYSTEM -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Usa el motor del sistema: Windows (System.Speech), macOS (say) o " +
                            "Linux (espeak-ng). No necesita clave ni internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    VoiceNumberField(
                        label = "Timeout de la locución (segundos)",
                        value = cfg.tts.timeoutMs / 1000,
                        onValue = { vm.setTtsTimeoutSeconds(it) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Captura y lectura", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceNumberField(
                    label = "Tasa de muestreo (Hz)",
                    value = cfg.capture.sampleRate.toLong(),
                    onValue = { vm.setCaptureSampleRate(it.toInt()) },
                    modifier = Modifier.weight(1f),
                )
                VoiceNumberField(
                    label = "Umbral de silencio (0 - 100)",
                    value = (cfg.capture.silenceThreshold * 1000).toLong(),
                    onValue = { vm.setSilenceThreshold(it / 1000f) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VoiceNumberField(
                    label = "Silencio que corta la frase (ms)",
                    value = cfg.capture.silenceTimeoutMs,
                    onValue = { vm.setSilenceTimeoutMs(it) },
                    modifier = Modifier.weight(1f),
                )
                VoiceNumberField(
                    label = "Tope de grabación (ms)",
                    value = cfg.capture.maxRecordingMs,
                    onValue = { vm.setMaxRecordingMs(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            VoiceNumberField(
                label = "Hablar mínimo para dar la frase por válida (ms)",
                value = cfg.capture.minRecordingMs,
                onValue = { vm.setMinRecordingMs(it) },
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Leer las respuestas en voz alta", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "En el chat por voz, la respuesta del modelo se dice con el motor de locución.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.speakAnswers, onCheckedChange = { vm.setSpeakAnswers(it) })
            }
            Spacer(Modifier.height(8.dp))
            VoiceNumberField(
                label = "Tope de caracteres que se leen (0 = sin tope)",
                value = cfg.maxSpeakChars.toLong(),
                onValue = { vm.setMaxSpeakChars(it.toInt()) },
            )

            // Problemas de la configuración: se muestran antes de usarla.
            val problemas = cfg.validate(vm.config.llm.remoteBaseUrl, vm.config.llm.apiKey)
            if (cfg.enabled && problemas.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "La configuración de voz tiene problemas:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        problemas.forEach {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fila de botones para elegir un proveedor (uno activo a la vez). */
@Composable
private fun <T> ProviderRow(
    selected: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            if (active) {
                Button(onClick = { onSelect(value) }) { Text(label, fontSize = 12.sp) }
            } else {
                TextButton(onClick = { onSelect(value) }) { Text(label, fontSize = 12.sp) }
            }
        }
    }
}

/** Campo de texto de una línea (la configuración se guarda al instante). */
@Composable
private fun VoiceTextField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Campo numérico (sólo guarda si el número es válido). */
@Composable
private fun VoiceNumberField(
    label: String,
    value: Long,
    onValue: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var texto by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = texto,
        onValueChange = { nuevo ->
            texto = nuevo
            val n = nuevo.toLongOrNull()
            if (n != null && n >= 0) onValue(n)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
