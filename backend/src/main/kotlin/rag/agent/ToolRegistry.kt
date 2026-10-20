package rag.agent

import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec

/**
 * Registro de herramientas disponibles para el modelo.
 * Acepta herramientas locales (implementadas acá) y, en un futuro, adaptadas
 * de MCP (el origen queda invisible: un Tool es un Tool).
 */
class ToolRegistry(private val tools: List<Tool>) {

    val isEmpty: Boolean get() = tools.isEmpty()

    /** Especificaciones para el catálogo que se le pasa al gateway. */
    fun specs(): List<ToolSpec> = tools.map { it.spec }

    fun byName(name: String): Tool? = tools.firstOrNull { it.spec.name == name }

    /** Listado legible para el comando /tools. */
    fun describe(): String = tools.joinToString("\n") { "· `${it.spec.name}` — ${it.spec.description}" }

    /** Parsea los argumentos crudos del modelo a JsonObject ({} si vienen vacíos). */
    fun parseArgs(argsJson: String): JsonObject = try {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(argsJson.ifBlank { "{}" })
        el as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
