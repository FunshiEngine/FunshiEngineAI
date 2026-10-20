package rag.engine

import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import llm.api.LlmGateway
import llm.config.ChatMessage
import rag.config.AppPaths
import rag.config.RagConfig
import rag.retrieval.Chunk
import rag.retrieval.KnowledgeBase

class Answer(val sources: List<Chunk>, val tokens: Flow<String>)

/** Mensajes ya armados para una pregunta, con las fuentes de contexto. */
data class Prepared(val sources: List<Chunk>, val messages: List<ChatMessage>)

class RagEngine(
    private val cfg: RagConfig,
    private val kb: KnowledgeBase,
    private val llm: LlmGateway
) {
    /** Gateway en uso (por si el caller arma un AgentLoop con el mismo motor). */
    val gateway: LlmGateway get() = llm

    /** Se lee en cada pregunta: puedes editar el archivo sin reiniciar la app. */
    private fun systemPrompt(): String {
        val file = AppPaths.resolve(cfg.systemPromptFile)
        return if (Files.exists(file)) Files.readString(file)
        else "Responde únicamente con la información del CONTEXTO. Si no está, dilo."
    }

    /**
     * Arma los mensajes de una pregunta (con o sin RAG). Es lo que el AgentLoop
     * usa como base del bucle; separado de la ejecución para que el loop pueda
     * agregar las rondas de herramientas sin recalcular el contexto.
     */
    fun prepare(question: String, history: List<ChatMessage>, useDocs: Boolean): Prepared {
        if (!useDocs) {
            return Prepared(
                emptyList(),
                buildList {
                    add(ChatMessage("system", GENERAL_PROMPT))
                    addAll(history.takeLast(cfg.historyTurns * 2))
                    add(ChatMessage("user", question))
                },
            )
        }
        val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val hits = kb.retrieve("$question $lastUser", cfg.topK)

        val context = if (hits.isEmpty()) "(sin resultados en la documentación)" else
            hits.mapIndexed { i, c -> "[${i + 1}] (${c.label} — ${c.heading})\n${c.text}" }
                .joinToString("\n\n---\n\n")

        val messages = buildList {
            add(ChatMessage("system", systemPrompt()))
            addAll(history.takeLast(cfg.historyTurns * 2))
            add(ChatMessage("user", "CONTEXTO:\n$context\n\nPREGUNTA:\n$question"))
        }
        return Prepared(hits, messages)
    }

    /** Modo Docs: responde del contexto con citas (streaming de texto puro). */
    fun ask(question: String, history: List<ChatMessage>): Answer {
        val p = prepare(question, history, useDocs = true)
        return Answer(p.sources, llm.chatText(p.messages))
    }

    /**
     * Verifica las citas de una respuesta contra sus fuentes (F9).
     *
     * Determinista: no llama al modelo ni a la red. El verificador abre cada
     * fuente citada y comprueba que el fragmento esté respaldado por ella.
     */
    fun verify(answer: String, sources: List<Chunk>): CitationReport =
        CitationVerifier.verify(answer, sources)

    /**
     * Modo General: conversación libre SIN la restricción de responder sólo de
     * la documentación. El modelo usa su propio conocimiento; no hay fuentes
     * que citar. Es lo que permite charlar cuando la base está vacía.
     */
    fun askGeneral(question: String, history: List<ChatMessage>): Answer {
        val p = prepare(question, history, useDocs = false)
        return Answer(emptyList(), llm.chatText(p.messages))
    }

    companion object {
        /** Prompt del modo General (chatear sin depender de la documentación). */
        private val GENERAL_PROMPT = """
            Eres un asistente útil y conversacional.
            Responde en el mismo idioma de la pregunta, de forma clara y concisa.
            Si muestras código, usa bloques de código. No inventes fuentes ni citas.
        """.trimIndent()
    }
}
