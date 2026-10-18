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
 */
@kotlinx.serialization.Serializable
data class HfGgufFile(val name: String, val size: Long = 0L)

/**
 * Descarga de archivos de Hugging Face con streaming por chunks, reanudaciÃ³n
 * (.part + Range) y lock por archivo.
 *
 * TÃ©cnica anti-ArrayBuffer (revisada contra SAI `modelDownloadLock.ts`):
 * SAI lee el body con `reader.read()` y escribe cada chunk al archivo con un
 * file handle â€” nunca acumula el modelo en memoria. AcÃ¡ hacemos lo mismo:
 * el canal de Ktor se lee de a 64 KB (`readAvailable`) directo al
 * `FileOutputStream` del `.part`, sin `bodyAsText()`/`readBytes()` que
 * cargarÃ­an los GB en RAM (ese es el "array buffer problema": con modelos de
 * 2+ GB, un buffer completo revienta la memoria).
 *
 * AdemÃ¡s: reanudaciÃ³n con `Range: bytes=<ya-descargado>-` (SAI no la tiene:
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
         * Lista los `.gguf` publicados en un repo de HF (API `huggingface.co/api/models/<repo>`).
         * Devuelve nombre + tamaño para que la UI muestre variantes elegibles.
         */
        suspend fun listGgufFiles(repo: String, token: String = ""): List<HfGgufFile> = withContext(Dispatchers.IO) {
            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 30_000; socketTimeoutMillis = 30_000 }
            }
            try {
                val text = client.get("https://huggingface.co/api/models/$repo") {
                    if (token.isNotBlank()) bearerAuth(token)
                }.bodyAsText()
                val root: JsonObject = Json.parseToJsonElement(text).jsonObject
                val siblings: JsonArray = root["siblings"]?.jsonArray ?: return@withContext emptyList()
                val names: List<String> = siblings.mapNotNull { el: JsonElement ->
                    el.jsonObject["rfilename"]?.let { v ->
                        try { v.jsonPrimitive.content } catch (_: Exception) { null }
                    }
                }
                names.filter { it.endsWith(".gguf", ignoreCase = true) }
                    .map { HfGgufFile(name = it.substringAfterLast("/")) }
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
    }

    suspend fun ensureModel(
        repo: String,
        file: String,
        dir: Path,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        val target = dir.resolve(file)
        if (Files.exists(target)) return@withContext target
        Files.createDirectories(target.parent)
        // Lock por archivo (patrÃ³n SAI): solo una descarga activa por modelo.
        lockFor(target).withLock {
            // Doble chequeo: otro hilo pudo terminar la descarga mientras esperÃ¡bamos el lock.
            if (Files.exists(target)) return@withLock target
            download(repo, file, target, onProgress)
        }
        target
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
                // Integridad: si el servidor declara el total y llegÃ³ menos, no renombramos
                // (el `.part` queda para reanudar en el prÃ³ximo intento).
                if (total > 0 && done < total) {
                    throw LlmException(
                        LlmErrorCode.DESCARGA_FALLIDA,
                        "Descarga incompleta de $file: $done de $total bytes.",
                        retryable = true,
                    )
                }
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
