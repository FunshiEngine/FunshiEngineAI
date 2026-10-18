package llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Contrato que usa el backend. No sabe si detrás hay llama.cpp, Ollama u OpenAI. */
interface LlmGateway {
    fun chatStream(messages: List<ChatMessage>): Flow<String>
    suspend fun chat(messages: List<ChatMessage>): String = chatStream(messages).toList().joinToString("")
    fun close() {}
}

/**
 * Cliente para cualquier endpoint /v1/chat/completions con streaming SSE.
 *
 * Mejoras tomadas del backend de SAI:
 *  - Errores tipados ([LlmException]) clasificando status HTTP (401→clave,
 *    429→cuota, 400+tokens→contexto, 5xx→servidor) en vez de `error(...)` crudo.
 *  - Timeout por petición (`chatTimeoutMs`, 90 s como SAI) sobre el flujo completo,
 *    para que un servidor colgado no deje la petición zombi.
 */
class OpenAiCompatibleGateway(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val temperature: Double,
    private val maxTokens: Int,
    private val chatTimeoutMs: Long,
) : LlmGateway {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
        }
    }

    override fun chatStream(messages: List<ChatMessage>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            }
        }

        // Timeout total por petición (patrón de timeouts de SAI), también en streaming.
        // El timeout se reporta como TIMEOUT y los fallos de transporte como
        // MODELO_NO_DISPONIBLE/SERVIDOR_CAIDO (nunca como error crudo).
        // Nota: `local` es false para REMOTE; en LOCAL el LlmService usa este mismo
        // gateway contra llama-server, que también es HTTP local.
        val isLocal = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")
        try {
            withTimeout(chatTimeoutMs) {
                client.preparePost("${baseUrl.trimEnd('/')}/chat/completions") {
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) bearerAuth(apiKey)
                    setBody(body.toString())
                }.execute { resp ->
                    if (!resp.status.isSuccess()) {
                        throw LlmException.fromHttp(resp.status.value, resp.bodyAsText(), local = false)
                    }
                    val channel = resp.bodyAsChannel()
                    while (true) {
                        val line = channel.readUTF8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val token = json.parseToJsonElement(data).jsonObject["choices"]
                            ?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("delta")?.jsonObject
                            ?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!token.isNullOrEmpty()) emit(token)
                    }
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw LlmException(LlmErrorCode.TIMEOUT, "La petición superó $chatTimeoutMs ms.", retryable = true, cause = e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancelación real del usuario: no la convertimos en error
        } catch (e: Exception) {
            throw LlmException.fromNetwork(e, local = isLocal)
        }
    }.flowOn(Dispatchers.IO)

    override fun close() = client.close()
}
