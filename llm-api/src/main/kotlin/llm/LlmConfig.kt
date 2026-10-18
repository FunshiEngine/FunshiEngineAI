package llm

import kotlinx.serialization.Serializable

@Serializable
enum class LlmMode {
    /** Descarga el modelo GGUF de Hugging Face y levanta llama-server en localhost. */
    LOCAL,
    /** Usa cualquier API compatible con OpenAI (Ollama, LM Studio, vLLM, OpenAI, etc.). */
    REMOTE
}

@Serializable
data class ChatMessage(val role: String, val content: String)

/** Un modelo GGUF descargable de Hugging Face (catálogo para el gestor de modelos). */
@Serializable
data class ModelPreset(
    val id: String,
    val label: String,
    val repo: String,
    val file: String,
    /** Tamaño aproximado en GB (para mostrar en la UI). */
    val sizeGb: Double = 0.0,
    val description: String = "",
)

/** Una API OpenAI-compatible guardada (colección para el selector de APIs). */
@Serializable
data class ApiPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** Vacío = sin clave (endpoints localhost como Ollama/LM Studio). */
    val apiKey: String = "",
    val model: String = "",
)

@Serializable
data class LlmConfig(
    val mode: LlmMode = LlmMode.LOCAL,

    // ---- REMOTE ----
    val remoteBaseUrl: String = "http://localhost:11434/v1",
    val apiKey: String = "",
    val modelName: String = "phi-4-mini",

    // ---- LOCAL ----
    // Modelo por defecto: el mismo que usa SAI (Phi-4-mini-instruct Q4_K_M).
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    val hfToken: String = "",
    val modelsDir: String = "models",
    /** Ruta al binario; vacío = autodetectar (`llama-server[.exe]` en PATH y candidatos). */
    val llamaServerPath: String = "",
    /** Repo git de llama.cpp a clonar (como SAI_LLM_LOCALHOST_REPO_URL de SAI). */
    val llamaRepoUrl: String = "https://github.com/ggml-org/llama.cpp.git",
    /** Rama/tag a clonar (vacío = HEAD por defecto). */
    val llamaRepoVersion: String = "",
    /** Directorio del clon (vacío = <app-home>/llama.cpp). */
    val llamaRepoDir: String = "",
    /** Binario git (default "git"). */
    val gitBin: String = "git",
    /** true = compila llama-server con cmake si no hay binario (SAI_LLM_LOCALHOST_BUILD). */
    val buildFromSource: Boolean = false,
    /** Catálogo de modelos locales (el activo lo marcan hfRepo/hfFile). */
    val modelCatalog: List<ModelPreset> = defaultModelCatalog(),
    /** Colección de APIs OpenAI-compatibles (la activa la marcan remoteBaseUrl/apiKey/modelName). */
    val apiPresets: List<ApiPreset> = defaultApiPresets(),
    val localPort: Int = 8081,
    val contextSize: Int = 8192,
    val gpuLayers: Int = 0,

    // ---- Generación ----
    val temperature: Double = 0.2,
    val maxTokens: Int = 1024,

    // ---- Timeouts (ms) — como en SAI: chat 90 s, carga 120 s ----
    /** Timeout de cada petición de chat (STREAM incluido). */
    val chatTimeoutMs: Long = 90_000,
    /** Espera máxima a que `llama-server` esté sano tras arrancarlo. */
    val loadTimeoutMs: Long = 180_000,

    // ---- UI (tema) ----
    /** Tema oscuro. */
    val uiDark: Boolean = false,
    /** Color de acento en ARGB Int (ej. 0xFF6200EE). */
    val uiAccent: Int = 0xFF6200EE.toInt(),
) {

    /**
     * Valida la config resuelta y devuelve los problemas (vacío = válida).
     * Inspirado en `config/validate.ts` de SAI: falla rápido con un mensaje claro
     * en vez de quedar a medio configurar (B-23).
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (maxTokens <= 0) errors += "llm.maxTokens debe ser mayor que 0 (actual: $maxTokens)."
        if (temperature < 0.0 || temperature > 2.0) {
            errors += "llm.temperature debe estar en [0, 2] (actual: $temperature)."
        }
        if (contextSize < MIN_CONTEXT || contextSize > MAX_CONTEXT) {
            errors += "llm.contextSize fuera de rango [$MIN_CONTEXT, $MAX_CONTEXT] (actual: $contextSize)."
        }
        if (localPort !in 1..65535) errors += "llm.localPort fuera de rango [1, 65535] (actual: $localPort)."
        if (chatTimeoutMs <= 0) errors += "llm.chatTimeoutMs debe ser mayor que 0 (actual: $chatTimeoutMs)."
        if (loadTimeoutMs <= 0) errors += "llm.loadTimeoutMs debe ser mayor que 0 (actual: $loadTimeoutMs)."
        when (mode) {
            LlmMode.LOCAL -> {
                if (hfRepo.isBlank() || hfFile.isBlank()) {
                    errors += "Modo LOCAL: llm.hfRepo y llm.hfFile no pueden estar vacíos."
                }
            }
            LlmMode.REMOTE -> if (remoteBaseUrl.isBlank()) {
                errors += "Modo REMOTE: llm.remoteBaseUrl no puede estar vacío."
            } else if (apiKey.isBlank() && !isLocalhostUrl(remoteBaseUrl)) {
                // Patrón ApiEngine de SAI: la API remota exige clave; los endpoints
                // localhost (Ollama/LM Studio/llama-server) funcionan sin ella.
                errors += "Modo REMOTE: falta llm.apiKey para $remoteBaseUrl (los endpoints " +
                    "localhost no la necesitan)."
            }
        }
        return errors
    }

    companion object {
        /** Mínimo de contexto razonable (igual que SAI). */
        const val MIN_CONTEXT = 128
        /** Máximo de contexto — SAI valida hasta 40 960; llama-server puede más, pero acotamos. */
        const val MAX_CONTEXT = 40_960

        /**
         * Catálogo inicial: el Phi-4-mini de SAI (el que ya usamos) + dos
         * alternativas GGUF populares del mismo formato. El usuario puede
         * agregar más desde la pantalla de modelos (se guardan en la config).
         */
        fun defaultModelCatalog(): List<ModelPreset> = listOf(
            ModelPreset(
                id = "phi-4-mini",
                label = "Phi-4-mini-instruct (Q4_K_M) · el de SAI",
                repo = "unsloth/Phi-4-mini-instruct-GGUF",
                file = "Phi-4-mini-instruct-Q4_K_M.gguf",
                sizeGb = 2.4,
                description = "Modelo por defecto: rápido y bueno en español para docs.",
            ),
            ModelPreset(
                id = "qwen3-4b",
                label = "Qwen3-4B-instruct (Q4_K_M)",
                repo = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                file = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                sizeGb = 2.6,
                description = "Alternativa multilingüe compacta.",
            ),
            ModelPreset(
                id = "llama-3.2-3b",
                label = "Llama-3.2-3B-instruct (Q4_K_M)",
                repo = "unsloth/Llama-3.2-3B-Instruct-GGUF",
                file = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                sizeGb = 2.0,
                description = "El más liviano del catálogo.",
            ),
        )

        /** Colección inicial de APIs: Ollama y LM Studio locales (sin clave). */
        fun defaultApiPresets(): List<ApiPreset> = listOf(
            ApiPreset(id = "ollama", label = "Ollama local", baseUrl = "http://localhost:11434/v1", model = "phi4-mini"),
            ApiPreset(id = "lmstudio", label = "LM Studio local", baseUrl = "http://localhost:1234/v1", model = "local-model"),
        )

        /** Hosts que no necesitan clave (Ollama, LM Studio, llama-server en loopback). */
        private fun isLocalhostUrl(url: String): Boolean {
            val host = try {
                java.net.URI(url).host?.lowercase() ?: ""
            } catch (_: Exception) {
                ""
            }
            return host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")
        }
    }
}
