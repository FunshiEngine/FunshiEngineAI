package rag.mcp

import kotlinx.serialization.Serializable

/**
 * Configuración de los servidores MCP (sección `mcp` de la config v4).
 *
 * El transporte implementado es **stdio** (F8): el servidor se lanza como
 * proceso hijo y se le habla JSON-RPC. Los demás transportes quedan
 * declarados, pero su estado dice que no están implementados.
 */
@Serializable
data class McpConfig(
    /** Servidores MCP declarados por el usuario. */
    val servers: List<McpServerConfig> = emptyList(),
    /** Timeout de cada petición MCP (handshake, tools/list, tools/call). */
    val timeoutMs: Long = 30_000,
)

/**
 * Un servidor MCP declarado en la configuración.
 *
 * @param id identificador estable y único (sin espacios): califica los
 *   nombres de las tools (`mcp__<id>__<tool>`).
 * @param transport "stdio" (proceso local) o "http" (servidor remoto).
 */
@Serializable
data class McpServerConfig(
    val id: String = "",
    /** Nombre para mostrar (si está vacío se usa el id). */
    val name: String = "",
    /** "stdio" (proceso local) o "http" (servidor remoto). */
    val transport: String = "stdio",
    /** Comando a lanzar (transporte stdio). */
    val command: String = "",
    /** Argumentos del comando (transporte stdio). */
    val args: List<String> = emptyList(),
    /** Variables de entorno extra del proceso (transporte stdio). */
    val env: Map<String, String> = emptyMap(),
    /** URL del servidor (transporte http). */
    val url: String = "",
    /** Si está apagado, el servidor no se conecta. */
    val enabled: Boolean = true,
) {
    /** Nombre para mostrar en la UI. */
    val displayName: String get() = name.ifBlank { id }

    /** ¿El servidor tiene lo mínimo para intentarlo? */
    val isUsable: Boolean
        get() = when (transport.lowercase()) {
            "stdio" -> command.isNotBlank()
            "http", "https", "sse" -> url.isNotBlank()
            else -> false
        }

    /** Problemas de la declaración (vacío = todo bien). */
    fun problems(): List<String> = buildList {
        if (id.isBlank()) add("Falta el id del servidor.")
        if (!isUsable) {
            add(
                if (transport.equals("stdio", ignoreCase = true)) {
                    "Falta el comando del servidor (transporte stdio)."
                } else {
                    "Falta la URL del servidor (transporte $transport)."
                },
            )
        }
    }
}
