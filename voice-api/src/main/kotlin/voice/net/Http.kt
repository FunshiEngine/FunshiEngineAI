package voice.net

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/**
 * Cliente HTTP mínimo para los motores de voz.
 *
 * Usa el **`HttpClient` del JDK** (no ktor) por el mismo motivo que
 * `rag/retrieval/Sources.kt`: menos dependencias y el toolchain del proyecto
 * no se mueve (mismo criterio que el cliente MCP propio).
 */
internal object Http {

    /** Cliente compartido (el del JDK, con timeouts). */
    val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * POST multipart/form-data con un archivo.
     *
     * @param url URL completa.
     * @param fields campos de texto del formulario.
     * @param fileField nombre del campo del archivo.
     * @param fileName nombre del archivo (en la cabecera Content-Disposition).
     * @param contentType tipo MIME del archivo.
     * @param fileBytes contenido del archivo.
     * @param apiKey clave de la API (vacía = sin cabecera de autorización).
     * @param timeoutMs timeout de la petición.
     * @param errorCode código de error si la petición falla por red.
     */
    fun postMultipart(
        url: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray,
        apiKey: String = "",
        timeoutMs: Long = 60_000,
        errorCode: VoiceErrorCode = VoiceErrorCode.TRANSCRIPCION_FALLIDA,
    ): ByteArray {
        val boundary = "----FunshiVoice" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = multipartBody(boundary, fields, fileField, fileName, contentType, fileBytes)
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return send(builder.build(), url, errorCode)
    }

    /**
     * POST con cuerpo JSON.
     *
     * @param url URL completa.
     * @param json cuerpo JSON (ya serializado).
     * @param apiKey clave de la API (vacía = sin cabecera de autorización).
     * @param timeoutMs timeout de la petición.
     * @param errorCode código de error si la petición falla por red.
     */
    fun postJson(
        url: String,
        json: String,
        apiKey: String = "",
        timeoutMs: Long = 60_000,
        errorCode: VoiceErrorCode = VoiceErrorCode.SINTETESIS_FALLIDA,
    ): ByteArray {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        return send(builder.build(), url, errorCode)
    }

    /** Ejecuta la petición y devuelve el cuerpo; los errores salen tipados. */
    private fun send(request: HttpRequest, url: String, errorCode: VoiceErrorCode): ByteArray {
        val response: HttpResponse<ByteArray> = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: java.io.IOException) {
            throw VoiceException(
                errorCode,
                "No se pudo conectar con $url: ${e.message ?: e.javaClass.simpleName}",
                retryable = true,
                cause = e,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VoiceException(VoiceErrorCode.CANCELADO, "La petición de voz fue interrumpida.", cause = e)
        }
        val status = response.statusCode()
        val body = response.body().toString(Charsets.UTF_8)
        if (status !in 200..299) {
            throw VoiceException.fromHttp(status, body, "El servicio de voz")
        }
        return response.body()
    }

    /** Arma el cuerpo multipart/form-data a mano (evita depender de ktor). */
    private fun multipartBody(
        boundary: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(fileBytes.size + 512)
        for ((name, value) in fields) {
            out.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.write(value.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        out.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
        out.write(
            "Content-Disposition: form-data; name=\"$fileField\"; filename=\"$fileName\"\r\n"
                .toByteArray(Charsets.US_ASCII),
        )
        out.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(fileBytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}
