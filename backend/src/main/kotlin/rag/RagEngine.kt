package rag

import kotlinx.coroutines.flow.Flow
import llm.ChatMessage
import llm.LlmGateway
import java.nio.file.Files

class Answer(val sources: List<Chunk>, val tokens: Flow<String>)

class RagEngine(
    private val cfg: RagConfig,
    private val kb: KnowledgeBase,
    private val llm: LlmGateway
) {
    /** Se lee en cada pregunta: puedes editar el archivo sin reiniciar la app. */
    private fun systemPrompt(): String {
        val file = AppPaths.resolve(cfg.systemPromptFile)
        return if (Files.exists(file)) Files.readString(file)
        else "Responde únicamente con la información del CONTEXTO. Si no está, dilo."
    }

    /** [history] contiene solo pregunta/respuesta "limpias" (sin el contexto inyectado). */
    fun ask(question: String, history: List<ChatMessage>): Answer {
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
        return Answer(hits, llm.chatStream(messages))
    }
}
