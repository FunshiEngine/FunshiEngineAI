package rag.config

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import llm.config.LlmConfig
import rag.mcp.McpConfig
import voice.config.VoiceConfig

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

/**
 * Migración en disco de la carpeta de los modelos de **texto**: antes vivían
 * en `<app-home>/models/` y ahora en `<app-home>/models/texto/`, para que
 * convivan con los de voz (`<app-home>/models/voz/`) sin mezclarse.
 *
 * Reglas:
 *
 * - Se mueve **todo** lo que hay en la carpeta vieja **salvo** la carpeta de
 *   voz (`voz`) y la propia carpeta destino (`texto`).
 * - No se pisa nada: si en el destino ya hay un archivo con el mismo nombre,
 *   el destino queda intacto. Si los dos pesan lo mismo, es el mismo modelo y
 *   se borra el de la carpeta vieja; si no, el movimiento se considera
 *   fallido (así la app no queda sin modelo).
 * - Es idempotente: si se corta a mitad, al volver a arrancar sigue moviendo
 *   lo que falta.
 *
 * Devuelve `true` si no quedó ningún modelo de texto en la carpeta vieja.
 */
internal fun moveLlmModelsToTextoDir(home: Path): Boolean {
    val vieja = home.resolve("models")
    val nueva = vieja.resolve("texto")
    if (!Files.isDirectory(vieja)) return true
    val items = try {
        Files.list(vieja).use { it.toList() }
    } catch (_: Exception) {
        return false
    }
    var ok = true
    for (item in items) {
        val nombre = item.fileName.toString()
        // La carpeta de voz ya está donde debe: no se toca.
        if (nombre == "voz" || nombre == "texto") continue
        val destino = nueva.resolve(nombre)
        try {
            Files.createDirectories(nueva)
            if (Files.exists(destino)) {
                val mismo = Files.isRegularFile(item) && Files.isRegularFile(destino) &&
                    Files.size(item) == Files.size(destino)
                if (mismo) {
                    Files.deleteIfExists(item)
                } else {
                    ok = false
                }
            } else {
                Files.move(item, destino)
            }
        } catch (_: Exception) {
            ok = false
        }
    }
    return ok
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
    /** Herramientas: interruptor general y timeout por tool (F7). */
    val tools: ToolsConfig = ToolsConfig(),
    /** Salidas de los chats: carpeta y topes de tamaño (F7). */
    val outputs: OutputsConfig = OutputsConfig(),
    /** LaTeX: motor, timeout y tope del documento (F7). */
    val latex: LatexConfig = LatexConfig(),
    /** Servidores MCP declarados (F7; el transporte llega en la ronda siguiente). */
    val mcp: McpConfig = McpConfig(),
    /** Voz: dictado por voz y chat por voz (V1). */
    val voice: VoiceConfig = VoiceConfig(),
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
     * v3 → v4: secciones `tools`, `outputs`, `latex` y `mcp`. Son nuevas, así que
     * entran con sus valores por defecto (los mismos que usaban las constantes
     * del código antes de la migración): una config migrada se comporta igual.
     * v4 → v5: sección `voice` (dictado y locución). También es nueva: entra
     * **apagada**, así que una instalación migrada no cambia de comportamiento.
     * v5 → v6: los modelos de texto pasan a `models/texto` (dejan de compartir
     * carpeta con los de voz, que viven en `models/voz`). Esta **sí** mueve
     * archivos: por eso [migrate] los mueve antes de guardar la config.
     */
    fun migrate(): AppConfig {
        if (llm.configVersion >= LlmConfig.CURRENT_CONFIG_VERSION) return this
        val nueva = migrated()
        // La carpeta de los modelos cambia: hay que mover los archivos ya
        // descargados **antes** de guardar la config nueva. Si no se movieran,
        // la app no encontraría el modelo y lo volvería a bajar (2 GB de nuevo).
        // Si el movimiento no se puede hacer, se deja la carpeta vieja: el
        // modelo sigue donde está y todo funciona igual.
        val final = if (nueva.llm.modelsDir != llm.modelsDir &&
            moveLlmModelsToTextoDir(AppPaths.home)
        ) {
            nueva
        } else {
            nueva.copy(llm = nueva.llm.copy(modelsDir = llm.modelsDir))
        }
        return final.save()
    }

    /**
     * La migración sin escribir en disco (pura): misma regla que [migrate],
     * pero devuelve la config nueva en vez de guardarla. Así se puede testear
     * sin tocar la carpeta del usuario.
     */
    internal fun migrated(): AppConfig {
        if (llm.configVersion >= LlmConfig.CURRENT_CONFIG_VERSION) return this
        var l = llm
        if (l.configVersion < 2) l = l.copy(buildFromSource = true)
        if (l.configVersion < 3) l = l.copy(chatTimeoutMs = 0L)
        if (l.configVersion < 6) l = l.copy(modelsDir = "models/texto")
        l = l.copy(configVersion = LlmConfig.CURRENT_CONFIG_VERSION)
        return copy(llm = l)
    }

    /** Persiste la config actual (la usa el gestor de modelos/APIs). */
    fun save(): AppConfig {
        val file = AppPaths.resolve("config/app-config.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, AppJson.encodeToString(this))
        return this
    }

    /**
     * Config de voz **resuelta**: si el dictado o la locución no tienen URL ni
     * clave propias, se usan los del motor de inferencia activo.
     *
     * Así el caso común (la misma API que ya speakea, también transcribe) no
     * obliga a repetir la URL y la clave en dos lados.
     */
    fun resolvedVoice(): VoiceConfig {
        val v = voice
        return v.copy(
            stt = v.stt.copy(
                baseUrl = v.stt.baseUrl.ifBlank { llm.remoteBaseUrl },
                apiKey = v.stt.apiKey.ifBlank { llm.apiKey },
            ),
            tts = v.tts.copy(
                baseUrl = v.tts.baseUrl.ifBlank { llm.remoteBaseUrl },
                apiKey = v.tts.apiKey.ifBlank { llm.apiKey },
            ),
        )
    }
}
