package app.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.core.UiAttachment
import app.core.humanSize
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Tarjeta de un archivo que el modelo creó o descargó (F3): nombre, tamaño y
 * dos acciones — **Abrir** (con la app del sistema) y **Guardar…** (copia
 * donde el usuario elija). Sin emojis: sólo texto e íconos de Material.
 */
@Composable
internal fun AttachmentCard(a: UiAttachment) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.padding(bottom = 6.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, fontSize = 12.sp, maxLines = 1)
                Text(
                    listOf(humanSize(a.sizeBytes), a.mime.substringBefore(';').trim())
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = { openInSystem(a.path) }) {
                Text("Abrir", fontSize = 11.sp)
            }
            TextButton(onClick = { saveAs(a) }) {
                Text("Guardar", fontSize = 11.sp)
            }
        }
    }
}

/** Abre el archivo con la aplicación que el sistema tenga asociada. */
private fun openInSystem(path: String) {
    val file = File(path)
    if (!file.exists()) return
    runCatching {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (desktop != null && desktop.isSupported(Desktop.Action.OPEN)) desktop.open(file)
    }
}

/** "Guardar como…": copia el archivo a la carpeta que elija el usuario. */
private fun saveAs(a: UiAttachment) {
    val source = File(a.path)
    if (!source.exists()) return
    val chooser = JFileChooser()
    chooser.dialogTitle = "Guardar ${a.name}"
    chooser.selectedFile = File(a.name)
    SwingUtilities.invokeLater {
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val dest = chooser.selectedFile.toPath()
            try {
                dest.parent?.let { Files.createDirectories(it) }
                Files.copy(source.toPath(), dest, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Si no se puede copiar (permisos, destino ocupado), no pasa nada.
            }
        }
    }
}
