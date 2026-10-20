package rag.agent

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec

/**
 * Categoría de una herramienta: define qué permiso hace falta para usarla.
 *
 * El permiso es POR CATEGORÍA (no por herramienta): conceder FILES habilita
 * `create_file` y `download_url`; el usuario lo concede por chat con
 * `/permitir-archivos`. `NONE` = herramienta sin efecto externo (la hora, por
 * ejemplo): siempre disponible.
 */
enum class ToolCategory(val label: String) {
    /** Sin efecto externo: siempre disponible (ej. `get_time`). */
    NONE("Sin permisos"),
    /** Escribir archivos y descargar: permiso FILES. */
    FILES("Archivos"),
    /** Consultar la web: permiso WEB. */
    WEB("Web"),
    /** Herramientas de servidores MCP: permiso MCP. */
    MCP("MCP"),
    ;

    companion object {
        /** Convierte el nombre persistido ("FILES") al enum; null si no existe. */
        fun fromName(name: String): ToolCategory? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Decisión del usuario frente a una petición de permiso. */
enum class PermissionDecision {
    /** Autorizar sólo esta ejecución. */
    ALLOW_ONCE,

    /** Autorizar y conceder la categoría en este chat (queda concedido). */
    ALLOW_ALWAYS,

    /** Denegar: la herramienta no se ejecuta. */
    DENY,
}

/**
 * Petición de permiso que la UI muestra al usuario ("El modelo quiere usar
 * `download_url`… ¿Permitir?").
 */
data class PermissionRequest(
    /** Chat al que pertenece la llamada (el permiso se concede por chat). */
    val chatId: String,
    /** Nombre de la herramienta. */
    val tool: String,
    /** Categoría que hay que conceder. */
    val category: ToolCategory,
    /** Resumen legible de lo que va a hacer ("descargar `doc.pdf`"). */
    val summary: String,
    /** Argumentos crudos (para que el usuario vea el detalle). */
    val argsJson: String = "",
)

/**
 * Contexto de ejecución de una herramienta: a qué chat pertenece la llamada,
 * dónde puede escribir archivos y qué permisos tiene concedidos.
 */
data class ToolContext(
    val chatId: String,
    /** Carpeta donde la app deja los archivos del chat (`outputs/<chat-id>`). */
    val outputsDir: Path,
    /** Categorías ya concedidas en este chat (se ejecutan sin preguntar). */
    val grantedCategories: Set<ToolCategory> = emptySet(),
    /** Pide permiso al usuario; la UI muestra el diálogo y responde. */
    val requestPermission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.DENY },
)

/**
 * Archivo producido por una herramienta (se muestra como adjunto en el chat:
 * se puede abrir o guardar donde quiera el usuario).
 */
data class Artifact(
    /** Nombre visible del archivo. */
    val name: String,
    /** Ruta absoluta en disco. */
    val path: String,
    /** Tamaño en bytes. */
    val sizeBytes: Long,
    /** Tipo MIME (deducido de la extensión; vacío si no se sabe). */
    val mime: String = "",
)

/** Resultado de ejecutar una herramienta, apto para devolverle al modelo. */
sealed class ToolResult {
    /** Éxito: [text] es lo que el modelo verá como resultado. */
    data class Ok(val text: String, val artifacts: List<Artifact> = emptyList()) : ToolResult()

    /** Fallo controlado: [message] es legible; el modelo puede reintentar o disculparse. */
    data class Error(val message: String) : ToolResult()

    val ok: Boolean get() = this is Ok

    /** Texto legible para devolver al modelo (éxito = texto, fallo = mensaje). */
    fun render(): String = when (this) {
        is Ok -> text
        is Error -> message
    }
}

/**
 * Una herramienta que el modelo puede pedir. Implementan `execute` sin tocar
 * la UI; el resultado es texto para el LLM. La herramienta nunca toca la red
 * ni el disco sin que el AgentLoop la ejecute: el modelo sólo la pide.
 */
interface Tool {
    val spec: ToolSpec

    /** Categoría de permiso que exige (NONE = siempre disponible). */
    val category: ToolCategory get() = ToolCategory.NONE

    /** Resumen legible de la llamada, para el diálogo de permiso. */
    fun describeCall(args: JsonObject): String = spec.name

    /** Ejecuta la herramienta. Todo I/O bloqueante va en Dispatchers.IO. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
