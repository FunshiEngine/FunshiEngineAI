package rag.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rag.agent.Tool

/**
 * Abre una conexión con un servidor MCP declarado en la configuración.
 *
 * Es EL seam del transporte: la app no sabe cómo se habla MCP, sólo pide
 * conexiones. Cuando llegue el SDK oficial, se implementa esta interfaz
 * (stdio o HTTP) y el resto de la app no cambia.
 */
fun interface McpConnector {
    /** Abre la conexión. Si no puede, lanza una excepción con el motivo. */
    suspend fun connect(server: McpServerConfig): McpConnection
}

/**
 * Conector por defecto: todavía NO hay transporte MCP.
 *
 * Los servidores se pueden declarar en la configuración, pero no se conectan:
 * la razón se muestra en el estado, así el usuario no cree que hay un
 * servidor conectado que no responde.
 */
object PendingMcpConnector : McpConnector {
    override suspend fun connect(server: McpServerConfig): McpConnection = throw McpUnavailableException(
        "El transporte MCP todavía no está implementado: falta el cliente oficial " +
            "(SDK de Kotlin). El servidor '${server.displayName}' queda declarado pero no conectado.",
    )
}

/** Error de transporte MCP: la conexión no se pudo abrir. */
class McpUnavailableException(message: String) : Exception(message)

/** Estado de un servidor MCP para mostrarlo en la UI. */
data class McpServerStatus(
    val serverId: String,
    val displayName: String,
    /** ¿Se pudo conectar y listar sus herramientas? */
    val connected: Boolean,
    /** Cuántas herramientas expone (0 si no se conectó). */
    val toolCount: Int = 0,
    /** Motivo del fallo (vacío si todo bien). */
    val error: String = "",
)

/**
 * Administra los servidores MCP y expone sus herramientas como [Tool].
 *
 * El manager no sabe de transporte: pide conexiones al [McpConnector] y de
 * ahí arma los adaptadores. Así se puede probar con dobles sin ningún
 * servidor real, y el día que haya SDK sólo cambia el conector.
 */
class McpManager(
    private val connector: McpConnector = PendingMcpConnector,
    private val servers: List<McpServerConfig> = emptyList(),
) {

    private val mutex = Mutex()

    /** Adaptadores de las herramientas descubiertas (vacío hasta el primer refresh). */
    private var adapters: List<Tool> = emptyList()

    /** Conexiones abiertas (se cierran al apagar). */
    private var connections: List<McpConnection> = emptyList()

    /** Estado por servidor (para la UI). */
    private var statuses: List<McpServerStatus> = emptyList()

    /** Herramientas MCP listas para registrar. */
    fun tools(): List<Tool> = adapters

    /** Estado de cada servidor declarado. */
    fun statuses(): List<McpServerStatus> = statuses

    /**
     * Conecta con los servidores habilitados y vuelve a listar sus
     * herramientas. Un servidor que falla no impide que los demás funcionen:
     * su error queda en el estado.
     */
    suspend fun refresh(): List<Tool> = mutex.withLock {
        val nuevos = mutableListOf<Tool>()
        val estados = mutableListOf<McpServerStatus>()
        val abiertas = mutableListOf<McpConnection>()

        for (server in servers) {
            if (!server.enabled) {
                estados += McpServerStatus(
                    serverId = server.id,
                    displayName = server.displayName,
                    connected = false,
                    error = "Deshabilitado en la configuración.",
                )
                continue
            }

            val problemas = server.problems()
            if (problemas.isNotEmpty()) {
                estados += McpServerStatus(
                    serverId = server.id,
                    displayName = server.displayName,
                    connected = false,
                    error = problemas.joinToString(" "),
                )
                continue
            }

            try {
                val connection = connector.connect(server)
                val descriptores = connection.listTools()
                abiertas += connection
                nuevos += descriptores.map { McpToolAdapter(connection, it) }
                estados += McpServerStatus(
                    serverId = server.id,
                    displayName = server.displayName,
                    connected = true,
                    toolCount = descriptores.size,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                estados += McpServerStatus(
                    serverId = server.id,
                    displayName = server.displayName,
                    connected = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }

        // Las conexiones viejas se cierran antes de abrir las nuevas.
        closeAll(connections)
        connections = abiertas
        adapters = nuevos
        statuses = estados
        nuevos
    }

    /** Cierra todas las conexiones abiertas. */
    suspend fun close() = mutex.withLock {
        closeAll(connections)
        connections = emptyList()
        adapters = emptyList()
        statuses = emptyList()
    }

    /** Cierra las conexiones sin propagar fallos (apagar no puede fallar). */
    private suspend fun closeAll(lista: List<McpConnection>) {
        for (connection in lista) {
            try {
                connection.close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Al apagar, un servidor que no cierra bien no puede romper la app.
            }
        }
    }
}
