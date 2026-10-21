package voice.error

/**
 * Errores tipados de la capa de voz: cada error sabe si es reintentable o
 * terminal, para que quien llama decida sin parsear mensajes de texto.
 *
 * Es el mismo patrón que `llm.error.LlmException`, con los códigos propios
 * del audio.
 */
enum class VoiceErrorCode {
    /** No hay ningún motor de voz configurado (dictado o locución). */
    NO_PROVEEDOR,
    /** La configuración de voz es inválida (URL vacía, modelo que no existe…). */
    CONFIG_INVALIDA,
    /** No hay micrófono, o está en uso por otra aplicación. */
    MICROFONO_NO_DISPONIBLE,
    /** La captura de audio falló a mitad de la grabación. */
    CAPTURA_FALLIDA,
    /** No se oyó nada (o el silencio llegó antes que la voz). */
    SIN_HABLA,
    /** El motor de transcripción falló o devolvió algo ilegible. */
    TRANSCRIPCION_FALLIDA,
    /** El motor de síntesis falló o devolvió un audio que no se puede usar. */
    SINTETESIS_FALLIDA,
    /** No se pudo reproducir el audio. */
    REPRODUCCION_FALLIDA,
    /** Se superó el tiempo máximo. */
    TIMEOUT,
    /** La operación fue cancelada por el usuario. */
    CANCELADO,
    /** Error desconocido. */
    DESCONOCIDO,
}

/**
 * Excepción tipada de la capa de voz.
 *
 * @param code código identificador.
 * @param retryable `true` si un reintento puede resolverlo (red caída, timeout);
 * los errores de configuración o de hardware son `false`.
 * @param cause causa original cuando existe.
 */
class VoiceException(
    val code: VoiceErrorCode,
    override val message: String,
    val retryable: Boolean = false,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String = "VoiceException[$code, retryable=$retryable]: $message"

    companion object {

        /** Clasifica una respuesta HTTP fallida de un endpoint de voz. */
        fun fromHttp(status: Int, body: String, what: String): VoiceException {
            val snippet = body.take(300).ifBlank { "(sin cuerpo)" }
            return when {
                status == 401 || status == 403 ->
                    VoiceException(VoiceErrorCode.CONFIG_INVALIDA, "$what rechazó la clave (HTTP $status).")
                status == 404 ->
                    VoiceException(
                        VoiceErrorCode.CONFIG_INVALIDA,
                        "$what no encontró el modelo o la ruta (HTTP 404). Detalle: $snippet",
                    )
                status == 408 ->
                    VoiceException(VoiceErrorCode.TIMEOUT, "$what tardó demasiado (HTTP 408).", retryable = true)
                status == 429 ->
                    VoiceException(VoiceErrorCode.DESCONOCIDO, "$what alcanzó su límite de uso (HTTP 429).", retryable = true)
                status >= 500 ->
                    VoiceException(VoiceErrorCode.DESCONOCIDO, "$what respondió HTTP $status: $snippet", retryable = true)
                else ->
                    VoiceException(VoiceErrorCode.DESCONOCIDO, "$what respondió HTTP $status: $snippet")
            }
        }

        /** Normaliza cualquier Throwable a [VoiceException] preservando la información. */
        fun wrap(t: Throwable): VoiceException = when (t) {
            is VoiceException -> t
            is kotlinx.coroutines.CancellationException ->
                VoiceException(VoiceErrorCode.CANCELADO, "La operación de voz fue cancelada.", cause = t)
            is java.util.concurrent.TimeoutException ->
                VoiceException(VoiceErrorCode.TIMEOUT, t.message ?: "Tiempo de espera agotado.", retryable = true, cause = t)
            is java.io.IOException ->
                VoiceException(VoiceErrorCode.CAPTURA_FALLIDA, "Fallo de E/S: ${t.message}", retryable = true, cause = t)
            else ->
                VoiceException(VoiceErrorCode.DESCONOCIDO, t.message ?: "Error desconocido", cause = t)
        }
    }
}
