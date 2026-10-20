package app

/** Pantalla visible del asistente. */
enum class Screen { LOADING, CHAT, MODELS, APIS, SETTINGS }

/** Progreso de una operación en curso (descarga, clonado, compilado). */
data class UiProgress(
    val label: String,
    val doneBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val percent: Int? = null,
)

/** Tema elegido por el usuario. */
data class AppTheme(
    val dark: Boolean = false,
    val accent: Int = 0xFF6200EE.toInt(),
)

/** Una herramienta ejecutada por el modelo durante una respuesta (tarjeta). */
data class UiToolCall(
    val name: String,
    val args: String,
    /** null = todavía ejecutándose. */
    val ok: Boolean? = null,
    val result: String = "",
)

/** Mensaje de chat para la UI. */
data class UiMessage(
    val role: String,
    val text: String,
    val sources: List<String> = emptyList(),
    val toolCalls: List<UiToolCall> = emptyList(),
)

/** Archivo documental cargado en un motor (para el panel "Documentación"). */
data class UiDocsFile(
    val name: String,
    val ext: String,
    val size: Long,
)

/** Tamaño legible para la UI ("2.35 GB", "512.0 MB", "48 KB"). */
fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}