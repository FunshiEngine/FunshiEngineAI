package llm.config

import kotlinx.serialization.Serializable

@Serializable
enum class LlmMode {
    /** Descarga el modelo GGUF de Hugging Face y levanta llama-server en localhost. */
    LOCAL,
    /** Usa cualquier API compatible con OpenAI (Ollama, LM Studio, vLLM, OpenAI, etc.). */
    REMOTE
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /**
     * Formato OpenAI: en `role=assistant` con tool call, el array `tool_calls`
     * crudo (para el siguiente round del agente). null normalmente.
     */
    val toolCalls: kotlinx.serialization.json.JsonElement? = null,
    /** En `role=tool`: id del tool call que este mensaje responde. */
    val toolCallId: String? = null,
    /** En `role=tool`: nombre de la herramienta respondida (algunos proveedores). */
    val toolName: String? = null,
)

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
    /**
     * Base de la API remota. Vacío por defecto A PROPÓSITO: antes apuntaba a
     * `http://localhost:11434/v1` (Ollama) y eso confundía — al elegir "API
     * remota" sin configurar nada, la app intentaba un localhost inexistente y
     * fallaba con "Connection refused". Ahora hay que poner una URL real
     * (o guardar una API, que la completa sola).
     */
    val remoteBaseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "phi-4-mini",

    // ---- LOCAL ----
    // Modelo por defecto: Phi-4-mini-instruct Q4_K_M, rápido y bueno en español.
    val hfRepo: String = "unsloth/Phi-4-mini-instruct-GGUF",
    val hfFile: String = "Phi-4-mini-instruct-Q4_K_M.gguf",
    val hfToken: String = "",
    val modelsDir: String = "models",
    /** Ruta al binario; vacío = autodetectar (`llama-server[.exe]` en PATH y candidatos). */
    val llamaServerPath: String = "",
    /** Repo git de llama.cpp a clonar. */
    val llamaRepoUrl: String = "https://github.com/ggml-org/llama.cpp.git",
    /** Rama/tag a clonar (vacío = HEAD por defecto). */
    val llamaRepoVersion: String = "",
    /** Directorio del clon (vacío = <app-home>/llama.cpp). */
    val llamaRepoDir: String = "",
    /** Binario git (default "git"). */
    val gitBin: String = "git",
    /**
     * true = clonar/compilar `llama-server` con cmake si todavía no está clonado
     * o compilado. Por defecto activo: sin él el arranque
     * local falla la primera vez en una máquina sin llama.cpp.
     */
    val buildFromSource: Boolean = true,
    /** Catálogo de modelos locales (el activo lo marcan hfRepo/hfFile). */
    val modelCatalog: List<ModelPreset> = defaultModelCatalog(),
    /** Colección de APIs OpenAI-compatibles (la activa la marcan remoteBaseUrl/apiKey/modelName). */
    val apiPresets: List<ApiPreset> = defaultApiPresets(),
    /**
     * Id de la API activa en modo REMOTE. Se guarda para que la UI pueda marcar
     * "Activa" de forma fiable aunque dos APIs compartan URL/modelo.
     */
    val activeApiId: String? = null,
    val localPort: Int = 8081,
    val contextSize: Int = 8192,
    val gpuLayers: Int = 0,

    // ---- Generación ----
    val temperature: Double = 0.2,
    val maxTokens: Int = 1024,

    // ---- Timeouts (ms) — chat SIN límite por defecto, carga 180 s ----
    /** Timeout de cada petición de chat (STREAM incluido). 0 = sin límite. */
    val chatTimeoutMs: Long = 0,
    /** Espera máxima a que `llama-server` esté sano tras arrancarlo. */
    val loadTimeoutMs: Long = 180_000,

    /**
     * Versión del esquema de config, para migrar instalaciones existentes
     * (ver [rag.AppConfig.migrate]).
     */
    val configVersion: Int = CURRENT_CONFIG_VERSION,

    // ---- UI (tema) ----
    /** Tema oscuro. */
    val uiDark: Boolean = false,
    /** Color de acento en ARGB Int (ej. 0xFF6200EE). */
    val uiAccent: Int = 0xFF6200EE.toInt(),

    // ---- Razonamiento (comandos /thinking y /power del chat) ----
    /**
     * Nivel de razonamiento que se le pide al modelo (Gemini 3, Nvidia, OpenAI, Grok...).
     * Se envía como `reasoning_effort` en el request. Valores: "off", "low", "medium", "high".
     * "off" no envía el parámetro (comportamiento por defecto del proveedor).
     */
    val thinking: String = "off",
    /**
     * Potencia de respuesta: longitud/detalle del texto generado.
     * Mapea a `max_tokens` del request (low=256, medium=1024, high=4096).
     * Valores: "low", "medium", "high".
     */
    val power: String = "medium",
    /**
     * Modo del chat: "GENERAL" responde con conocimiento propio (conversación
     * libre); "DOCS" usa RAG sobre la documentación indexada y cita fuentes.
     */
    val chatMode: String = "GENERAL",
) {

    /**
     * Valida la config resuelta y devuelve los problemas (vacío = válida).
     * Enfoque fail-fast: falla rápido con un mensaje claro
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
        if (chatTimeoutMs < 0) errors += "llm.chatTimeoutMs no puede ser negativo (0 = sin límite; actual: $chatTimeoutMs)."
        if (loadTimeoutMs <= 0) errors += "llm.loadTimeoutMs debe ser mayor que 0 (actual: $loadTimeoutMs)."
        when (mode) {
            LlmMode.LOCAL -> {
                if (hfRepo.isBlank() || hfFile.isBlank()) {
                    errors += "Modo LOCAL: llm.hfRepo y llm.hfFile no pueden estar vacíos."
                }
            }
            LlmMode.REMOTE -> if (remoteBaseUrl.isBlank()) {
                errors += "Modo REMOTE: falta la URL base de la API (llm.remoteBaseUrl). " +
                    "Ejemplo: https://api.openai.com/v1"
            } else if (!remoteBaseUrl.trim().startsWith("http://") && !remoteBaseUrl.trim().startsWith("https://")) {
                // Error típico: pegar la API key en el campo de la URL.
                errors += "Modo REMOTE: la URL base (\"$remoteBaseUrl\") debe empezar con " +
                    "http:// o https:// y terminar en /v1. Si pegaste ahí la clave, " +
                    "esa clave va en el campo \"API key\"."
            } else if (modelName.isBlank()) {
                errors += "Modo REMOTE: falta llm.modelName (el nombre del modelo del proveedor, " +
                    "ej. gpt-4o-mini)."
            } else if (apiKey.isBlank() && !isLocalhostUrl(remoteBaseUrl)) {
                // Patrón de APIs: la API remota exige clave; los endpoints
                // localhost (Ollama/LM Studio/llama-server) funcionan sin ella.
                errors += "Modo REMOTE: falta llm.apiKey para $remoteBaseUrl (los endpoints " +
                    "localhost no la necesitan)."
            }
        }
        return errors
    }

    companion object {
        /** Mínimo de contexto razonable. */
        const val MIN_CONTEXT = 128
        /** Máximo de contexto — llama-server puede más, pero acotamos a 40 960. */
        const val MAX_CONTEXT = 40_960
        /**
         * Versión actual del esquema de config.
         * v1: `buildFromSource=false` por defecto (arranque local sin compilar).
         * v2: `buildFromSource=true` por defecto y sin APIs localhost prefijadas.
         * v3: `chatTimeoutMs=0` — sin límite de tiempo por respuesta.
         * v4: secciones `tools`, `outputs`, `latex` y `mcp` en la config de la app.
         * v5: sección `voice` (dictado y locución) en la config de la app.
         */
        const val CURRENT_CONFIG_VERSION = 5

        /**
         * Catálogo inicial: el Phi-4-mini (el por defecto) + dos
         * alternativas GGUF populares del mismo formato. El usuario puede
         * agregar más desde la pantalla de modelos (se guardan en la config).
         */
        fun defaultModelCatalog(): List<ModelPreset> = listOf(
            ModelPreset(
                id = "phi-4-mini",
                label = "Phi-4-mini-instruct (Q4_K_M) · el por defecto",
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

        /**
         * Colección inicial de APIs: VACÍA a propósito. Antes traía Ollama y
         * LM Studio apuntando a localhost, y eso hacía que el modo "API remota"
         * pareciera roto (intentaba localhost y daba "Connection refused").
         * El usuario carga las que use desde la pestaña APIs.
         */
        fun defaultApiPresets(): List<ApiPreset> = emptyList()

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
