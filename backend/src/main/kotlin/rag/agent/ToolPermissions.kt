package rag.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Puerta de permisos de las herramientas (F3).
 *
 * Regla: el permiso es **por categoría**, está **apagado por defecto** y se
 * concede **por chat**. Si la categoría está concedida en el chat, la
 * herramienta corre sin preguntar; si no, se le pregunta al usuario (la UI
 * muestra el diálogo y devuelve la decisión).
 *
 * Las herramientas sin efecto externo (`ToolCategory.NONE`, como `get_time`)
 * nunca preguntan nada.
 */
object ToolPermissions {

    /**
     * Decide si [tool] puede ejecutarse con los permisos de [ctx].
     * Devuelve [PermissionDecision.ALLOW_ONCE] para lo que ya está concedido
     * o no necesita permiso; [PermissionDecision.DENY] si el usuario lo rechazó.
     */
    suspend fun authorize(tool: Tool, argsJson: String, ctx: ToolContext): PermissionDecision {
        val category = tool.category
        if (category == ToolCategory.NONE) return PermissionDecision.ALLOW_ONCE
        if (category in ctx.grantedCategories) return PermissionDecision.ALLOW_ONCE

        val request = PermissionRequest(
            chatId = ctx.chatId,
            tool = tool.spec.name,
            category = category,
            summary = tool.describeCall(parseArgs(argsJson)),
            argsJson = argsJson,
        )
        return ctx.requestPermission(request)
    }

    /** Parsea los argumentos crudos a JsonObject ({} si vienen vacíos o rotos). */
    private fun parseArgs(raw: String): JsonObject = try {
        Json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}
