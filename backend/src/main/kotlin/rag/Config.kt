package rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import llm.LlmConfig
import java.nio.file.Files
import java.nio.file.Path

val AppJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

/** Carpeta de trabajo (config, modelos, caché, docs internas). */
object AppPaths {
    val home: Path = Path.of(
        System.getProperty("app.home")
            ?: System.getenv("DOCS_CHATBOT_HOME")
            ?: "${System.getProperty("user.home")}/.docs-chatbot"
    ).toAbsolutePath().normalize()

    fun resolve(p: String): Path = home.resolve(p).normalize()
}

@Serializable
data class GithubSource(
    val owner: String,
    val repo: String,
    /** Rama, tag o SHA. Vacío = rama por defecto. */
    val ref: String = "",
    /** Prefijos de carpetas a indexar (ej. ["docs/"]). Vacío = todo el repo. */
    val paths: List<String> = emptyList(),
    /** Para repos privados. Si está vacío se usa la variable de entorno GITHUB_TOKEN. */
    val token: String = ""
)

@Serializable
data class RagConfig(
    val githubSources: List<GithubSource> = emptyList(),
    val internalDocsDirs: List<String> = listOf("internal-docs"),
    val extensions: List<String> = listOf("md", "mdx", "txt", "rst", "adoc"),
    val systemPromptFile: String = "config/system_prompt.md",
    val cacheDir: String = "data/cache",
    val chunkSize: Int = 1200,
    val chunkOverlap: Int = 150,
    val topK: Int = 5,
    val historyTurns: Int = 6
)

/**
 * Perfil de color guardado por el usuario (pestaña Configuración).
 * Permite guardar combinaciones (tema + acento) y re-aplicarlas en un clic.
 */
@Serializable
data class ColorProfile(
    val name: String,
    val dark: Boolean,
    val accent: Int,
)

@Serializable
data class AppConfig(
    val llm: LlmConfig = LlmConfig(),
    val rag: RagConfig = RagConfig(),
    /** Perfiles de color guardados por el usuario (tema + acento con nombre). */
    val uiColorProfiles: List<ColorProfile> = emptyList(),
) {
    companion object {
        /** Carga la config; si es la primera vez crea config, system prompt y carpeta de docs internas. */
        fun loadOrCreate(): AppConfig {
            val file = AppPaths.resolve("config/app-config.json")
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) Files.writeString(file, AppJson.encodeToString(AppConfig()))

            val prompt = AppPaths.resolve("config/system_prompt.md")
            if (!Files.exists(prompt)) {
                val default = AppConfig::class.java.getResourceAsStream("/default_system_prompt.md")!!.readBytes()
                Files.write(prompt, default)
            }

            val internal = AppPaths.resolve("internal-docs")
            if (!Files.exists(internal)) {
                Files.createDirectories(internal)
                Files.writeString(internal.resolve("bienvenida.md"), "# Bienvenida\n\nColoca aquí tus documentos internos (.md, .txt, .rst, .adoc).\n")
            }
            val cfg = AppJson.decodeFromString<AppConfig>(Files.readString(file)).migrate()
            // buildFromSource SIEMPRE activo: sin él, el primer arranque local en una
            // máquina sin llama.cpp falla ("no tengo llama-server"). Si una config
            // vieja lo traía en false, se reactiva y persiste (el toggle ya no existe).
            return if (cfg.llm.buildFromSource) {
                cfg
            } else {
                cfg.copy(llm = cfg.llm.copy(buildFromSource = true)).save()
            }
        }
    }

    /**
     * Migraciones de una sola vez por instalación (según [LlmConfig.configVersion]).
     * v1 → v2: activa `buildFromSource` (en v1 quedaba en false y el arranque local
     * nunca clonaba/compilaba llama.cpp: el síntoma era "no tengo llama-server").
     * v2 → v3: `chatTimeoutMs = 0` — sin límite de tiempo por respuesta (antes 90 s).
     */
    fun migrate(): AppConfig {
        if (llm.configVersion >= LlmConfig.CURRENT_CONFIG_VERSION) return this
        var l = llm
        if (l.configVersion < 2) l = l.copy(buildFromSource = true)
        if (l.configVersion < 3) l = l.copy(chatTimeoutMs = 0L)
        l = l.copy(configVersion = LlmConfig.CURRENT_CONFIG_VERSION)
        return copy(llm = l).save()
    }

    /** Persiste la config actual (la usa el gestor de modelos/APIs). */
    fun save(): AppConfig {
        val file = AppPaths.resolve("config/app-config.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, AppJson.encodeToString(this))
        return this
    }
}
