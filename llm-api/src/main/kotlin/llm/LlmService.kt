package llm

import kotlinx.coroutines.delay
import java.nio.file.Path

/**
 * Fachada de la API intermedia: según la config, prepara el modelo local
 * (descarga + localhost) o apunta a una API remota, y devuelve un [LlmGateway].
 *
 * Validación fail-fast al estilo SAI (`config/validate.ts`): la config se
 * valida antes de tocar red o procesos, con todos los problemas listados.
 *
 * La config se lee en cada arranque vía [state] (fuente de verdad = ViewModel).
 * Antes se guardaba una copia al construir el servicio, así que activar una API
 * o cambiar de modelo no tenía efecto: el servicio seguía con la config inicial
 * y seguía exigiendo el modelo local + llama-server en localhost.
 *
 * En modo REMOTE no se toca nada local: ni descarga, ni clonado, ni servidor.
 */
class LlmService(private val state: () -> LlmConfig, private val baseDir: Path) {

    /** Servidor de la sesión actual (null en REMOTE o antes del primer start). */
    private var server: LocalServerManager? = null
    private var gateway: LlmGateway? = null

    suspend fun start(
        onStatus: (String) -> Unit,
        onSetup: (LlamaSetupProgress) -> Unit = { onStatus(it.message) },
        onDownload: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): LlmGateway {
        val cfg = state()
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
                // Nada de localhost: solo el endpoint que configuró el usuario.
                onStatus("Usando API remota: ${cfg.remoteBaseUrl}")
                cfg.remoteBaseUrl
            }
            LlmMode.LOCAL -> {
                var last = 0L
                // ensureModel no vuelve a bajar nada si el archivo ya existe y pesa > 0.
                val model = retryTransient("descarga del modelo") {
                    HuggingFaceDownloader(cfg.hfToken).ensureModel(
                        cfg.hfRepo, cfg.hfFile, baseDir.resolve(cfg.modelsDir)
                    ) { done, total ->
                        val now = System.currentTimeMillis()
                        val finished = total > 0 && done >= total
                        if (now - last > 300 || finished) {
                            last = now
                            onDownload(done, total)
                        }
                    }
                }
                // Servidor por arranque: siempre con la config vigente.
                server?.stop()
                val srv = LocalServerManager(cfg, baseDir.resolve("logs"))
                srv.start(model, onStatus, onSetup)
                server = srv
                // Puerto efectivo: el escaneo pudo moverlo si el configurado estaba ocupado.
                "http://127.0.0.1:${srv.boundPort}/v1"
            }
        }
        return OpenAiCompatibleGateway(
            baseUrl, cfg.apiKey, cfg.modelName, cfg.temperature, cfg.maxTokens, cfg.chatTimeoutMs,
            cfg.thinking, cfg.power,
        ).also { gateway = it }
    }

    fun stop() {
        gateway?.close()
        gateway = null
        server?.stop()
        server = null
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
