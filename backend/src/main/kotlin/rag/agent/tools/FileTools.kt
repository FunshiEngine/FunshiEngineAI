package rag.agent.tools

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import llm.api.ToolSpec
import rag.agent.Artifact
import rag.agent.Tool
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolResult

/**
 * Herramientas de archivos (F3): crear un archivo de texto y descargar una
 * URL a la carpeta de salidas del chat.
 *
 * Las dos piden permiso de la categoría FILES: el permiso está **apagado por
 * defecto** y se concede por chat (`/permitir-archivos`). Los archivos se
 * escriben **siempre** dentro de `outputs/<chat-id>/`, nunca en una ruta que
 * el modelo pueda elegir: el modelo decide el nombre, no la carpeta.
 */

/** Extensiones que el modelo puede crear (texto y código). */
private val ALLOWED_EXTENSIONS = setOf(
    "md", "mdx", "txt", "rst", "adoc", "csv", "tsv", "json", "yaml", "yml", "toml",
    "ini", "cfg", "conf", "properties", "xml", "html", "css", "scss", "js", "jsx",
    "ts", "tsx", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go",
    "rs", "rb", "php", "swift", "lua", "dart", "sql", "sh", "bat", "ps1", "gradle",
    "cmake", "glsl", "hlsl", "vert", "frag", "tex", "bib", "asm", "s", "r", "jl",
    "vue", "svg", "log", "env",
)

/** Nombres sin extensión que se permiten (los clásicos de proyecto). */
private val ALLOWED_BARE_NAMES = setOf("makefile", "dockerfile", "license", "readme", "gitignore")

/** Tope del nombre de archivo (crear o descargar). */
private const val MAX_NAME_LENGTH = 120

/** Tipo MIME deducido de la extensión (para la tarjeta de adjunto). */
private val MIME_BY_EXT = mapOf(
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "html" to "text/html",
    "py" to "text/x-python",
    "kt" to "text/x-kotlin",
    "js" to "text/javascript",
)

/** Tipo MIME de un archivo por su extensión (vacío si no se conoce). */
internal fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MIME_BY_EXT[ext] ?: ""
}

/** Lee un campo de texto de los argumentos JSON de una herramienta. */
internal fun JsonObject.str(key: String): String =
    this[key]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""

/* ==========================================================================
 * create_file
 * ========================================================================== */

/**
 * Crea un archivo de texto en la carpeta de salidas del chat.
 *
 * Validación estricta (fail-fast): el nombre no puede traer rutas ni `..`, la
 * extensión tiene que estar en la lista permitida y el contenido no puede
 * pasar los 5 MB.
 */
class CreateFileTool : Tool {

    override val category = ToolCategory.FILES

    override val spec = ToolSpec(
        name = "create_file",
        description = "Crea un archivo de texto o código en la carpeta de salidas del chat. " +
            "Usala cuando el usuario pida guardar código, un documento o un ejemplo. " +
            "El nombre debe llevar la extensión (ej. escena.py, shader.glsl).",
        parametersSchema = """
            {"type":"object","properties":{
              "name":{"type":"string","description":"nombre del archivo con su extensión"},
              "content":{"type":"string","description":"contenido completo del archivo"}
            },"required":["name","content"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val name = args.str("name")
        return if (name.isBlank()) {
            "Crear un archivo en la carpeta de salidas del chat."
        } else {
            "Crear el archivo `$name` en la carpeta de salidas del chat."
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val name = args.str("name").trim()
        val content = args.str("content")

        val invalid = validateName(name)
        if (invalid != null) return@withContext ToolResult.Error(invalid)

        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            return@withContext ToolResult.Error(
                "El archivo es demasiado grande: ${bytes.size} bytes (máximo $MAX_FILE_BYTES). " +
                    "Dividilo en varios archivos o resumí el contenido.",
            )
        }

        try {
            val dir = ctx.outputsDir
            Files.createDirectories(dir)
            val target = dir.resolve(name).normalize()
            if (!target.startsWith(dir)) {
                return@withContext ToolResult.Error("Ruta de archivo no válida: `$name`.")
            }
            Files.write(target, bytes)
            ToolResult.Ok(
                text = "Archivo creado: $name (${bytes.size} bytes). " +
                    "El usuario puede abrirlo o guardarlo donde quiera desde la tarjeta del chat.",
                artifacts = listOf(
                    Artifact(
                        name = name,
                        path = target.toAbsolutePath().toString(),
                        sizeBytes = bytes.size.toLong(),
                        mime = mimeOf(name),
                    ),
                ),
            )
        } catch (e: Exception) {
            ToolResult.Error("No se pudo crear `$name`: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Devuelve el motivo del rechazo, o null si el nombre es válido. */
    private fun validateName(name: String): String? = when {
        name.isBlank() -> "Falta el nombre del archivo."
        name.length > MAX_NAME_LENGTH -> "El nombre es demasiado largo (máximo $MAX_NAME_LENGTH caracteres)."
        name.contains('/') || name.contains('\\') ->
            "El nombre no puede contener rutas: escribí sólo el nombre del archivo (ej. `escena.py`)."
        name == "." || name == ".." || name.contains("..") ->
            "El nombre no puede contener `..`."
        name.startsWith(".") -> "El nombre no puede empezar con un punto."
        !isAllowedName(name) ->
            "Extensión no permitida en `$name`. Se aceptan: ${allowedExtensionsPreview()}."
        else -> null
    }

    /** ¿El nombre tiene una extensión permitida (o es un nombre clásico)? */
    private fun isAllowedName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty()) return name.lowercase() in ALLOWED_BARE_NAMES
        return ext.lowercase() in ALLOWED_EXTENSIONS
    }

    /** Lista corta de extensiones permitidas (para el mensaje de error). */
    private fun allowedExtensionsPreview(): String =
        ALLOWED_EXTENSIONS.sorted().joinToString(", ")

    companion object {
        /** Tope por archivo creado por el modelo: 5 MB. */
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
    }
}

/* ==========================================================================
 * download_url
 * ========================================================================== */

/**
 * Descarga una URL a la carpeta de salidas del chat (streaming a disco, con
 * tope de 50 MB y bloqueo por ruta). El nombre sale del `Content-Disposition`
 * o de la URL; la extensión, si falta, se deduce del `Content-Type`.
 */
class DownloadUrlTool : Tool {

    override val category = ToolCategory.FILES

    override val spec = ToolSpec(
        name = "download_url",
        description = "Descarga un archivo de una URL (http/https) a la carpeta de salidas del chat. " +
            "Usala para bajar un PDF, una imagen o un dataset que el usuario pidió. " +
            "Devuelve el nombre, el tamaño y el tipo del archivo descargado.",
        parametersSchema = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"URL completa del archivo a descargar"}
            },"required":["url"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val url = args.str("url")
        return if (url.isBlank()) {
            "Descargar un archivo de la web a la carpeta de salidas del chat."
        } else {
            "Descargar `$url` a la carpeta de salidas del chat."
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val url = args.str("url").trim()
        if (url.isEmpty()) return@withContext ToolResult.Error("Falta la URL a descargar.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.Error("La URL debe empezar por `http://` o `https://`.")
        }

        val uri = try {
            URI.create(url)
        } catch (_: Exception) {
            return@withContext ToolResult.Error("URL inválida: `$url`.")
        }
        if (uri.host.isNullOrBlank()) {
            return@withContext ToolResult.Error("La URL no tiene un dominio válido: `$url`.")
        }

        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()

        try {
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "FunshiEngineAI/1.0")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

            if (response.statusCode() !in 200..299) {
                response.body().close()
                return@withContext ToolResult.Error(
                    "La descarga falló: el servidor respondió HTTP ${response.statusCode()}.",
                )
            }

            val declared = response.headers().firstValueAsLong("content-length").orElse(-1L)
            if (declared > MAX_DOWNLOAD_BYTES) {
                response.body().close()
                return@withContext ToolResult.Error(
                    "El archivo es demasiado grande: $declared bytes (máximo $MAX_DOWNLOAD_BYTES). " +
                        "Pedí una versión más chica.",
                )
            }

            val contentType = response.headers().firstValue("content-type").orElse("")
            val disposition = response.headers().firstValue("content-disposition").orElse("")
            val name = resolveName(disposition, uri.path, contentType)

            val dir = ctx.outputsDir
            Files.createDirectories(dir)
            val target = dir.resolve(name).normalize()
            if (!target.startsWith(dir)) {
                response.body().close()
                return@withContext ToolResult.Error("No se pudo construir una ruta segura para `$name`.")
            }

            // Lock por ruta: dos descargas al mismo archivo no se pisan.
            val size = lockFor(target).withLock {
                val part = target.resolveSibling(target.fileName.toString() + ".part")
                try {
                    val written = writeLimited(response.body(), part, MAX_DOWNLOAD_BYTES)
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
                    written
                } catch (e: Exception) {
                    try { Files.deleteIfExists(part) } catch (_: Exception) { }
                    throw e
                }
            }

            ToolResult.Ok(
                text = "Descargado: $name (${size} bytes, tipo ${contentType.ifBlank { "desconocido" }}). " +
                    "El usuario puede abrirlo o guardarlo desde la tarjeta del chat.",
                artifacts = listOf(
                    Artifact(
                        name = name,
                        path = target.toAbsolutePath().toString(),
                        sizeBytes = size,
                        mime = contentType.substringBefore(';').trim().ifBlank { mimeOf(name) },
                    ),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error("No se pudo descargar `$url`: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            client.close()
        }
    }

    /** Escribe el cuerpo a disco, cortando si supera el tope. */
    private fun writeLimited(input: java.io.InputStream, part: Path, max: Long): Long {
        var total = 0L
        val buf = ByteArray(1 shl 16)
        Files.newOutputStream(part).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IllegalStateException("el archivo supera el máximo de $max bytes")
                out.write(buf, 0, n)
            }
        }
        return total
    }

    /** Nombre final del archivo: `Content-Disposition`, luego la URL, luego deduce la extensión. */
    private fun resolveName(disposition: String, path: String, contentType: String): String {
        val fromHeader = nameFromDisposition(disposition)
        val fromUrl = path.substringAfterLast('/').substringBefore('?').trim()
        var name = sanitizeName(fromHeader.ifBlank { fromUrl.ifBlank { "descarga" } })
        if (name.isEmpty()) name = "descarga"
        // Sin extensión: la deducimos del tipo MIME (application/pdf → .pdf).
        if (!name.contains('.')) {
            val ext = extensionForMime(contentType)
            if (ext.isNotEmpty()) name = "$name.$ext"
        }
        return name
    }

    /** Extrae `filename="..."` del `Content-Disposition`. */
    private fun nameFromDisposition(disposition: String): String {
        val m = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /** Extensión deducida del tipo MIME. */
    private fun extensionForMime(contentType: String): String {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/zip" -> "zip"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "text/plain" -> "txt"
            "text/csv" -> "csv"
            "text/html" -> "html"
            "application/json" -> "json"
            else -> ""
        }
    }

    /** Quita separadores y caracteres raros del nombre deducido. */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trim('.')
        return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH) else cleaned
    }

    companion object {
        /** Tope de descarga: 50 MB. */
        const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024

        /** Locks por ruta absoluta de destino (compartidos entre instancias). */
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(target: Path): Mutex =
            locks.computeIfAbsent(target.toAbsolutePath().toString()) { Mutex() }
    }
}
