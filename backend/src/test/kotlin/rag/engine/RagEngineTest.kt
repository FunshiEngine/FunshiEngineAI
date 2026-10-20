package rag.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage
import rag.config.RagConfig
import rag.retrieval.KnowledgeBase
import rag.test.runTest

/**
 * Tests del motor de RAG (preparación del prompt).
 *
 * `prepare()` es lo que decide qué ve el modelo: el prompt de sistema, el
 * contexto recuperado y el historial. Si arma mal el mensaje, el modelo
 * responde a otra cosa (o se inventa).
 */
class RagEngineTest {

    private class FakeGateway(private val respuesta: String = "respuesta") : LlmGateway {
        val requests = mutableListOf<List<ChatMessage>>()
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> {
            requests += messages
            return flow { emit(LlmChunk.Text(respuesta)) }
        }
    }

    /** Docs de prueba + motor listo (con el índice ya construido). */
    private suspend fun motor(respuesta: String = "respuesta"): Pair<RagEngine, FakeGateway> {
        val dir = Files.createTempDirectory("ragengine-test-")
        Files.writeString(dir.resolve("motor.md"), "# Motor\n\nEl motor usa Vulkan para renderizar.")
        val cfg = RagConfig(
            internalDocsDirs = listOf(dir.toAbsolutePath().toString()),
            githubSources = emptyList(),
            // Un archivo que no existe: el motor usa su prompt de respaldo.
            systemPromptFile = "no-existe-este-prompt.md",
            topK = 3,
            historyTurns = 2,
        )
        val kb = KnowledgeBase(cfg)
        kb.sync { }
        val gateway = FakeGateway(respuesta)
        return RagEngine(cfg, kb, gateway) to gateway
    }

    @Test
    fun `en modo docs el mensaje lleva el contexto y la pregunta`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("como renderizo?", emptyList(), useDocs = true)
        val user = prep.messages.last { it.role == "user" }
        assertTrue("CONTEXTO:" in user.content)
        assertTrue("PREGUNTA:" in user.content)
        assertTrue("como renderizo?" in user.content)
    }

    @Test
    fun `en modo docs las fuentes son las que se recuperaron`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("vulkan", emptyList(), useDocs = true)
        assertTrue(prep.sources.isNotEmpty(), "la pregunta tiene que encontrar fuentes")
        assertTrue(prep.sources.any { "vulkan" in it.text.lowercase() })
    }

    @Test
    fun `en modo general no hay contexto ni fuentes`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("hola", emptyList(), useDocs = false)
        assertTrue(prep.sources.isEmpty())
        val user = prep.messages.last { it.role == "user" }
        assertFalse("CONTEXTO:" in user.content)
        assertEquals("hola", user.content)
    }

    @Test
    fun `el historial se recorta a los turnos configurados`() = runTest {
        val (engine, _) = motor()
        val historial = (1..10).map { ChatMessage("user", "mensaje $it") }
        val prep = engine.prepare("pregunta", historial, useDocs = false)
        val usuarios = prep.messages.count { it.role == "user" && it.content.startsWith("mensaje") }
        assertTrue(
            usuarios <= 2 * 2,
            "el historial tiene que estar recortado a historyTurns*2: $usuarios",
        )
    }

    @Test
    fun `sin resultados el contexto lo dice`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("xyzabc", emptyList(), useDocs = true)
        val user = prep.messages.last { it.role == "user" }
        assertTrue(
            "sin resultados" in user.content,
            "el modelo tiene que enterarse de que no hay nada: ${user.content}",
        )
    }

    @Test
    fun `el mensaje del sistema va primero`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("hola", emptyList(), useDocs = true)
        assertEquals("system", prep.messages.first().role)
    }

    @Test
    fun `ask devuelve la respuesta del modelo`() = runTest {
        val (engine, gateway) = motor("la respuesta")
        val answer = engine.ask("vulkan", emptyList())
        val texto = answer.tokens.toList().joinToString("")
        assertEquals("la respuesta", texto)
        assertEquals(1, gateway.requests.size)
        assertTrue(answer.sources.isNotEmpty())
    }

    @Test
    fun `askGeneral no busca fuentes`() = runTest {
        val (engine, _) = motor()
        val answer = engine.askGeneral("hola", emptyList())
        assertTrue(answer.sources.isEmpty())
    }

    @Test
    fun `el contexto numera las fuentes para citarlas`() = runTest {
        val (engine, _) = motor()
        val prep = engine.prepare("vulkan", emptyList(), useDocs = true)
        val user = prep.messages.last { it.role == "user" }
        assertTrue(
            "[1]" in user.content,
            "las fuentes tienen que ir numeradas para poder citarlas: ${user.content}",
        )
    }
}
