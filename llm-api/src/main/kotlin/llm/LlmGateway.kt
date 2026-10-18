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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Contrato que usa el backend. No sabe si detrás hay llama.cpp, Ollama u OpenAI. */
interface LlmGateway {
    fun chatStream(messages: List<ChatMessage>): Flow<String>
    suspend fun chat(messages: List<ChatMessage>): String = chatStream(messages).toList().joinToString("")
    /** Lista los modelos del endpoint (`GET /models`); sirve para "Probar conexión". */
    suspend fun listModels(): List<String> = emptyList()
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
    /**
     * Nivel de razonamiento ("off" | "low" | "medium" | "high"). Si no es "off"
     * se envía `reasoning_effort` (Gemini 3, Nvidia, OpenAI, Grok lo soportan).
     */
    private val thinking: String = "off",
    /**
     * Potencia de respuesta ("low" | "medium" | "high"): ajusta `max_tokens`.
     * "medium" respeta [maxTokens] configurado; "low"/"high" lo fijan a 256/4096.
     */
    private val power: String = "medium",
) : LlmGateway {

    /** `max_tokens` efectivo según la potencia pedida (comando /power). */
    private val effectiveMaxTokens: Int = when (power.lowercase()) {
        "low" -> 256
        "high" -> maxOf(maxTokens, 4096)
        else -> maxTokens
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** true si el endpoint es loopback (llama-server, Ollama, LM Studio locales). */
    private val isLocalUrl: Boolean =
        baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") || baseUrl.contains("[::1]")

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 15_000
        }
    }

    /**
     * Error de transporte con contexto útil: incluye la URL y qué revisar.
     * Antes decía sólo "Fallo de red con la API: Connection refused: getsockopt",
     * que no le dice nada al usuario (típico: pegó la clave en el campo URL).
     */
    private fun transportError(e: Exception): LlmException {
        val detail = e.message ?: e.javaClass.simpleName
        return if (isLocalUrl) {
            LlmException(
                LlmErrorCode.SERVIDOR_CAIDO,
                "No se pudo hablar con el servidor local ($baseUrl): $detail",
                retryable = true, cause = e,
            )
        } else {
            LlmException(
                LlmErrorCode.MODELO_NO_DISPONIBLE,
                "No se pudo conectar con la API ($baseUrl): $detail. " +
                    "Revisá que la URL base sea correcta (debe empezar con http/https y terminar en /v1) " +
                    "y que el servicio esté accesible.",
                retryable = true, cause = e,
            )
        }
    }

    override fun chatStream(messages: List<ChatMessage>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            // Razonamiento (comando /thinking): sólo si el usuario lo encendió.
            if (thinking.isNotBlank() && !thinking.equals("off", ignoreCase = true)) {
                put("reasoning_effort", thinking.lowercase())
            }
            putJsonArray("messages") {
                messages.forEach { m ->
                    add(buildJsonObject { put("role", m.role); put("content", m.content) })
                }
            }
        }

        // Timeout total por petición (patrón de timeouts de SAI), también en streaming.
        // El timeout se reporta como TIMEOUT y los fallos de transporte como
        // MODELO_NO_DISPONIBLE/SERVIDOR_CAIDO (nunca como error crudo).
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
            throw transportError(e)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Prueba de conexión: pide `GET {baseUrl}/models` y devuelve los ids.
     * Es lo que usa el botón "Probar conexión" de la pantalla Motor, para que un
     * error de URL/clave se vea ANTES de escribir en el chat.
     */
    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val resp = client.get("${baseUrl.trimEnd('/')}/models") {
                if (apiKey.isNotBlank()) bearerAuth(apiKey)
            }
            if (!resp.status.isSuccess()) {
                throw LlmException.fromHttp(resp.status.value, resp.bodyAsText(), local = isLocalUrl)
            }
            val text = resp.bodyAsText()
            val arr = Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: return@withContext emptyList()
            arr.mapNotNull { el ->
                el.jsonObject["id"]?.let { v -> try { v.jsonPrimitive.content } catch (_: Exception) { null } }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw transportError(e)
        }
    }

    override fun close() = client.close()
}
