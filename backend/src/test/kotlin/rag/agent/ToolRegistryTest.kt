package rag.agent

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import llm.api.ToolSpec

/**
 * Tests del registro de herramientas (F1, F7).
 *
 * El registro es lo que ve el modelo. Con MCP, las tools locales y las
 * remotas conviven: lo que importa es que se distingan por el origen y que
 * se puedan reemplazar (las tools de un servidor MCP cambian en caliente).
 */
class ToolRegistryTest {

    private class FakeTool(
        override val spec: ToolSpec,
        override val origin: ToolOrigin = ToolOrigin.LOCAL,
    ) : Tool {
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.Ok("ok")
    }

    private fun tool(name: String, origin: ToolOrigin = ToolOrigin.LOCAL) =
        FakeTool(ToolSpec(name, "Herramienta $name"), origin)

    @Test
    fun `un registro vacio no tiene herramientas`() {
        val registry = ToolRegistry()
        assertTrue(registry.isEmpty)
        assertTrue(registry.specs().isEmpty())
        assertNull(registry.byName("nada"))
    }

    @Test
    fun `las specs son las de las tools`() {
        val registry = ToolRegistry(listOf(tool("get_time"), tool("web_search")))
        assertEquals(listOf("get_time", "web_search"), registry.specs().map { it.name })
    }

    @Test
    fun `el origen por defecto es LOCAL`() {
        val registry = ToolRegistry(listOf(tool("get_time")))
        assertEquals(ToolOrigin.LOCAL, registry.originOf("get_time"))
    }

    @Test
    fun `una tool MCP declara su origen`() {
        val registry = ToolRegistry(listOf(tool("get_time"), tool("mcp__x__y", ToolOrigin.MCP)))
        assertEquals(ToolOrigin.LOCAL, registry.originOf("get_time"))
        assertEquals(ToolOrigin.MCP, registry.originOf("mcp__x__y"))
    }

    @Test
    fun `se pueden filtrar por origen`() {
        val registry = ToolRegistry(
            listOf(tool("a"), tool("b", ToolOrigin.MCP), tool("c", ToolOrigin.MCP)),
        )
        assertEquals(1, registry.byOrigin(ToolOrigin.LOCAL).size)
        assertEquals(2, registry.byOrigin(ToolOrigin.MCP).size)
    }

    @Test
    fun `reemplazar un origen no toca el otro`() {
        val registry = ToolRegistry(listOf(tool("a"), tool("mcp__x__y", ToolOrigin.MCP)))
        registry.replaceOrigin(ToolOrigin.MCP, listOf(tool("mcp__x__z", ToolOrigin.MCP)))
        assertEquals(listOf("a", "mcp__x__z"), registry.specs().map { it.name })
    }

    @Test
    fun `reemplazar con una lista vacia saca las de ese origen`() {
        val registry = ToolRegistry(listOf(tool("a"), tool("mcp__x__y", ToolOrigin.MCP)))
        registry.replaceOrigin(ToolOrigin.MCP, emptyList())
        assertEquals(listOf("a"), registry.specs().map { it.name })
    }

    @Test
    fun `agregar tools las suma`() {
        val registry = ToolRegistry(listOf(tool("a")))
        registry.addAll(listOf(tool("b"), tool("c")))
        assertEquals(3, registry.specs().size)
    }

    @Test
    fun `el listado distingue las tools MCP`() {
        val registry = ToolRegistry(listOf(tool("a"), tool("mcp__x__y", ToolOrigin.MCP)))
        val texto = registry.describe()
        assertTrue("mcp__x__y" in texto)
        assertTrue("MCP" in texto, "las tools MCP tienen que estar marcadas")
    }

    @Test
    fun `los argumentos rotos se parsean como objeto vacio`() {
        val registry = ToolRegistry(listOf(tool("a")))
        assertEquals(0, registry.parseArgs("no soy json").size)
        assertEquals(0, registry.parseArgs("").size)
        assertEquals(1, registry.parseArgs("""{"x":1}""").size)
    }
}
