package rag.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec
import rag.agent.Artifact
import rag.agent.Tool
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolOrigin
import rag.agent.ToolResult

/* ==========================================================================
 * MCP (Model Context Protocol) — contratos y adaptador (F7).
 *
 * Esta ronda define el CONTRATO, no el transporte: la app no habla MCP
 * todavía, pero todo lo que hace falta para hablarlo está escrito y probado.
 *
 * El seam está en un solo lugar: [McpConnection]. Cuando llegue el SDK
 * oficial (`io.modelcontextprotocol:kotlin-sdk`), se implementa la interfaz
 * sobre stdio o HTTP y el resto de la app no cambia: el resto ya consume
 * [McpToolAdapter], que expone una tool remota como una tool más.
 *
 * Decisión de diseño: la herramienta remota se adapta a [Tool], no al revés.
 * El AgentLoop, los permisos, la UI de adjuntos y el catálogo de tools no
 * distinguen el origen de una tool: una tool MCP es una tool, con categoría
 * MCP (pide permiso) y nombre calificado (`mcp__servidor__herramienta`).
 * ========================================================================== */

/** Herramienta declarada por un servidor MCP. */
data class McpToolDescriptor(
    /** Nombre de la herramienta en el servidor (sin calificar). */
    val name: String,
    /** Descripción que el servidor le da a la herramienta. */
    val description: String = "",
    /** JSON Schema de los argumentos, como texto (igual que en [ToolSpec]). */
    val inputSchema: String = "{}",
)

/** Contenido que devuelve un servidor MCP. */
sealed class McpContent {
    /** Texto plano (lo más común). */
    data class Text(val text: String) : McpContent()

    /** Contenido binario en base64 (imágenes, PDF, etc.). */
    data class Binary(val mime: String, val base64: String) : McpContent()
}

/** Resultado de una llamada a una herramienta de un servidor MCP. */
sealed class McpToolResult {
    /** El servidor respondió. [isError] = el contenido es un mensaje de error. */
    data class Ok(val content: List<McpContent>, val isError: Boolean = false) : McpToolResult()

    /** La llamada ni siquiera llegó al servidor (transporte caído, timeout…). */
    data class Error(val message: String) : McpToolResult()
}

/**
 * Contrato de conexión con un servidor MCP.
 *
 * Deliberadamente mínimo: sólo lo que la app necesita. Implementaciones:
 *  - `McpConnection` sobre el SDK oficial (ronda siguiente).
 *  - dobles de prueba en los tests (no dependen de ningún servidor real).
 */
interface McpConnection {
    /** Identificador del servidor (stable, sin espacios: parte del nombre de la tool). */
    val serverId: String

    /** Nombre para mostrar en la UI. */
    val displayName: String

    /** Versión de protocolo que negoció el servidor (vacía si no la informó). */
    val protocolVersion: String get() = ""

    /** Lista las herramientas que el servidor expone ahora. */
    suspend fun listTools(): List<McpToolDescriptor>

    /** Invoca una herramienta del servidor. */
    suspend fun callTool(name: String, args: JsonObject): McpToolResult

    /** Cierra la conexión (libera el proceso o el socket). */
    suspend fun close()
}

/**
 * Adapta una herramienta de un servidor MCP a una [Tool] de la app.
 *
 * El nombre queda calificado (`mcp__servidor__herramienta`) para que dos
 * servidores puedan exponer herramientas homónimas sin pisarse, y para que
 * el modelo (y el usuario) vean de dónde sale cada llamada.
 */
class McpToolAdapter(
    private val connection: McpConnection,
    private val descriptor: McpToolDescriptor,
) : Tool {

    /** Nombre calificado con el que el modelo la invoca. */
    val qualifiedName: String = "mcp__${sanitizeIdent(connection.serverId)}__${sanitizeIdent(descriptor.name)}"

    /** Nombre original en el servidor (para la llamada). */
    val remoteName: String get() = descriptor.name

    /** Servidor del que viene. */
    val serverId: String get() = connection.serverId

    /** Las tools MCP piden permiso: pueden tocar lo que sea el servidor. */
    override val category = ToolCategory.MCP

    override val origin = ToolOrigin.MCP

    override val spec = ToolSpec(
        name = qualifiedName,
        description = buildString {
            append(descriptor.description.ifBlank { "Herramienta del servidor MCP ${connection.displayName}." })
            append(" (servidor: ").append(connection.displayName).append(")")
        },
        parametersSchema = descriptor.inputSchema,
    )

    override fun describeCall(args: JsonObject): String =
        "Usar la herramienta `${descriptor.name}` del servidor MCP ${connection.displayName}."

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val result = try {
            connection.callTool(descriptor.name, args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Error(
                "No se pudo hablar con el servidor MCP ${connection.displayName}: " +
                    "${e.message ?: e.javaClass.simpleName}",
            )
        }

        return when (result) {
            is McpToolResult.Error -> ToolResult.Error(result.message)
            is McpToolResult.Ok -> {
                val textos = result.content.filterIsInstance<McpContent.Text>().map { it.text }
                val binarios = result.content.filterIsInstance<McpContent.Binary>()
                val texto = textos.joinToString("\n").trim()

                // El servidor marcó el resultado como error: vuelve al modelo
                // como error controlado (puede reintentar o explicar).
                if (result.isError) {
                    return ToolResult.Error(
                        texto.ifBlank { "El servidor MCP ${connection.displayName} devolvió un error." },
                    )
                }

                val artifacts = binarios.mapNotNull { saveBinary(it, ctx.outputsDir) }
                val cuerpo = buildString {
                    append(texto)
                    if (artifacts.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Archivos recibidos: ")
                        append(artifacts.joinToString(", ") { it.name })
                    }
                }
                ToolResult.Ok(
                    text = cuerpo.ifBlank { "El servidor MCP ${connection.displayName} no devolvió contenido." },
                    artifacts = artifacts,
                )
            }
        }
    }

    /**
     * Guarda un contenido binario en la carpeta de salidas del chat y devuelve
     * el artefacto. Si no se puede guardar, no es motivo para fallar la
     * llamada: el texto (si lo hay) sigue valiendo.
     */
    private fun saveBinary(content: McpContent.Binary, dir: Path): Artifact? {
        val bytes = try {
            Base64.getDecoder().decode(content.base64)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val name = "${sanitizeIdent(descriptor.name)}.${extensionOf(content.mime)}"
        return try {
            Files.createDirectories(dir)
            val target = dir.resolve(name).normalize()
            if (!target.startsWith(dir)) return null
            Files.write(target, bytes)
            Artifact(
                name = name,
                path = target.toAbsolutePath().toString(),
                sizeBytes = bytes.size.toLong(),
                mime = content.mime,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Extensión por tipo MIME (las más comunes; si no, `bin`). */
        private val MIME_EXTENSIONS = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "application/pdf" to "pdf",
            "application/json" to "json",
            "text/plain" to "txt",
            "text/markdown" to "md",
            "audio/mpeg" to "mp3",
            "audio/wav" to "wav",
        )

        private fun extensionOf(mime: String): String =
            MIME_EXTENSIONS[mime.lowercase().substringBefore(';').trim()] ?: "bin"

        /**
         * Deja un identificador con los caracteres que aceptan las APIs de
         * tools: letras, números, `_` y `-`.
         */
        internal fun sanitizeIdent(raw: String): String {
            val clean = raw.trim().map { c ->
                if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
            }.joinToString("")
            return clean.ifBlank { "tool" }
        }
    }
}
