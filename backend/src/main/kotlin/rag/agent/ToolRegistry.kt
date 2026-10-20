package rag.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec

/**
 * Registro de herramientas disponibles para el modelo.
 *
 * Acepta herramientas locales (implementadas acá) y adaptadas de servidores
 * MCP, indistintamente: quien usa el registro ve un `Tool` y nada más. El
 * origen queda disponible para(listarlos y mostrarlos, no para decidir cómo
 * se ejecutan.
 *
 * El registro es mutable a propósito: las herramientas MCP se descubren en
 * caliente (el servidor puede cambiar sus tools), así que se pueden reemplazar
 * sin reiniciar la app.
 */
class ToolRegistry(tools: List<Tool> = emptyList()) {

    private val items: MutableList<Tool> = tools.toMutableList()

    val isEmpty: Boolean get() = items.isEmpty()

    /** Todas las herramientas registradas. */
    fun all(): List<Tool> = items.toList()

    /** Especificaciones para el catálogo que se le pasa al gateway. */
    fun specs(): List<ToolSpec> = items.map { it.spec }

    fun byName(name: String): Tool? = items.firstOrNull { it.spec.name == name }

    /** Origen de una herramienta por nombre (null si no existe). */
    fun originOf(name: String): ToolOrigin? = byName(name)?.origin

    /** Herramientas de un origen dado. */
    fun byOrigin(origin: ToolOrigin): List<Tool> = items.filter { it.origin == origin }

    /** Agrega herramientas (las MCP se registran cuando se descubren). */
    fun addAll(tools: List<Tool>) {
        items.addAll(tools)
    }

    /**
     * Reemplaza todas las herramientas de un origen por otras.
     * Se usa al refrescar los servidores MCP: las tools viejas (de un catálogo
     * que ya no existe) se van y entran las nuevas.
     */
    fun replaceOrigin(origin: ToolOrigin, tools: List<Tool>) {
        val keep = items.filter { it.origin != origin }
        items.clear()
        items.addAll(keep)
        items.addAll(tools)
    }

    /** Listado legible para el comando /tools. */
    fun describe(): String = items.joinToString("\n") { tool ->
        val origen = if (tool.origin == ToolOrigin.LOCAL) "" else " _(MCP)_"
        "· `${tool.spec.name}`$origen — ${tool.spec.description}"
    }

    /** Parsea los argumentos crudos del modelo a JsonObject ({} si vienen vacíos). */
    fun parseArgs(argsJson: String): JsonObject = try {
        val el = Json.parseToJsonElement(argsJson.ifBlank { "{}" })
        el as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
