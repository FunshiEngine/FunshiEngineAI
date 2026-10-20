package rag.agent

import kotlinx.serialization.json.JsonObject
import llm.ToolSpec

/**
 * Contexto de ejecución de una herramienta: a qué chat pertenece la llamada y
 * dónde puede escribir archivos (F3 agrega outputsDir y los permisos).
 */
data class ToolContext(
    val chatId: String,
)

/** Resultado de ejecutar una herramienta, apto para devolverle al modelo. */
sealed class ToolResult {
    /** Éxito: [text] es lo que el modelo verá como resultado. */
    data class Ok(val text: String) : ToolResult()

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

    /** Ejecuta la herramienta. Todo I/O bloqueante va en Dispatchers.IO. */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}
