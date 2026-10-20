package rag.agent

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import rag.test.runTest

/**
 * Tests del bucle de tool calling (F1, F7).
 *
 * El bucle es la parte más delicada de la app: decide qué se ejecuta, qué se
 * le devuelve al modelo y cuándo se rinde. Las reglas que se prueban acá son
 * las del diseño (tomadas del bucle predictor de SAI):
 *
 *  - Un error transitorio (JSON roto, tool desconocida, fallo de la tool)
 *    NO corta el bucle: consume una ronda y genera feedback.
 *  - La cancelación externa SÍ se propaga.
 *  - El texto ya escrito nunca se pierde.
 */
class AgentLoopTest {

    /* ----------------------------------------------------------------------
     * Dobles de prueba
     * ---------------------------------------------------------------------- */

    /** Gateway que responde con un guion de rondas prefijado. */
    private class ScriptedGateway(private val script: List<List<LlmChunk>>) : LlmGateway {
        /** Cada request que recibió el gateway (para ver qué se le pasa al modelo). */
        val requests = mutableListOf<List<ChatMessage>>()

        /** El catálogo que recibió en cada ronda (null = modo por prompt). */
        val catalogs = mutableListOf<List<ToolSpec>?>()

        var round = 0

        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> {
            requests += messages
            catalogs += tools
            val chunks = script.getOrElse(round) { emptyList() }
            round++
            return flow { chunks.forEach { emit(it) } }
        }
    }

    /** Gateway que siempre falla con la excepción dada. */
    private class FailingGateway(private val error: Throwable) : LlmGateway {
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> =
            flow { throw error }
    }

    /** Tool de prueba: responde con el texto pedido y puede fallar o colgar. */
    private class FakeTool(
        name: String,
        override val category: ToolCategory = ToolCategory.NONE,
        private val answer: String = "resultado",
        private val failWith: String? = null,
        private val hang: Boolean = false,
        private val artifact: Artifact? = null,
    ) : Tool {
        override val spec = ToolSpec(name, "Tool de prueba $name")
        var calls = 0

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            calls++
            if (hang) kotlinx.coroutines.delay(60_000)
            failWith?.let { return ToolResult.Error(it) }
            return ToolResult.Ok(answer, listOfNotNull(artifact))
        }
    }

    private fun ctx(
        granted: Set<ToolCategory> = emptySet(),
        permission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.ALLOW_ONCE },
    ) = ToolContext(
        chatId = "chat-test",
        outputsDir = Path.of("."),
        grantedCategories = granted,
        requestPermission = permission,
    )

    private fun call(name: String, argsJson: String = "{}") =
        LlmChunk.ToolCall(id = "c1", name = name, argsJson = argsJson)

    private fun text(value: String) = LlmChunk.Text(value)

    private fun messages() = listOf(
        ChatMessage("system", "Sos un asistente."),
        ChatMessage("user", "hola"),
    )

    /* ----------------------------------------------------------------------
     * Camino feliz
     * ---------------------------------------------------------------------- */

    @Test
    fun `sin herramientas el bucle solo devuelve texto`() = runTest {
        val gateway = ScriptedGateway(listOf(listOf(text("hola "), text("mundo"))))
        val loop = AgentLoop(gateway, ToolRegistry(), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertEquals("hola mundo", answer.text)
        assertTrue(answer.toolCalls.isEmpty())
    }

    @Test
    fun `la tool se ejecuta y su resultado vuelve al modelo`() = runTest {
        val tool = FakeTool("get_time", answer = "son las 10")
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("get_time")),
                listOf(text("son las 10")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())

        assertEquals(1, tool.calls, "la tool tenía que ejecutarse una vez")
        assertEquals("son las 10", answer.text)

        // El modelo tiene que ver su llamada y el resultado.
        val segunda = gateway.requests[1]
        assertTrue(
            segunda.any { it.role == "tool" && it.content.contains("son las 10") },
            "el resultado de la tool tiene que volverle al modelo: $segunda",
        )
    }

    @Test
    fun `la tool usada queda en la respuesta`() = runTest {
        val tool = FakeTool("get_time")
        val gateway = ScriptedGateway(listOf(listOf(call("get_time")), listOf(text("listo"))))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertEquals(1, answer.toolCalls.size)
        assertEquals("get_time", answer.toolCalls[0].name)
        assertTrue(answer.toolCalls[0].ok)
    }

    @Test
    fun `los artefactos de la tool se propagan`() = runTest {
        val artifact = Artifact("a.pdf", "/tmp/a.pdf", 10, "application/pdf")
        val tool = FakeTool("descargar", artifact = artifact)
        val gateway = ScriptedGateway(listOf(listOf(call("descargar")), listOf(text("listo"))))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertEquals(1, answer.toolCalls[0].artifacts.size)
        assertEquals("a.pdf", answer.toolCalls[0].artifacts[0].name)
    }

    @Test
    fun `el catalogo de tools se le pasa al gateway en modo nativo`() = runTest {
        val gateway = ScriptedGateway(listOf(listOf(text("hola"))))
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(FakeTool("get_time"))),
            nativeToolCalls = true,
        )
        loop.run(messages(), ctx())
        assertEquals(1, gateway.catalogs[0]?.size)
        assertEquals("get_time", gateway.catalogs[0]?.get(0)?.name)
    }

    /* ----------------------------------------------------------------------
     * Errores que NO cortan el bucle (regla SAI)
     * ---------------------------------------------------------------------- */

    @Test
    fun `una tool desconocida vuelve como error y el bucle sigue`() = runTest {
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("no_existe")),
                listOf(text("no tengo esa herramienta")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(FakeTool("get_time"))), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())

        assertEquals("no tengo esa herramienta", answer.text)
        assertFalse(answer.toolCalls[0].ok)
        assertTrue(
            answer.toolCalls[0].result.contains("no_existe"),
            "el error tiene que nombrar la tool: ${answer.toolCalls[0].result}",
        )
    }

    @Test
    fun `una tool que falla devuelve el error al modelo`() = runTest {
        val tool = FakeTool("get_time", failWith = "no se pudo leer el reloj")
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("get_time")),
                listOf(text("no pude leer la hora")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertFalse(answer.toolCalls[0].ok)
        assertTrue(answer.toolCalls[0].result.contains("no se pudo leer el reloj"))
    }

    @Test
    fun `un JSON roto se repara y la tool se ejecuta`() = runTest {
        val tool = FakeTool("get_time")
        // Comillas curvas: el parseo leniente falla, la reparación las arregla.
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("get_time", """{“argumento”: 1}""")),
                listOf(text("listo")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertEquals(1, tool.calls, "el JSON roto tiene que repararse y ejecutarse")
        assertTrue(answer.toolCalls[0].ok)
    }

    @Test
    fun `un JSON irreparable da feedback y el bucle sigue`() = runTest {
        val tool = FakeTool("get_time")
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("get_time", "esto no es json")),
                listOf(text("ok, sin tool")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())

        assertEquals(0, tool.calls, "una llamada inválida no se ejecuta")
        assertEquals("ok, sin tool", answer.text)
        // Al modelo le tiene que llegar la guía de cómo escribirla bien.
        val segunda = gateway.requests[1]
        assertTrue(
            segunda.any { it.content.contains("tool_call") },
            "el feedback tiene que explicar el formato: $segunda",
        )
    }

    @Test
    fun `los errores de tool consecutivos cortan el bucle`() = runTest {
        val tool = FakeTool("get_time", failWith = "falla")
        // Siempre falla: el bucle tiene que rendirse, no girar 8 rondas.
        val gateway = ScriptedGateway(
            List(10) { listOf(call("get_time")) },
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())

        assertEquals(
            AgentLoop.MAX_CONSECUTIVE_TOOL_ERRORS,
            tool.calls,
            "el bucle tiene que cortar al agotar los reintentos de error",
        )
    }

    @Test
    fun `el texto ya escrito no se pierde cuando la tool falla`() = runTest {
        val tool = FakeTool("get_time", failWith = "falla")
        val gateway = ScriptedGateway(
            listOf(
                listOf(text("estoy pensando..."), call("get_time")),
                listOf(text(" no pude leer la hora")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx())
        assertTrue(
            answer.text.startsWith("estoy pensando..."),
            "el texto previo a la tool no puede perderse: ${answer.text}",
        )
    }

    /* ----------------------------------------------------------------------
     * Permisos
     * ---------------------------------------------------------------------- */

    @Test
    fun `sin permiso la tool no se ejecuta`() = runTest {
        val tool = FakeTool("web_search", category = ToolCategory.WEB)
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("web_search")),
                listOf(text("no puedo buscar")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx(permission = { PermissionDecision.DENY }))

        assertEquals(0, tool.calls, "sin permiso la tool no se ejecuta")
        assertFalse(answer.toolCalls[0].ok)
    }

    @Test
    fun `el permiso denegado no corta el bucle`() = runTest {
        val tool = FakeTool("web_search", category = ToolCategory.WEB)
        val gateway = ScriptedGateway(
            listOf(
                listOf(call("web_search")),
                listOf(text("ok, sin buscar")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx(permission = { PermissionDecision.DENY }))
        assertEquals("ok, sin buscar", answer.text)
        assertEquals(2, gateway.round, "el bucle tiene que seguir después del permiso denegado")
    }

    @Test
    fun `la categoria concedida ejecuta sin preguntar`() = runTest {
        val tool = FakeTool("web_search", category = ToolCategory.WEB)
        var preguntada = false
        val gateway = ScriptedGateway(listOf(listOf(call("web_search")), listOf(text("listo"))))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        loop.run(
            messages(),
            ctx(
                granted = setOf(ToolCategory.WEB),
                permission = { preguntada = true; PermissionDecision.DENY },
            ),
        )
        assertEquals(1, tool.calls)
        assertFalse(preguntada, "con la categoría concedida no hay que preguntar")
    }

    @Test
    fun `si el modelo insiste tras un permiso denegado el bucle corta`() = runTest {
        val tool = FakeTool("web_search", category = ToolCategory.WEB)
        val gateway = ScriptedGateway(
            List(10) { listOf(call("web_search")) },
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        val answer = loop.run(messages(), ctx(permission = { PermissionDecision.DENY }))

        assertEquals(0, tool.calls)
        assertEquals(
            2,
            answer.toolCalls.size,
            "la segunda insistencia tiene que cortar el bucle (no preguntar de nuevo)",
        )
    }

    @Test
    fun `la peticion de permiso dice que herramienta es`() = runTest {
        val tool = FakeTool("web_search", category = ToolCategory.WEB)
        val peticiones = mutableListOf<PermissionRequest>()
        val gateway = ScriptedGateway(listOf(listOf(call("web_search")), listOf(text("listo"))))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        loop.run(
            messages(),
            ctx(permission = { peticion ->
                peticiones += peticion
                PermissionDecision.ALLOW_ONCE
            }),
        )
        assertEquals(1, peticiones.size)
        assertEquals("web_search", peticiones[0].tool)
        assertEquals(ToolCategory.WEB, peticiones[0].category)
        assertEquals("chat-test", peticiones[0].chatId)
    }

    /* ----------------------------------------------------------------------
     * Cancelación y límites
     * ---------------------------------------------------------------------- */

    @Test
    fun `la cancelacion externa se propaga`() = runTest {
        val gateway = FailingGateway(CancellationException("el usuario detuvo"))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(FakeTool("get_time"))), nativeToolCalls = true)
        var fallo: Throwable? = null
        try {
            loop.run(messages(), ctx())
        } catch (e: CancellationException) {
            fallo = e
        }
        assertTrue(fallo is CancellationException, "la cancelación tiene que propagarse, no tragarse")
    }

    @Test
    fun `una tool colgada se corta con el timeout`() = runTest {
        val tool = FakeTool("get_time", hang = true)
        val gateway = ScriptedGateway(listOf(listOf(call("get_time")), listOf(text("se colgó"))))
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(tool)),
            nativeToolCalls = true,
            toolTimeoutMs = 100,
        )
        val answer = loop.run(messages(), ctx())
        assertFalse(answer.toolCalls[0].ok, "una tool colgada tiene que volver como error")
        assertTrue(answer.toolCalls[0].result.contains("tardó más de"))
    }

    @Test
    fun `el bucle corta despues de las rondas maxima`() = runTest {
        val tool = FakeTool("get_time")
        // El modelo pide tool siempre: el bucle tiene que rendirse.
        val gateway = ScriptedGateway(List(20) { listOf(call("get_time")) })
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = true)
        loop.maxIterations = 3
        val answer = loop.run(messages(), ctx())
        assertEquals(3, tool.calls)
        assertEquals(3, answer.toolCalls.size)
    }

    /* ----------------------------------------------------------------------
     * Modo LOCAL: protocolo por prompt
     * ---------------------------------------------------------------------- */

    @Test
    fun `en modo prompt el bloque tool_call se ejecuta`() = runTest {
        val tool = FakeTool("get_time", answer = "son las 10")
        val gateway = ScriptedGateway(
            listOf(
                listOf(text("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```\n")),
                listOf(text("son las 10")),
            ),
        )
        val loop = AgentLoop(gateway, ToolRegistry(listOf(tool)), nativeToolCalls = false)
        val answer = loop.run(messages(), ctx())

        assertEquals(1, tool.calls)
        assertEquals("son las 10", answer.text)
        // El bloque no es texto visible: no puede aparecer en la respuesta.
        assertFalse(answer.text.contains("tool_call"), "el bloque no es texto para el usuario")
    }

    @Test
    fun `en modo prompt el catalogo se inyecta en el system prompt`() = runTest {
        val gateway = ScriptedGateway(listOf(listOf(text("hola"))))
        val loop = AgentLoop(
            gateway,
            ToolRegistry(listOf(FakeTool("get_time"))),
            nativeToolCalls = false,
        )
        loop.run(messages(), ctx())
        val system = gateway.requests[0].first { it.role == "system" }
        assertTrue(system.content.contains("get_time"), "el catálogo tiene que estar en el system prompt")
        assertTrue(system.content.contains("HERRAMIENTAS DISPONIBLES"))
    }

    @Test
    fun `en modo prompt no se manda el catalogo en el request`() = runTest {
        val gateway = ScriptedGateway(listOf(listOf(text("hola"))))
        val loop = AgentLoop(gateway, ToolRegistry(listOf(FakeTool("get_time"))), nativeToolCalls = false)
        loop.run(messages(), ctx())
        assertEquals(null, gateway.catalogs[0], "en modo prompt el catálogo va en el prompt, no en el request")
    }
}
