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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Descarga un archivo de un repo de Hugging Face, con reanudación (.part) si se corta.
 *
 * Mejoras tomadas del backend de SAI (`modelDownloadLock.ts`):
 *  - Lock global por archivo: dos descargas concurrentes del mismo modelo esperan
 *    en vez de corromper el `.part`.
 *  - Chequeo de integridad: si el servidor declara `Content-Length` y llegó menos
 *    bytes, se lanza [LlmException] DESCARGA_FALLIDA (el `.part` queda para reanudar).
 *  - Errores tipados en vez de `error(...)` genérico.
 */
class HuggingFaceDownloader(private val token: String = "") {

    companion object {
        /** Locks por ruta absoluta de destino, compartidos entre instancias. */
        private val locks = ConcurrentHashMap<String, Mutex>()

        private fun lockFor(target: Path): Mutex =
            locks.computeIfAbsent(target.toAbsolutePath().toString()) { Mutex() }
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
        // Lock por archivo (patrón SAI): solo una descarga activa por modelo.
        lockFor(target).withLock {
            // Doble chequeo: otro hilo pudo terminar la descarga mientras esperábamos el lock.
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
                // Integridad: si el servidor declara el total y llegó menos, no renombramos
                // (el `.part` queda para reanudar en el próximo intento).
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
