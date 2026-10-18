package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import llm.ChatMessage
import llm.LlmService
import rag.AppConfig
import rag.AppPaths
import rag.KnowledgeBase
import rag.RagEngine

data class UiMessage(val role: String, val text: String, val sources: List<String> = emptyList())

class ChatViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val config = AppConfig.loadOrCreate()
    private val llmService = LlmService(config.llm, AppPaths.home)
    private val kb = KnowledgeBase(config.rag)
    private var engine: RagEngine? = null

    var messages by mutableStateOf(listOf<UiMessage>())
        private set
    var status by mutableStateOf("Iniciando…")
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        scope.launch { boot() }
    }

    private suspend fun boot() {
        try {
            kb.sync { status = it }
            val llm = llmService.start { status = it }
            engine = RagEngine(config.rag, kb, llm)
            ready = true
            status = "Listo · ${kb.chunkCount} fragmentos indexados · ${config.llm.mode}"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Error: ${e.message}"
        }
    }

    fun reindex() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                kb.sync { status = it }
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun send(text: String) {
        val eng = engine ?: return
        if (text.isBlank() || busy) return
        val history = messages.filter { it.text.isNotEmpty() }.map { ChatMessage(it.role, it.text) }
        messages = messages + UiMessage("user", text) + UiMessage("assistant", "")
        busy = true
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { eng.ask(text, history) }
                val sb = StringBuilder()
                answer.tokens.collect { sb.append(it); updateLast(sb.toString()) }
                updateLast(sb.toString(), answer.sources.map { it.label }.distinct())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateLast("⚠ ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun updateLast(text: String, sources: List<String> = emptyList()) {
        messages = messages.dropLast(1) + messages.last().copy(text = text, sources = sources)
    }

    fun shutdown() {
        scope.cancel()
        llmService.stop()
    }
}
