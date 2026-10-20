package llm.error



/**
 * Errores tipados del backend LLM: cada error sabe si es reintentable o
 * terminal, para que quien llama decida sin parsear mensajes de texto.
 */
enum class LlmErrorCode {
    /** La API alcanzó su límite de uso (429). */
    SIN_CUOTA,
    /** La clave de la API fue rechazada (401/403). */
    CLAVE_INVALIDA,
    /** Se superó el tiempo máximo de respuesta. */
    TIMEOUT,
    /** El túnel no está configurado o habilitado. */
    NO_CONFIGURADO,
    /** El modelo/petición respondió un error del servidor (5xx) o el modelo no está disponible. */
    MODELO_NO_DISPONIBLE,
    /** La ventana de contexto / KV cache se agotó (400 con indicios de tokens). */
    CONTEXTO_AGOTADO,
    /** La descarga del modelo falló (HTTP, red o integridad). */
    DESCARGA_FALLIDA,
    /** El servidor local se cayó o no responde en red. */
    SERVIDOR_CAIDO,
    /** La generación fue cancelada por el usuario. */
    CANCELADO,
    /** Error desconocido. */
    DESCONOCIDO,
}

/**
 * Excepción tipada del módulo LLM.
 *
 * @param code código identificador.
 * @param retryable `true` si un reintento (o caer a otro túnel) puede resolverlo;
 * los errores deterministas (clave inválida, cuota, config) son `false`.
 * @param cause causa original cuando existe.
 */
class LlmException(
    val code: LlmErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String = "LlmException[$code, retryable=$retryable]: $message"

    companion object {
        /**
         * Clasifica una respuesta HTTP fallida de un endpoint OpenAI-compatible
         * (local o remoto) a un [LlmException]: mira status y palabras clave
         * del cuerpo ("context", "kv cache", "tokens").
         */
        fun fromHttp(status: Int, body: String, local: Boolean): LlmException {
            val origin = if (local) "llama-server" else "la API"
            val snippet = body.take(300).ifBlank { "(sin cuerpo)" }
            return when {
                status == 401 || status == 403 ->
                    LlmException(LlmErrorCode.CLAVE_INVALIDA, "$origin rechazó la clave (HTTP $status).")
                status == 429 ->
                    LlmException(LlmErrorCode.SIN_CUOTA, "$origin alcanzó su límite de uso (HTTP 429).", retryable = true)
                status == 408 ->
                    LlmException(LlmErrorCode.TIMEOUT, "$origin tardó demasiado (HTTP 408).", retryable = true)
                status == 400 && CONTEXT_PATTERN.containsMatchIn(body) ->
                    LlmException(
                        LlmErrorCode.CONTEXTO_AGOTADO,
                        "$origin agotó el contexto: $snippet",
                        retryable = true,
                    )
                status == 404 ->
                    LlmException(
                        LlmErrorCode.MODELO_NO_DISPONIBLE,
                        "$origin no encontró el modelo/ruta (HTTP 404). Detalle: $snippet",
                        retryable = true,
                    )
                status >= 500 ->
                    LlmException(LlmErrorCode.MODELO_NO_DISPONIBLE, "$origin respondió HTTP $status: $snippet", retryable = true)
                else ->
                    LlmException(LlmErrorCode.DESCONOCIDO, "$origin respondió HTTP $status: $snippet")
            }
        }

        /** Clasifica fallos de red/transporte (conexión rechazada, reset, timeout…). */
        fun fromNetwork(t: Throwable, local: Boolean): LlmException {
            if (t is LlmException) return t
            val msg = t.message ?: t.javaClass.simpleName
            return if (local) {
                LlmException(LlmErrorCode.SERVIDOR_CAIDO, "No se pudo hablar con llama-server: $msg", retryable = true, cause = t)
            } else {
                LlmException(LlmErrorCode.MODELO_NO_DISPONIBLE, "Fallo de red con la API: $msg", retryable = true, cause = t)
            }
        }

        /** Normaliza cualquier Throwable a [LlmException] preservando la información. */
        fun wrap(t: Throwable): LlmException = when (t) {
            is LlmException -> t
            is kotlinx.coroutines.CancellationException -> {
                // La cancelación de una coroutine también cae aquí; va con
                // CANCELADO para no confundirla con un fallo.
                LlmException(LlmErrorCode.CANCELADO, "La generación fue cancelada.", cause = t)
            }
            is java.util.concurrent.TimeoutException ->
                LlmException(LlmErrorCode.TIMEOUT, t.message ?: "Tiempo de espera agotado.", retryable = true, cause = t)
            is java.io.IOException ->
                LlmException(LlmErrorCode.SERVIDOR_CAIDO, "Fallo de E/S: ${t.message}", retryable = true, cause = t)
            else ->
                LlmException(LlmErrorCode.DESCONOCIDO, t.message ?: "Error desconocido", cause = t)
        }

        private val CONTEXT_PATTERN = Regex("context|ctx_size|kv cache|kv_cache|tokens", RegexOption.IGNORE_CASE)
    }
}