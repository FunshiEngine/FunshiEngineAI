package app.core

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

/**
 * Archivo que el modelo creó o descargó durante la respuesta (adjunto del
 * mensaje): se puede abrir o guardar donde quiera el usuario.
 */
data class UiAttachment(
    val name: String,
    val path: String,
    val sizeBytes: Long = 0L,
    val mime: String = "",
)

/**
 * Verificación de las citas de una respuesta (F9).
 *
 * En modo Docs el modelo tiene que citar sus fuentes (`[1]`, `[2]…). Después
 * de responder, la app comprueba cada cita contra la fuente citada y deja
 * el resultado a la vista: así el usuario sabe si la respuesta está respaldada
 * por la documentación o no.
 */
data class UiCitation(
    /** Citas respaldadas por la fuente que citan. */
    val verified: Int = 0,
    /** Citas cuya fuente existe pero no respalda lo que se dice. */
    val unsupported: Int = 0,
    /** Citas que apuntan a una fuente que no existe. */
    val unknown: Int = 0,
) {
    /** Total de citas de la respuesta. */
    val total: Int get() = verified + unsupported + unknown

    /** ¿Todo respaldado? */
    val ok: Boolean get() = total > 0 && unsupported == 0 && unknown == 0
}

/** Mensaje de chat para la UI. */
data class UiMessage(
    val role: String,
    val text: String,
    val sources: List<String> = emptyList(),
    val toolCalls: List<UiToolCall> = emptyList(),
    val attachments: List<UiAttachment> = emptyList(),
    /** Verificación de citas (sólo en modo Docs). */
    val citation: UiCitation? = null,
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