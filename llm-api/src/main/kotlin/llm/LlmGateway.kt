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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Contrato que usa el backend. No sabe si detrás hay llama.cpp, Ollama u OpenAI. */
interface LlmGateway {
    /**
     * Streaming de la respuesta, tipado: texto de a fragmentos y, si el modelo
     * pidió una herramienta, [LlmChunk.ToolCall] con la llamada completa.
     * [tools] opcional: catálogo de herramientas que el modelo puede pedir.
     */
    fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>? = null): Flow<LlmChunk>

    /** Sólo los fragmentos de texto (lo que pinta el chat sin herramientas). */
    fun chatText(messages: List<ChatMessage>): Flow<String> =
        chatStream(messages).map { chunk -> (chunk as? LlmChunk.Text)?.delta.orEmpty() }

    /** Respuesta completa (sin streaming). */
    suspend fun chat(messages: List<ChatMessage>): String =
        chatText(messages).toList().joinToString("")

    /** Lista los modelos del endpoint (`GET /models`); sirve para "Probar conexión". */
    suspend fun listModels(): List<String> = emptyList()
    fun close() {}
}

/**
 * Cliente para cualquier endpoint /v1/chat/completions con streaming SSE.
 *
 * Soporte de herramientas:
 *  - Si recibe [tools], los envía en el body (`tools[]`, formato OpenAI) y
 *    acumula los `delta.tool_calls` fragmentados del stream para emitirlos como
 *    [LlmChunk.ToolCall] al terminar ([DONE] o cierre del stream).
 *  - Los mensajes `role=tool` (resultados) y `role=assistant` con `tool_calls`
 *    se serializan según el protocolo OpenAI.
 *
 * Errores tipados ([LlmException]) clasificando status HTTP (401→clave,
 * 429→cuota, 400+tokens→contexto, 5xx→servidor) en vez de `error(...)` crudo.
 * Timeout por petición opcional (`chatTimeoutMs`; 0 = sin límite, que es
 * lo por defecto) sobre el flujo completo.
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

    /** Convierte un ToolSpec al formato `tools[]` que espera el protocolo OpenAI. */
    private fun toolToBody(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", json.parseToJsonElement(spec.parametersSchema))
        })
    }

    /** Ensambla una conversación a JSON: system/user/assistant/tool con sus extras. */
    private fun messageToBody(m: ChatMessage): JsonObject = buildJsonObject {
        put("role", m.role)
        // Assistant con tool_calls y sin texto: content se omite (algunos
        // proveedores lo exigen). Resto de roles: siempre content.
        val omitContent = m.role == "assistant" && m.content.isEmpty() && m.toolCalls != null
        if (!omitContent) put("content", m.content)
        m.toolCalls?.let { put("tool_calls", it) }
        m.toolCallId?.let { put("tool_call_id", it) }
        m.toolName?.let { put("name", it) }
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

    override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>?): Flow<LlmChunk> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            // Razonamiento (comando /thinking): sólo si el usuario lo encendió.
            if (thinking.isNotBlank() && !thinking.equals("off", ignoreCase = true)) {
                put("reasoning_effort", thinking.lowercase())
            }
            // Catálogo de herramientas: sólo si el caller las pasó.
            if (!tools.isNullOrEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(toolToBody(it)) }
                }
            }
            putJsonArray("messages") {
                messages.forEach { add(messageToBody(it)) }
            }
        }

        /**
         * Acumulación de tool_calls fragmentados: cada delta puede traer un
         * trocito de `arguments` (u `id`/`name` en el primer delta de esa call).
         * Los chunks completos se emiten al terminar el stream.
         */
        data class PendingCall(
            val id: StringBuilder = StringBuilder(),
            val name: StringBuilder = StringBuilder(),
            val args: StringBuilder = StringBuilder(),
        )
        val pending = LinkedHashMap<Int, PendingCall>()

        // Timeout opcional por petición, también en streaming: con
        // chatTimeoutMs = 0 (por defecto) no hay límite de tiempo y una
        // respuesta larga no se corta. Los errores de timeout se reportan
        // como TIMEOUT y los fallos de transporte como
        // MODELO_NO_DISPONIBLE/SERVIDOR_CAIDO (nunca como error crudo).
        val request: suspend () -> Unit = {
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
                    val choice = json.parseToJsonElement(data).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                    val delta = choice["delta"]?.jsonObject

                    // Fragmentos de texto normal.
                    delta?.get("content")?.let { c ->
                        val token = (c as? JsonPrimitive)?.contentOrNull
                        if (!token.isNullOrEmpty()) emit(LlmChunk.Text(token))
                    }

                    // Fragmentos de tool calls: se acumulan por index.
                    delta?.get("tool_calls")?.let { rawEl ->
                        val arr = rawEl as? JsonArray
                        arr?.forEach { item ->
                            val tc = item as? JsonObject
                            if (tc == null) return@forEach
                            val idx = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                            val p = pending.getOrPut(idx) { PendingCall() }
                            (tc["id"] as? JsonPrimitive)?.contentOrNull?.let { p.id.append(it) }
                            (tc["function"] as? JsonObject)?.let { fn ->
                                (fn["name"] as? JsonPrimitive)?.contentOrNull?.let { p.name.append(it) }
                                (fn["arguments"] as? JsonPrimitive)?.contentOrNull?.let { p.args.append(it) }
                            }
                        }
                    }
                }
            }
        }
        try {
            if (chatTimeoutMs > 0) withTimeout(chatTimeoutMs) { request() } else request()
        } catch (e: LlmException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw LlmException(LlmErrorCode.TIMEOUT, "La petición superó $chatTimeoutMs ms.", retryable = true, cause = e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancelación real del usuario: no la convertimos en error
        } catch (e: Exception) {
            throw transportError(e)
        }

        // Terminó el stream: emitir las tool calls acumuladas (una por call).
        pending.entries.sortedBy { it.key }.forEach { (_, p) ->
            val name = p.name.toString().trim()
            if (name.isNotEmpty()) {
                emit(
                    LlmChunk.ToolCall(
                        id = p.id.toString().ifBlank { name },
                        name = name,
                        argsJson = p.args.toString().ifBlank { "{}" },
                    ),
                )
            }
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
