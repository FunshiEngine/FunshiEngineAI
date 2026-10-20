package app.chat

import kotlinx.coroutines.launch
import rag.agent.Tool
import rag.agent.ToolOrigin
import rag.agent.tools.CreateFileTool
import rag.agent.tools.DownloadUrlTool
import rag.agent.tools.GetTimeTool
import rag.agent.tools.LatexCompileTool
import rag.agent.tools.WebFetchTool
import rag.agent.tools.WebSearchTool
import rag.mcp.McpManager
import rag.mcp.McpServerConfig
import rag.mcp.PendingMcpConnector

/**
 * Herramientas del chat (F1–F7).
 *
 * El registro se arma a partir de la configuración, así que los topes, el
 * motor de LaTeX y el interruptor general salen de `app-config.json` y no de
 * constantes en el código.
 *
 * Son funciones de extensión sobre el ViewModel (mismo patrón que el resto de
 * áreas): el ViewModel sigue siendo el dueño del estado.
 */

/** (Re)construye las herramientas locales con la configuración actual. */
internal fun ChatViewModel.rebuildLocalTools() {
    val cfg = config
    val lista: List<Tool> = if (!cfg.tools.enabled) {
        emptyList()
    } else {
        listOf(
            GetTimeTool(),
            CreateFileTool(cfg.outputs.maxFileBytes),
            DownloadUrlTool(cfg.outputs.maxDownloadBytes),
            WebSearchTool(),
            WebFetchTool(),
            LatexCompileTool(
                preferredEngine = cfg.latex.engine,
                timeoutSeconds = (cfg.latex.timeoutMs / 1000).coerceAtLeast(1),
                maxDocumentBytes = cfg.latex.maxDocumentBytes,
            ),
        )
    }
    tools.replaceOrigin(ToolOrigin.LOCAL, lista)
}

/**
 * Conecta con los servidores MCP declarados y registra sus herramientas.
 *
 * El transporte todavía no está implementado ([PendingMcpConnector]): los
 * servidores quedan declarados y su estado dice por qué no conectan. Cuando
 * haya cliente MCP, sólo cambia el conector: esto no se toca.
 */
internal suspend fun ChatViewModel.refreshMcpTools() {
    mcp.close()
    mcp = McpManager(PendingMcpConnector, config.mcp.servers)
    val encontradas = mcp.refresh()
    tools.replaceOrigin(ToolOrigin.MCP, encontradas)
}

/** Estado de los servidores MCP, en texto (para el comando `/mcp`). */
internal fun ChatViewModel.mcpStatusText(): String {
    val declarados = config.mcp.servers
    if (declarados.isEmpty()) {
        return "No hay servidores MCP declarados.\n\n" +
            "Se declaran en **Ajustes → Herramientas** (sección `mcp` de la config). " +
            "Las herramientas MCP piden permiso aparte (`/permitir-mcp`)."
    }
    val estados = mcp.statuses()
    val lineas = declarados.map { server ->
        val estado = estados.firstOrNull { it.serverId == server.id }
        val detalle = when {
            estado == null -> "sin estado"
            estado.connected -> "conectado · ${estado.toolCount} herramienta(s)"
            else -> estado.error
        }
        "· **${server.displayName}** (`${server.id}`, ${server.transport}): $detalle"
    }
    val total = tools.byOrigin(ToolOrigin.MCP).size
    return "Servidores MCP de esta app:\n\n" +
        lineas.joinToString("\n") +
        "\n\nHerramientas MCP registradas: $total. " +
        "Para usarlas hay que conceder el permiso MCP en el chat (`/permitir-mcp`)."
}

/* ---------------------------------------------------------------------------
 * Ajustes de las herramientas (pantalla Ajustes → Herramientas).
 *
 * Cada cambio se persiste al instante y vuelve a armar el registro de
 * herramientas: no hace falta reiniciar la app.
 * ------------------------------------------------------------------------- */

/** Interruptor general de herramientas. */
internal fun ChatViewModel.setToolsEnabled(enabled: Boolean) {
    config = config.copy(tools = config.tools.copy(enabled = enabled)).save()
    rebuildLocalTools()
}

/** Timeout por herramienta, en segundos. */
internal fun ChatViewModel.setToolTimeoutSeconds(seconds: Long) {
    config = config.copy(tools = config.tools.copy(timeoutMs = seconds.coerceAtLeast(0) * 1000)).save()
}

/** Carpeta de salidas (relativa a la carpeta de la app). */
internal fun ChatViewModel.setOutputsDir(dir: String) {
    config = config.copy(outputs = config.outputs.copy(dir = dir.trim().ifBlank { "outputs" })).save()
}

/** Tope por archivo creado, en MB. */
internal fun ChatViewModel.setMaxFileMb(mb: Long) {
    config = config.copy(outputs = config.outputs.copy(maxFileMb = mb.toInt().coerceAtLeast(1))).save()
    rebuildLocalTools()
}

/** Tope por descarga, en MB. */
internal fun ChatViewModel.setMaxDownloadMb(mb: Long) {
    config = config.copy(outputs = config.outputs.copy(maxDownloadMb = mb.toInt().coerceAtLeast(1))).save()
    rebuildLocalTools()
}

/** Motor de LaTeX forzado (vacío = autodetectar). */
internal fun ChatViewModel.setLatexEngine(engine: String) {
    config = config.copy(latex = config.latex.copy(engine = engine.trim().lowercase())).save()
    rebuildLocalTools()
}

/** Timeout de compilación LaTeX, en segundos. */
internal fun ChatViewModel.setLatexTimeoutSeconds(seconds: Long) {
    config = config.copy(latex = config.latex.copy(timeoutMs = seconds.coerceAtLeast(1) * 1000)).save()
    rebuildLocalTools()
}

/** Agrega un servidor MCP vacío al final de la lista. */
internal fun ChatViewModel.addMcpServer() {
    val id = "servidor-${config.mcp.servers.size + 1}"
    val servidores = config.mcp.servers + McpServerConfig(id = id, name = id)
    config = config.copy(mcp = config.mcp.copy(servers = servidores)).save()
    scope.launch { refreshMcpTools() }
}

/** Cambia un servidor MCP de la lista. */
internal fun ChatViewModel.updateMcpServer(index: Int, transform: (McpServerConfig) -> McpServerConfig) {
    val servidores = config.mcp.servers.toMutableList()
    if (index !in servidores.indices) return
    servidores[index] = transform(servidores[index])
    config = config.copy(mcp = config.mcp.copy(servers = servidores)).save()
    scope.launch { refreshMcpTools() }
}

/** Quita un servidor MCP de la lista. */
internal fun ChatViewModel.removeMcpServer(index: Int) {
    val servidores = config.mcp.servers.toMutableList()
    if (index !in servidores.indices) return
    servidores.removeAt(index)
    config = config.copy(mcp = config.mcp.copy(servers = servidores)).save()
    scope.launch { refreshMcpTools() }
}
