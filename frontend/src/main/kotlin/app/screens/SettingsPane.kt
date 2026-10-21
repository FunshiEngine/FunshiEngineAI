package app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chat.ChatViewModel
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Configuración: apariencia (tema claro/oscuro, selector de color HSV,
 * perfiles guardados). El motor de inferencia se gestiona en la pestaña Motor.
 */
@Composable
fun SettingsPane(vm: ChatViewModel) {
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ---------- Apariencia ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Apariencia", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))

                    Text("Tema", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    // Dos botones separados para claro/oscuro (no tarjetas).
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        themeButton(vm, "Claro", false, Modifier.weight(1f))
                        themeButton(vm, "Oscuro", true, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))

                    Text("Color de acento", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    // Selector tipo espectro: cuadro HSV + sliders de matiz/saturación/brillo.
                    HsvColorPicker(
                        accent = vm.accentColor,
                        onColor = { argb -> vm.setTheme(vm.isDark, argb) },
                    )
                }
            }

            // ---------- Perfiles de color ----------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Perfiles de color", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Guardá la combinación actual (tema + color) con un nombre y reutilizala cuando quieras.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ColorProfileSection(vm)
                }
            }

            // ---------- Herramientas ----------
            ToolsSettings(vm)

            Spacer(Modifier.height(24.dp))

            // ---------- Voz ----------
            VoiceSettings(vm)

            Spacer(Modifier.height(8.dp))
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** Botón separado para elegir tema claro u oscuro. */
@Composable
private fun themeButton(vm: ChatViewModel, label: String, dark: Boolean, modifier: Modifier = Modifier) {
    val selected = vm.isDark == dark
    val colors = if (selected) ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) else ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    if (selected) {
        Button(onClick = { vm.setTheme(dark, vm.accentColor) }, modifier = modifier, colors = colors) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = { vm.setTheme(dark, vm.accentColor) }, modifier = modifier) {
            Text(label)
        }
    }
}

// ---------------------------------------------------------------------------
// Selector de color HSV: cuadro saturación×brillo + sliders de matiz.
// ---------------------------------------------------------------------------

/** Convierte un Color ARGB Int a componentes HSV (h 0..360, s 0..1, v 0..1). */
private fun rgbToHsv(color: Int): FloatArray {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when (max) {
        min -> 0f
        r -> ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
        g -> ((b - r) / delta + 2f) * 60f
        else -> ((r - g) / delta + 4f) * 60f
    }
    val s = if (max == 0f) 0f else delta / max
    return floatArrayOf(h, s, max)
}

/** Convierte HSV (h 0..360, s 0..1, v 0..1) a Color ARGB Int. */
private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (floor(hp).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Cuadro de espectro (saturación en X, brillo en Y para el matiz actual) con
 * sliders de matiz y de brillo/saturación para ajustar la mezcla con precisión.
 */
@Composable
private fun HsvColorPicker(accent: Int, onColor: (Int) -> Unit) {
    var hsv by remember { mutableStateOf(rgbToHsv(accent)) }
    // Sincroniza el picker si el color cambia desde afuera (otro control/perfil).
    LaunchedEffect(accent) {
        val cur = rgbToHsv(accent)
        if (hsvToArgb(cur[0], cur[1], cur[2]) != accent) hsv = cur
    }
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2]

    fun apply(h: Float, s: Float, v: Float) {
        hsv = floatArrayOf(h, s, v)
        onColor(hsvToArgb(h, s, v))
    }

    Column(Modifier.fillMaxWidth()) {
        // Cuadro de espectro: eje X = saturación, eje Y = brillo.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .pointerInput(hue) {
                    detectTapGestures { off ->
                        val s = (off.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (off.y / size.height).coerceIn(0f, 1f)
                        apply(hue, s, v)
                    }
                }
                .pointerInput(hue) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val s = (change.position.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        apply(hue, s, v)
                    }
                },
        ) {
            val w = size.width
            val hgt = size.height
            // Fondo: gradiente de blanco a matiz puro (eje X)…
            drawRect(Color.hsv(hue, 1f, 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            // …y de transparente a negro (eje Y) para el brillo.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            // Marcador de la selección actual.
            val cx = sat * w
            val cy = (1f - value) * hgt
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.Black, radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
        }

        Spacer(Modifier.height(12.dp))

        // Slider de matiz (arcoíris).
        HueSlider(hue) { apply(it, sat, value) }
        Spacer(Modifier.height(8.dp))

        // Muestra del color elegido + valor hexadecimal.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(hsvToArgb(hue, sat, value)), RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "#%06X".format(hsvToArgb(hue, sat, value) and 0xFFFFFF),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Slider horizontal que muestra el arcoíris de matiz y permite elegirlo. */
@Composable
private fun HueSlider(hue: Float, onHue: (Float) -> Unit) {
    val steps = 12
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    onHue((off.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onHue((change.position.x / size.width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        val w = size.width
        val colors = (0..steps).map { Color.hsv(it * 360f / steps, 1f, 1f) }
        drawRect(Brush.horizontalGradient(colors))
        // Marcador del matiz actual.
        val cx = (hue / 360f) * w
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(cx, size.height / 2f))
        drawCircle(Color.Black, radius = 9.dp.toPx(), center = Offset(cx, size.height / 2f), style = Stroke(width = 2.dp.toPx()))
    }
}

// ---------------------------------------------------------------------------
// Perfiles de color guardados.
// ---------------------------------------------------------------------------

@Composable
private fun ColorProfileSection(vm: ChatViewModel) {
    var name by remember { mutableStateOf("") }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nombre del perfil") },
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                vm.saveColorProfile(name)
                name = ""
            },
            enabled = name.isNotBlank(),
        ) { Text("Guardar perfil") }
    }

    if (vm.colorProfiles.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        vm.colorProfiles.forEach { profile ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.applyColorProfile(profile) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .background(Color(profile.accent), CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, fontSize = 14.sp)
                    Text(
                        if (profile.dark) "Tema oscuro" else "Tema claro",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.deleteColorProfile(profile.name) }) {
                    Text("Borrar", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    } else {
        Spacer(Modifier.height(8.dp))
        Text(
            "Todavía no guardaste ningún perfil.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
