package rag.agent

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rag.test.runTest

/**
 * Tests de la puerta de permisos (F3).
 *
 * La puerta es el punto más delicado del sistema: si deja pasar una
 * herramienta sin permiso, la app escribe archivos o sale a internet sin que
 * el usuario lo haya consentido.
 */
class ToolPermissionsTest {

    private class FakeTool(
        override val spec: llm.api.ToolSpec,
        override val category: ToolCategory,
    ) : Tool {
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.Ok("ok")
    }

    private fun tool(category: ToolCategory) = FakeTool(
        spec = llm.api.ToolSpec(
            name = "herramienta_de_prueba",
            description = "Herramienta usada por los tests.",
            parametersSchema = """{"type":"object","properties":{}}""",
        ),
        category = category,
    )

    private fun ctx(
        granted: Set<ToolCategory> = emptySet(),
        decision: PermissionDecision = PermissionDecision.ALLOW_ONCE,
    ): Pair<ToolContext, MutableList<PermissionRequest>> {
        val seen = mutableListOf<PermissionRequest>()
        val context = ToolContext(
            chatId = "chat-1",
            outputsDir = java.nio.file.Path.of("."),
            grantedCategories = granted,
            requestPermission = { request ->
                seen += request
                decision
            },
        )
        return context to seen
    }

    @Test
    fun `sin efecto externo no pide permiso`() = runTest {
        val (context, asked) = ctx()
        val decision = ToolPermissions.authorize(tool(ToolCategory.NONE), "{}", context)
        assertEquals(PermissionDecision.ALLOW_ONCE, decision)
        assertTrue(asked.isEmpty(), "una herramienta NONE no debe preguntar nada")
    }

    @Test
    fun `categoria concedida pasa sin preguntar`() = runTest {
        val (context, asked) = ctx(granted = setOf(ToolCategory.FILES))
        val decision = ToolPermissions.authorize(tool(ToolCategory.FILES), "{}", context)
        assertEquals(PermissionDecision.ALLOW_ONCE, decision)
        assertTrue(asked.isEmpty(), "una categoría ya concedida no debe preguntar")
    }

    @Test
    fun `categoria no concedida pregunta y respeta la decision`() = runTest {
        val (context, asked) = ctx(decision = PermissionDecision.DENY)
        val decision = ToolPermissions.authorize(tool(ToolCategory.WEB), """{"url":"x"}""", context)
        assertEquals(PermissionDecision.DENY, decision)
        assertEquals(1, asked.size, "hubo que preguntar una vez")
        assertEquals(ToolCategory.WEB, asked[0].category)
        assertEquals("chat-1", asked[0].chatId)
    }

    @Test
    fun `la peticion lleva el resumen legible de la herramienta`() = runTest {
        val (context, asked) = ctx(decision = PermissionDecision.DENY)
        ToolPermissions.authorize(tool(ToolCategory.FILES), """{"name":"informe.pdf"}""", context)
        assertEquals("herramienta_de_prueba", asked[0].tool)
        assertTrue(asked[0].argsJson.contains("informe.pdf"))
    }

    @Test
    fun `cada categoria pide su propio permiso`() = runTest {
        val (context, asked) = ctx(decision = PermissionDecision.DENY)
        ToolPermissions.authorize(tool(ToolCategory.FILES), "{}", context)
        ToolPermissions.authorize(tool(ToolCategory.WEB), "{}", context)
        ToolPermissions.authorize(tool(ToolCategory.MCP), "{}", context)
        assertEquals(3, asked.size)
        assertEquals(
            listOf(ToolCategory.FILES, ToolCategory.WEB, ToolCategory.MCP),
            asked.map { it.category },
        )
    }
}
