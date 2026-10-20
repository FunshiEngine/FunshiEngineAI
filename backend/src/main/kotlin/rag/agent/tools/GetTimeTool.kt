package rag.agent.tools

import kotlinx.serialization.json.JsonObject
import llm.ToolSpec
import rag.agent.Tool
import rag.agent.ToolContext
import rag.agent.ToolResult
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Herramienta de prueba del tool-calling (F1): devuelve la fecha y hora actual
 * del sistema. Sin red, sin disco, sin permisos: sirve para verificar el bucle
 * completo con una pregunta tipo "¿qué hora es?".
 */
class GetTimeTool : Tool {
    override val spec = ToolSpec(
        name = "get_time",
        description = "Devuelve la fecha y hora actual del sistema. Usala si el usuario pregunta qué hora es o qué día es hoy.",
        parametersSchema = "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val now = LocalDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return ToolResult.Ok("Fecha y hora actual: $formatted (zona: ${ZoneId.systemDefault().id})")
    }
}
