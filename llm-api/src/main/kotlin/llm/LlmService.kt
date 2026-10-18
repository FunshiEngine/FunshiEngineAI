package llm

import kotlinx.coroutines.delay
import java.nio.file.Path

/**
 * Fachada de la API intermedia: según la config, prepara el modelo local
 * (descarga + localhost) o apunta a una API remota, y devuelve un [LlmGateway].
 *
 * Validación fail-fast al estilo SAI (`config/validate.ts`): la config se
 * valida antes de tocar red o procesos, con todos los problemas listados.
 */
class LlmService(private val cfg: LlmConfig, private val baseDir: Path) {

    private val server = LocalServerManager(cfg, baseDir.resolve("logs"))
    private var gateway: LlmGateway? = null

    suspend fun start(onStatus: (String) -> Unit): LlmGateway {
        val errors = cfg.validate()
        if (errors.isNotEmpty()) {
            throw LlmException(
                LlmErrorCode.NO_CONFIGURADO,
                "Configuración inválida:\n - " + errors.joinToString("\n - "),
                retryable = false,
            )
        }
        val baseUrl = when (cfg.mode) {
            LlmMode.REMOTE -> {
                onStatus("Usando API remota: ${cfg.remoteBaseUrl}")
                cfg.remoteBaseUrl
            }
            LlmMode.LOCAL -> {
                var last = 0L
                val model = retryTransient("descarga del modelo") {
                    HuggingFaceDownloader(cfg.hfToken).ensureModel(
                        cfg.hfRepo, cfg.hfFile, baseDir.resolve(cfg.modelsDir)
                    ) { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - last > 300) {
                            last = now
                            val pct = if (total > 0) done * 100 / total else 0
                            onStatus("Descargando ${cfg.hfFile}: ${done shr 20} / ${total shr 20} MB ($pct%)")
                        }
                    }
                }
                server.start(model, onStatus)
                // Puerto efectivo: el escaneo pudo moverlo si el configurado estaba ocupado.
                "http://127.0.0.1:${server.boundPort}/v1"
            }
        }
        return OpenAiCompatibleGateway(
            baseUrl, cfg.apiKey, cfg.modelName, cfg.temperature, cfg.maxTokens, cfg.chatTimeoutMs
        ).also { gateway = it }
    }

    fun stop() {
        gateway?.close()
        server.stop()
    }

    companion object {
        /**
         * Reintenta un bloque que falló con [LlmException] reintentable
         * (patrón `ApiEngine` de SAI: hasta 2 reintentos, backoff 300→600 ms).
         * Los errores deterministas (clave, cuota, config) no se reintentan.
         */
        private suspend fun <T> retryTransient(what: String, block: suspend () -> T): T {
            var backoffMs = 300L
            for (attempt in 0..MAX_RETRIES) {
                try {
                    return block()
                } catch (e: LlmException) {
                    if (!e.retryable || attempt == MAX_RETRIES) throw e
                    delay(backoffMs)
                    backoffMs *= 2
                }
            }
            error("inalcanzable: reintento de $what")
        }

        private const val MAX_RETRIES = 2
    }
}
