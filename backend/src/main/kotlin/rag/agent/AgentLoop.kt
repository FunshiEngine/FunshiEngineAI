package rag.agent

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import llm.api.LlmChunk
import llm.api.LlmGateway
import llm.api.ToolSpec
import llm.config.ChatMessage

/** Eventos del loop, para la UI (texto en vivo y tarjetas de herramientas). */
sealed class AgentEvent {
    /** Fragmento de texto para mostrar en el chat. */
    data class Text(val delta: String) : AgentEvent()

    /** Una herramienta va a ejecutarse (tarjeta "en curso"). */
    data class ToolStart(val callId: String, val name: String, val argsJson: String) : AgentEvent()

    /** Una herramienta terminó (tarjeta OK / Falló). */
    data class ToolEnd(
        val callId: String,
        val name: String,
        val ok: Boolean,
        val result: String,
        /** Archivos que produjo la herramienta (se muestran como adjuntos). */
        val artifacts: List<Artifact> = emptyList(),
    ) : AgentEvent()
}

/** Una herramienta ya ejecutada dentro de la respuesta (para las tarjetas UI). */
data class ExecutedTool(
    val callId: String,
    val name: String,
    val argsJson: String,
    val ok: Boolean,
    val result: String,
    /** Archivos que produjo la herramienta (adjuntos del mensaje). */
    val artifacts: List<Artifact> = emptyList(),
)

/** Respuesta completa del loop: texto visible + herramientas usadas. */
data class AgentAnswer(
    val text: String,
    val toolCalls: List<ExecutedTool> = emptyList(),
)

/**
 * Bucle de tool calling: pide al modelo, y si éste pide una herramienta la
 * ejecuta y le pasa el resultado en la siguiente ronda, hasta responder con
 * texto puro o agotar el límite de iteraciones.
 *
 * Dos modos, elegidos por el caller según el motor:
 *  - [nativeToolCalls] = true (REMOTO OpenAI-compatible): el catálogo va en el
 *    body (`tools[]`) y las llamadas llegan como chunks [LlmChunk.ToolCall].
 *  - false (LOCAL con Phi-4-mini en llama-server): el catálogo se inyecta en el
 *    system prompt con un protocolo de bloque delimitado
 *    (```tool_call {json} ```) que el loop extrae del stream por sí mismo.
 *
 * Reglas tomadas del bucle de predicción de SAI (`.agentes/plan-sai-vs-funshi.md`):
 *  - **Nunca propaga errores transitorios**: JSON roto, herramienta desconocida
 *    o fallo de ejecución consumen una ronda y generan FEEDBACK al modelo para
 *    que lo reintente; se devuelve lo acumulado igual.
 *  - **La cancelación externa sí se propaga** (el "Detener" de la UI corta todo
 *    el bucle: `CancellationException` no se traga).
 *  - Tope de reintentos de sanitización y de fallos consecutivos: al agotarse
 *    el bucle devuelve el texto visible hasta ese punto (nunca pierde todo).
 *
 * Sanitización: toda llamada pasa por [ToolCallSanitizer] antes de ejecutarse.
 */
class AgentLoop(
    private val gateway: LlmGateway,
    private val registry: ToolRegistry,
    private val nativeToolCalls: Boolean,
    /**
     * Timeout por herramienta, en milisegundos (0 = sin límite). Viene de la
     * sección `tools` de la config: una tool colgada no puede dejar al chat
     * esperando para siempre.
     */
    private val toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,
) {

    /** Máximo de rondas modelo+herramienta por mensaje (corte de seguridad). */
    var maxIterations: Int = 8

    /**
     * Ejecuta el bucle sobre [messages] (ya con system/contexto/pregunta) y
     * reporta eventos por [onEvent]. Devuelve la respuesta final.
     */
    suspend fun run(
        messages: List<ChatMessage>,
        ctx: ToolContext,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentAnswer {
        if (registry.isEmpty) {
            return textOnly(messages, onEvent)
        }
        val used = mutableListOf<ExecutedTool>()
        val work = if (nativeToolCalls) messages.toMutableList() else injectCatalog(messages).toMutableList()
        val visible = StringBuilder()
        var sanitizeFails = 0
        var consecutiveToolErrors = 0
        /** Veces que el usuario denegó una herramienta en este mensaje. */
        var denials = 0

        for (iteration in 1..maxIterations) {
            var call: LlmChunk.ToolCall? = null
            val promptParser = if (nativeToolCalls) null else PromptToolParser()

            gateway.chatStream(work, if (nativeToolCalls) registry.specs() else null).collect { chunk ->
                when (chunk) {
                    is LlmChunk.Text -> {
                        val shown = if (promptParser != null) promptParser.feed(chunk.delta) else chunk.delta
                        if (shown.isNotEmpty()) {
                            visible.append(shown)
                            onEvent(AgentEvent.Text(shown))
                        }
                    }
                    is LlmChunk.ToolCall -> call = chunk
                }
            }
            // Stream cerrado: soltar lo retenido (texto parcial sin newline final).
            promptParser?.flush()?.let { tail ->
                if (tail.isNotEmpty()) {
                    visible.append(tail)
                    onEvent(AgentEvent.Text(tail))
                }
            }

            // ¿El modelo pidió una herramienta esta ronda? (nativa o por bloque)
            val rawCall = if (promptParser != null) {
                promptParser.drain().mapNotNull { RawCall.block(it) }.firstOrNull()
            } else {
                call?.let { RawCall.native(it) }
            } ?: break // ronda final: respondió sin herramientas

            // SANITIZACIÓN: reparar (JSON roto, fences, comas colgantes...) o
            // generar feedback para que el modelo lo reintente.
            when (val sanitized = sanitizeFrom(rawCall)) {
                is SanitizedToolCall.Valid -> {
                    val invocation = LlmChunk.ToolCall(
                        id = sanitized.id.ifBlank { sanitized.name },
                        name = sanitized.name,
                        argsJson = sanitized.args.toString(),
                    )
                    onEvent(AgentEvent.ToolStart(invocation.id, invocation.name, invocation.argsJson))

                    // PERMISO: por categoría, apagado por defecto, concedido por
                    // chat. Si el usuario ya denegó una vez en este mensaje, no
                    // se vuelve a preguntar (el diálogo no se repite).
                    val tool = registry.byName(invocation.name)
                    val denied = tool != null && (
                        if (denials > 0) {
                            PermissionDecision.DENY
                        } else {
                            ToolPermissions.authorize(tool, invocation.argsJson, ctx)
                        }
                    ) == PermissionDecision.DENY
                    if (denied) denials++

                    val result = if (denied) {
                        ToolResult.Error(
                            "El usuario NO autorizó la herramienta `${invocation.name}`. " +
                                "No la vuelvas a pedir: continuá la conversación con lo que ya tenés " +
                                "o explicá que no pudiste hacerlo.",
                        )
                    } else {
                        execute(invocation, ctx)
                    }
                    val artifacts = (result as? ToolResult.Ok)?.artifacts.orEmpty()
                    onEvent(AgentEvent.ToolEnd(invocation.id, invocation.name, result.ok, result.render(), artifacts))
                    used += ExecutedTool(
                        invocation.id, invocation.name, invocation.argsJson, result.ok, result.render(), artifacts,
                    )

                    if (!result.ok && !denied) {
                        consecutiveToolErrors++
                        if (consecutiveToolErrors >= MAX_CONSECUTIVE_TOOL_ERRORS) break
                    } else if (result.ok) {
                        // Sólo un ÉXITO corta la racha de errores. Antes el
                        // contador se ponía en cero al empezar la ronda, así que
                        // nunca llegaba al tope y el bucle seguía hasta
                        // maxIterations con la tool siempre fallando.
                        consecutiveToolErrors = 0
                    }

                    // Siguiente ronda: el modelo ve su llamada y el resultado.
                    work += followUpMessages(invocation, result, nativeToolCalls)
                    // Tras un permiso denegado, si el modelo insiste se corta:
                    // no se vuelve a abrir el diálogo ni a preguntar.
                    if (denied && denials >= 2) break
                }
                is SanitizedToolCall.Invalid -> {
                    sanitizeFails++
                    onEvent(AgentEvent.ToolStart("tool_call", "tool_call", rawCall.summary()))
                    onEvent(AgentEvent.ToolEnd("tool_call", "tool_call", false, sanitized.reason))
                    used += ExecutedTool("tool_call", "tool_call", rawCall.summary(), false, sanitized.reason)
                    // Feedback: como en el bucle predictor, el fallo NO corta el
                    // bucle: consume una ronda y se reintenta con la guía exacta.
                    work += sanitizeRetryFeedback(sanitized.reason)
                    if (sanitizeFails > MAX_SANITIZE_RETRIES) break
                }
            }
        }
        return AgentAnswer(visible.toString(), used)
    }

    /** Llamada cruda según el modo: el mismo contraste pasa por el sanitizador. */
    private sealed class RawCall {
        abstract fun summary(): String

        data class Native(val call: LlmChunk.ToolCall) : RawCall() {
            override fun summary(): String = call.argsJson
        }

        data class Block(val content: String) : RawCall() {
            override fun summary(): String = content.take(240)
        }

        companion object {
            fun native(c: LlmChunk.ToolCall): RawCall = Native(c)
            fun block(content: String): RawCall? {
                val clean = content.trim()
                return if (clean.isEmpty()) null else Block(clean)
            }
        }
    }

    /** Envía la llamada al sanitizador según el tipo crudo. */
    private fun sanitizeFrom(raw: RawCall): SanitizedToolCall = when (raw) {
        is RawCall.Native -> ToolCallSanitizer.args(raw.call.name, raw.call.argsJson)
        is RawCall.Block -> ToolCallSanitizer.block(raw.content)
    }

    /** Inyecta el catálogo de herramientas en el system prompt del turno. */
    private fun injectCatalog(messages: List<ChatMessage>): List<ChatMessage> {
        val catalog = promptCatalog(registry.specs())
        val idx = messages.indexOfFirst { it.role == "system" }
        if (idx >= 0) {
            return messages.mapIndexed { i, m ->
                if (i == idx) m.copy(content = m.content + "\n\n" + catalog) else m
            }
        }
        return listOf(ChatMessage("system", catalog)) + messages
    }

    /** Un solo pase sin herramientas: streaming directo, sin protocolos. */
    private suspend fun textOnly(messages: List<ChatMessage>, onEvent: (AgentEvent) -> Unit): AgentAnswer {
        val text = StringBuilder()
        gateway.chatStream(messages, null).collect { chunk ->
            if (chunk is LlmChunk.Text) {
                text.append(chunk.delta)
                onEvent(AgentEvent.Text(chunk.delta))
            }
        }
        return AgentAnswer(text.toString())
    }

    /**
     * Ejecuta una llamada reparada, validando nombre y argumentos.
     * El permiso ya se verificó antes de llegar acá (en el bucle): aquí sólo
     * se ejecuta y se traducen los fallos a un error controlado.
     */
    private suspend fun execute(call: LlmChunk.ToolCall, ctx: ToolContext): ToolResult {
        val tool = registry.byName(call.name)
            ?: return ToolResult.Error("Herramienta desconocida: ${call.name}. Disponibles: " +
                registry.specs().joinToString(", ") { it.name })
        val args = try {
            JsonOf(call.argsJson)
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        return try {
            if (toolTimeoutMs <= 0) {
                tool.execute(args, ctx)
            } else {
                withTimeout(toolTimeoutMs) { tool.execute(args, ctx) }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // OJO: el timeout es una subclase de CancellationException, así que
            // tiene que atraparse ANTES que ella (si no, se propaga y se corta
            // el chat entero en vez de devolverle un error al modelo).
            ToolResult.Error(
                "La herramienta ${call.name} tardó más de ${toolTimeoutMs / 1000} segundos y se cortó. " +
                    "Probá con otra herramienta o con menos datos.",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error("Fallo al ejecutar ${call.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun JsonOf(raw: String): JsonObject {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(raw.ifBlank { "{}" })
        return el as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Mensajes para la ronda siguiente: la llamada y su resultado, según modo. */
    private fun followUpMessages(
        call: LlmChunk.ToolCall,
        result: ToolResult,
        withNativeProtocol: Boolean,
    ): List<ChatMessage> {
        val visibleResult = result.render().take(MAX_RESULT_CHARS)
        return if (withNativeProtocol) {
            val jsonCall = buildJsonArray {
                add(buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", call.name)
                        put("arguments", call.argsJson.ifBlank { "{}" })
                    })
                })
            }
            listOf(
                ChatMessage("assistant", "", toolCalls = jsonCall),
                ChatMessage("tool", visibleResult, toolCallId = call.id, toolName = call.name),
            )
        } else {
            // Protocolo por prompt: el digest va como "user" porque los chat
            // templates de modelos chicos suelen no entender `role=tool`.
            listOf(
                ChatMessage("assistant", "```tool_call\n${call.argsJson.withName(call.name)}\n```"),
                ChatMessage(
                    "user",
                    "RESULTADO DE LA HERRAMIENTA ${call.name}:\n$visibleResult\n" +
                        "Continuá la conversación para el usuario con esta información.",
                ),
            )
        }
    }

    /** Feedback determinista cuando el JSON no se pudo reparar (regla SAI). */
    private fun sanitizeRetryFeedback(reason: String): List<ChatMessage> = listOf(
        ChatMessage(
            "user",
            "RESULTADO: el bloque ```tool_call no es JSON válido. Motivo: $reason\n\n" +
                "Reintentá respondiendo SOLO con el bloque exacto:\n" +
                "```tool_call\n{\"tool\":\"nombre\",\"arguments\":{...}}\n```\n" +
                "Si NO necesitás una herramienta, respondé al usuario normalmente, sin bloque.",
        ),
    )

    /** Joins nombre+argumentos al formato persistido de bloque delimitado. */
    private fun String.withName(name: String): String = try {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(this.ifBlank { "{}" })
        val obj = el as? kotlinx.serialization.json.JsonObject
            ?: kotlinx.serialization.json.JsonObject(emptyMap())
        kotlinx.serialization.json.JsonObject(obj + ("tool" to kotlinx.serialization.json.JsonPrimitive(name))).toString()
    } catch (_: Exception) {
        "{\"tool\":\"$name\"}"
    }

    companion object {
        /** Tope de caracteres del resultado que vuelve al modelo. */
        const val MAX_RESULT_CHARS = 4000
        /** Reintentos de sanitización por mensaje antes de rendirse. */
        const val MAX_SANITIZE_RETRIES = 2
        /** Fallos de herramienta consecutivos antes de cortar el bucle. */
        const val MAX_CONSECUTIVE_TOOL_ERRORS = 3
        /** Timeout por defecto de una herramienta (ms). */
        const val DEFAULT_TOOL_TIMEOUT_MS = 60_000L

        /**
         * Protocolo del modo por prompt: se anexa al system prompt del turno.
         * Delimitador exacto: una línea ```tool_call seguida de JSON en una
         * línea y el cierre ``` — nada más (el parser es estricto con esto).
         */
        fun promptCatalog(specs: List<ToolSpec>): String = buildString {
            appendLine("HERRAMIENTAS DISPONIBLES:")
            specs.forEach { s ->
                appendLine("- ${s.name}: ${s.description}")
                appendLine("  Argumentos JSON: ${s.parametersSchema}")
            }
            appendLine("PARA USAR UNA HERRAMIENTA, respondé SOLO con este bloque exacto y nada más:")
            appendLine("```tool_call")
            appendLine("{\"tool\":\"nombre\",\"arguments\":{...}}")
            appendLine("```")
            appendLine("Cuando recibas el RESULTADO DE LA HERRAMIENTA, respondé al usuario normalmente.")
            appendLine("Si NO necesitás una herramienta, respondé normalmente, sin ningún bloque.")
        }
    }
}

/**
 * Parser incremental del modo por prompt: recorta del stream los bloques
 * ```tool_call ... ``` (línea de apertura exacta, cierre en línea propia) y de
 * todo lo demás emite el texto tal cual.
 *
 * Línea-buffered: el texto visible se emite por líneas (el modo nativo sigue
 * con emisión por token; este parser es sólo del camino LOCAL). Tolerante a
 * stream cortado: los bloques incompletos al final se descartan en silencio.
 */
class PromptToolParser {
    private val pendingLine = StringBuilder()
    private var inTool = false
    private val toolBuf = StringBuilder()
    private val completed = mutableListOf<String>()

    /** Procesa un delta y devuelve el texto visible que ya se puede mostrar. */
    fun feed(delta: String): String {
        val out = StringBuilder()
        for (ch in delta) {
            if (ch != '\n') {
                pendingLine.append(ch)
                continue
            }
            pendingLine.append(ch)
            val line = pendingLine.toString()
            pendingLine.setLength(0)
            out.append(consumeLine(line))
        }
        // Cortesía de streaming: si lo acumulado ya NO puede ser la apertura,
        // lo soltamos tal cual (sino queda retenido hasta el newline).
        if (!inTool && pendingLine.isNotEmpty()) {
            val t = pendingLine.toString().trim()
            if (!OPENER.startsWith(t)) {
                out.append(pendingLine)
                pendingLine.setLength(0)
            }
        }
        return out.toString()
    }

    /** Texto retenido al terminar el stream (línea final sin newline). */
    fun flush(): String {
        val tail = pendingLine.toString()
        pendingLine.setLength(0)
        return tail
    }

    /** Devuelve los bloques completos desde la última lectura y limpia. */
    fun drain(): List<String> {
        val out = completed.toList()
        completed.clear()
        return out
    }

    private fun consumeLine(line: String): String {
        val t = line.trim()
        if (inTool) {
            if (t == "```") {
                inTool = false
                completed += toolBuf.toString().trim()
                toolBuf.setLength(0)
            } else {
                toolBuf.append(line)
            }
            return ""
        }
        if (t == OPENER) {
            inTool = true
            return ""
        }
        return line
    }

    companion object {
        private const val OPENER = "```tool_call"
    }
}
