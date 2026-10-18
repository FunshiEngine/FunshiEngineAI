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

/** Mensaje de chat para la UI. */
data class UiMessage(
    val role: String,
    val text: String,
    val sources: List<String> = emptyList(),
)