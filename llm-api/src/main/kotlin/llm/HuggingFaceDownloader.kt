package llm

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Un archivo GGUF publicado en un repo de Hugging Face (para elegir variante).
 * [name] es la ruta completa dentro del repo (`subcarpeta/archivo.gguf`), porque
 * la URL de descarga la necesita completa; usa [display] para mostrarlo en la UI.
 */
@kotlinx.serialization.Serializable
data class HfGgufFile(val name: String, val size: Long = 0L) {
    /** Nombre visible (sin carpetas). */
    val display: String get() = name.substringAfterLast("/")
}

/**
 * Repo GGUF encontrado en una búsqueda de Hugging Face: id + popularidad,
 * para que la UI permita buscar modelos sin escribir el repo a mano.
 */
@kotlinx.serialization.Serializable
data class HfRepoHit(
    val id: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val lastModified: String = "",
)

/**
 * Descarga de archivos de Hugging Face con streaming por chunks, reanudación
 * (.part + Range) y lock por archivo.
 *
 * Técnica anti-ArrayBuffer (revisada contra SAI `modelDownloadLock.ts`):
 * SAI lee el body con `reader.read()` y escribe cada chunk al archivo con un
 * file handle — nunca acumula el modelo en memoria. Acá hacemos lo mismo:
 * el canal de Ktor se lee de a 64 KB (`readAvailable`) directo al
 * `FileOutputStream` del `.part`, sin `bodyAsText()`/`readBytes()` que
 * cargarían los GB en RAM (ese es el "array buffer problema": con modelos de
 * 2+ GB, un buffer completo revienta la memoria).
 *
 * Además: reanudación con `Range: bytes=<ya-descargado>-` (SAI no la tiene:
 * borra el `.download` y empieza de cero), chequeo de integridad con
 * `Content-Length`, y errores tipados.
 */
class HuggingFaceDownloader(private val token: String = "") {


    companion object {
        /** Locks por ruta absoluta de destino, compartidos entre instancias. */
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(target: Path): Mutex =
            locks.computeIfAbsent(target.toAbsolutePath().toString()) { Mutex() }

        /**
         * Lista los `.gguf` publicados en un repo de HF (`/api/models/<repo>?blobs=true`),
         * devolviendo ruta completa + tamaño, para que la UI muestre variantes
         * elegibles con su peso real.
         */
        suspend fun listGgufFiles(repo: String, token: String = ""): List<HfGgufFile> = withContext(Dispatchers.IO) {
            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
            }
            try {
                val text = client.get("https://huggingface.co/api/models/$repo") {
                    parameter("blobs", "true")
                    if (token.isNotBlank()) bearerAuth(token)
                }.bodyAsText()
                val root: JsonObject = Json.parseToJsonElement(text).jsonObject
                val siblings: JsonArray = root["siblings"]?.jsonArray ?: return@withContext emptyList()
                siblings.mapNotNull { el: JsonElement ->
                    val o = el.jsonObject
                    val path = o["rfilename"]?.let { v ->
                        try { v.jsonPrimitive.content } catch (_: Exception) { null }
                    } ?: return@mapNotNull null
                    if (!path.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
                    val size = o["size"]?.let { v ->
                        try { v.jsonPrimitive.content.toLongOrNull() ?: 0L } catch (_: Exception) { 0L }
                    } ?: 0L
                    HfGgufFile(name = path, size = size)
                }
            } catch (e: Exception) {
                throw LlmException(
                    LlmErrorCode.DESCARGA_FALLIDA,
                    "No se pudo listar los archivos de $repo: ${e.message}",
                    retryable = true, cause = e,
                )
            } finally {
                client.close()
            }
        }

        /**
         * Busca modelos GGUF en Hugging Face (`/api/models?search=…&filter=gguf`,
         * ordenados por descargas). Si el tag `gguf` no devuelve nada (repos sin
         * etiquetar), reintenta sin el filtro para no dejar al usuario sin resultados.
         */
        suspend fun searchRepos(query: String, token: String = "", limit: Int = 20): List<HfRepoHit> =
            withContext(Dispatchers.IO) {
                val client = HttpClient(CIO) {
                    install(HttpTimeout) { requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
                }
                try {
                    val hits = fetchRepos(client, query, token, limit, ggufOnly = true)
                    if (hits.isNotEmpty()) hits else fetchRepos(client, query, token, limit, ggufOnly = false)
                } catch (e: Exception) {
                    throw LlmException(
                        LlmErrorCode.DESCARGA_FALLIDA,
                        "No se pudo buscar \"$query\" en Hugging Face: ${e.message}",
                        retryable = true, cause = e,
                    )
                } finally {
                    client.close()
                }
            }

        private suspend fun fetchRepos(
            client: HttpClient,
            query: String,
            token: String,
            limit: Int,
            ggufOnly: Boolean,
        ): List<HfRepoHit> {
            val text = client.get("https://huggingface.co/api/models") {
                parameter("search", query.trim())
                if (ggufOnly) parameter("filter", "gguf")
                parameter("sort", "downloads")
                parameter("direction", "-1")
                parameter("limit", limit)
                if (token.isNotBlank()) bearerAuth(token)
            }.bodyAsText()
            val arr: JsonArray = Json.parseToJsonElement(text).jsonArray
            return arr.mapNotNull { el ->
                val o = el.jsonObject
                val id = o.str("id") ?: return@mapNotNull null
                HfRepoHit(
                    id = id,
                    downloads = o.num("downloads"),
                    likes = o.num("likes"),
                    lastModified = o.str("lastModified") ?: "",
                )
            }
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.let { v -> try { v.jsonPrimitive.content } catch (_: Exception) { null } }

        private fun JsonObject.num(key: String): Int =
            this[key]?.let { v -> try { v.jsonPrimitive.content.toIntOrNull() ?: 0 } catch (_: Exception) { 0 } } ?: 0
    }

    suspend fun ensureModel(
        repo: String,
        file: String,
        dir: Path,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        val target = dir.resolve(file)
        if (isDownloaded(target)) return@withContext target
        Files.createDirectories(target.parent)
        // Lock por archivo: solo una descarga activa por modelo.
        lockFor(target).withLock {
            // Doble chequeo: otro hilo pudo terminar la descarga mientras esperabamos el lock.
            if (isDownloaded(target)) return@withLock
            // Archivo vacio de una interrupcion previa: no sirve, se vuelve a bajar.
            if (Files.exists(target)) Files.deleteIfExists(target)
            download(repo, file, target, onProgress)
        }
        target
    }

    /**
     * Un modelo esta descargado si existe, es un archivo y pesa > 0:
     * un `.gguf` de 0 bytes es una descarga abortada, no un modelo valido.
     */
    private fun isDownloaded(target: Path): Boolean =
        try {
            Files.isRegularFile(target) && Files.size(target) > 0
        } catch (_: Exception) {
            false
        }

    private suspend fun download(
        repo: String,
        file: String,
        target: Path,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        val already = if (Files.exists(part)) Files.size(part) else 0L

        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000
            }
        }
        try {
            client.prepareGet("https://huggingface.co/$repo/resolve/main/$file") {
                if (token.isNotBlank()) bearerAuth(token)
                if (already > 0) header(HttpHeaders.Range, "bytes=$already-")
            }.execute { resp ->
                // 416 = el `.part` ya contiene todo lo que el servidor tiene
                // (corte previo justo al final): lo damos por completo tal cual.
                if (resp.status == HttpStatusCode.RequestedRangeNotSatisfiable && already > 0) {
                    return@execute
                }
                if (!resp.status.isSuccess()) {
                    throw LlmException.fromHttp(resp.status.value, resp.bodyAsText(), local = false)
                }
                val resumed = resp.status == HttpStatusCode.PartialContent
                val offset = if (resumed) already else 0L
                val total = (resp.contentLength() ?: 0L) + offset
                var done = offset
                val channel = resp.bodyAsChannel()
                val buf = ByteArray(1 shl 16)
                FileOutputStream(part.toFile(), resumed).use { out ->
                    while (true) {
                        val n = channel.readAvailable(buf, 0, buf.size)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                // Integridad: si el servidor declara el total y llego menos, no renombramos
                // (el `.part` queda para reanudar en el proximo intento).
                if (total > 0 && done < total) {
                    throw LlmException(
                        LlmErrorCode.DESCARGA_FALLIDA,
                        "Descarga incompleta de $file: $done de $total bytes.",
                        retryable = true,
                    )
                }
                // Ultimo reporte garantizado: la UI siempre llega al 100%.
                onProgress(done, if (total > 0) total else done)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(
                LlmErrorCode.DESCARGA_FALLIDA,
                "No se pudo descargar $repo/$file: ${e.message}",
                retryable = true,
                cause = e,
            )
        } finally {
            client.close()
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
