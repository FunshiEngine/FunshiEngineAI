package rag.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Capa de protocolo MCP: mensajes JSON-RPC 2.0 (F8).
 *
 * Es una capa **pura** (sin IO): construir y leer mensajes se puede testear
 * sin levantar ningún proceso. El transporte (stdio) está en
 * [McpStdioConnector]; el contrato de la app, en [McpConnection].
 *
 * Lo que se implementa es el núcleo del protocolo: inicialización
 * (`initialize` + `notifications/initialized`), descubrimiento de herramientas
 * (`tools/list`) e invocación (`tools/call`). Es lo que la app necesita para
 * exponer un servidor MCP como herramientas más del chat.
 */
internal object McpProtocol {

    /** Versión de JSON-RPC que usa MCP. */
    const val JSONRPC = "2.0"

    /**
     * Versión del protocolo que pide el cliente en `initialize`.
     *
     * El servidor puede responder con otra: si no la conocemos seguimos
     * igual (las tools son estables entre versiones), pero queda a la vista
     * en el estado del servidor.
     */
    const val CLIENT_PROTOCOL_VERSION = "2025-06-18"

    /** Versiones del protocolo que el cliente conoce de punta a punta. */
    val KNOWN_PROTOCOL_VERSIONS = setOf(
        "2024-11-05",
        "2025-03-26",
        "2025-06-18",
        "2025-11-25",
    )

    /**
     * JSON **compacto**: el protocolo exige un mensaje por línea, así que
     * nunca puede ir con saltos de línea (ni indentación).
     */
    val json = Json { ignoreUnknownKeys = true }

    /** Petición JSON-RPC (con id: el servidor tiene que responder). */
    fun request(id: Int, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", JSONRPC)
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    /** Notificación JSON-RPC (sin id: no espera respuesta). */
    fun notification(method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", JSONRPC)
        put("method", method)
        if (params != null) put("params", params)
    }

    /** Serializa un mensaje a una línea (sin saltos de línea). */
    fun encode(message: JsonObject): String = json.encodeToString(JsonElement.serializer(), message)

    /**
     * Convierte una línea en mensaje JSON-RPC.
     *
     * Devuelve `null` si la línea no es un mensaje válido: algunos servidores
     * escriben logs en stdout (el protocolo dice que no deberían, pero pasa)
     * y esas líneas se ignoran en vez de romper la conexión.
     */
    fun parse(line: String): JsonObject? {
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return null
        val obj = element as? JsonObject ?: return null
        val tieneId = obj.containsKey("id")
        val tieneResultado = obj.containsKey("result") || obj.containsKey("error")
        val tieneMetodo = obj.containsKey("method")
        if (!tieneId && !tieneResultado && !tieneMetodo) return null
        return obj
    }

    /** Método del mensaje (si lo tiene). */
    fun methodOf(message: JsonObject): String? = stringOf(message, "method")

    /** Id del mensaje (si lo tiene). */
    fun idOf(message: JsonObject): Int? = intOf(message, "id")

    /** Texto de un campo (null si no es un string). */
    fun stringOf(message: JsonObject, key: String): String? =
        (message[key] as? JsonPrimitive)?.contentOrNull

    /** Entero de un campo (null si no es un número entero). */
    fun intOf(message: JsonObject, key: String): Int? =
        (message[key] as? JsonPrimitive)?.content?.toIntOrNull()

    /** Booleano de un campo (null si no es booleano). */
    fun booleanOf(message: JsonObject, key: String): Boolean? =
        (message[key] as? JsonPrimitive)?.booleanOrNull
}
