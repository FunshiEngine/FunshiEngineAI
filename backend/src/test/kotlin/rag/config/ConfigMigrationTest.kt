package rag.config

import kotlinx.serialization.encodeToString
import llm.config.LlmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import voice.config.SttProvider
import voice.config.TtsProvider

/**
 * Tests de la configuración de la app y su migración (F7 config v4, V1 config v5).
 *
 * La migración tiene que ser invisible: una config vieja migrada tiene que
 * comportarse EXACTO igual que antes (mismos topes, mismo motor, mismos
 * permisos). Si esto se rompe, el usuario ve cambios que no pidió.
 */
class ConfigMigrationTest {

    /** Config tal como quedó en v3 (sin las secciones nuevas). */
    private fun configV3(): AppConfig = AppConfig(
        llm = LlmConfig(configVersion = 3),
    )

    /** Config tal como quedó en v4 (con tools/outputs/latex/mcp, sin voz). */
    private fun configV4(): AppConfig = AppConfig(
        llm = LlmConfig(configVersion = 4),
    )

    @Test
    fun `la version actual es la 5`() {
        assertEquals(5, LlmConfig.CURRENT_CONFIG_VERSION)
    }

    @Test
    fun `una config v3 se migra a la 5`() {
        val migrada = configV3().migrated()
        assertEquals(5, migrada.llm.configVersion)
    }

    @Test
    fun `una config v4 se migra a la 5`() {
        val migrada = configV4().migrated()
        assertEquals(5, migrada.llm.configVersion)
    }

    @Test
    fun `la migracion no cambia los valores que ya existian`() {
        val original = configV3()
        val migrada = original.migrated()
        assertEquals(original.llm.hfRepo, migrada.llm.hfRepo)
        assertEquals(original.llm.hfFile, migrada.llm.hfFile)
        assertEquals(original.llm.temperature, migrada.llm.temperature)
        assertEquals(original.llm.maxTokens, migrada.llm.maxTokens)
        assertEquals(original.llm.chatTimeoutMs, migrada.llm.chatTimeoutMs)
        assertEquals(original.rag, migrada.rag)
    }

    @Test
    fun `la migracion agrega las secciones nuevas con los valores de antes`() {
        val migrada = configV3().migrated()
        // Los mismos valores que usaban las constantes del código.
        assertTrue(migrada.tools.enabled)
        assertEquals(60_000L, migrada.tools.timeoutMs)
        assertEquals("outputs", migrada.outputs.dir)
        assertEquals(5, migrada.outputs.maxFileMb)
        assertEquals(50, migrada.outputs.maxDownloadMb)
        assertEquals("", migrada.latex.engine)
        assertEquals(60_000L, migrada.latex.timeoutMs)
        assertEquals(1024, migrada.latex.maxDocumentKb)
        assertTrue(migrada.mcp.servers.isEmpty())
    }

    @Test
    fun `la migracion a la v5 deja la voz apagada`() {
        val migrada = configV4().migrated()
        assertFalse(migrada.voice.enabled, "la voz entra apagada: no cambia el comportamiento")
        assertEquals(SttProvider.NONE, migrada.voice.stt.provider)
        assertEquals(TtsProvider.NONE, migrada.voice.tts.provider)
    }

    @Test
    fun `una config ya migrada no cambia`() {
        val v5 = AppConfig(llm = LlmConfig(configVersion = 5))
        val otra = v5.migrated()
        assertEquals(v5.llm.configVersion, otra.llm.configVersion)
        assertEquals(v5.tools, otra.tools)
        assertEquals(v5.voice, otra.voice)
    }

    @Test
    fun `la migracion desde la version 1 conserva los arreglo viejos`() {
        val v1 = AppConfig(llm = LlmConfig(configVersion = 1, buildFromSource = false))
        val migrada = v1.migrated()
        assertTrue(migrada.llm.buildFromSource, "v1 → v2 activa buildFromSource")
        assertEquals(0L, migrada.llm.chatTimeoutMs, "v2 → v3 deja el timeout sin límite")
        assertEquals(5, migrada.llm.configVersion)
    }

    @Test
    fun `una config v3 en json se lee con las secciones nuevas por defecto`() {
        val json = """{"llm":{"configVersion":3}}"""
        val leida = AppJson.decodeFromString<AppConfig>(json)
        assertEquals(3, leida.llm.configVersion)
        assertTrue(leida.tools.enabled)
        assertEquals("outputs", leida.outputs.dir)
        assertTrue(leida.mcp.servers.isEmpty())
        assertFalse(leida.voice.enabled, "sin sección `voice`, la voz queda apagada")
    }

    @Test
    fun `una config v4 con las secciones se lee tal cual`() {
        val json = """
            {"llm":{"configVersion":4},
             "tools":{"enabled":false,"timeoutMs":5000},
             "outputs":{"dir":"salidas","maxFileMb":2,"maxDownloadMb":10},
             "latex":{"engine":"xelatex","timeoutMs":1000,"maxDocumentKb":64},
             "mcp":{"servers":[{"id":"docs","command":"docs-server","enabled":true}]}}
        """.trimIndent()
        val leida = AppJson.decodeFromString<AppConfig>(json)
        assertEquals(false, leida.tools.enabled)
        assertEquals(5000L, leida.tools.timeoutMs)
        assertEquals("salidas", leida.outputs.dir)
        assertEquals(2, leida.outputs.maxFileMb)
        assertEquals("xelatex", leida.latex.engine)
        assertEquals(1, leida.mcp.servers.size)
        assertEquals("docs", leida.mcp.servers[0].id)
    }

    @Test
    fun `una config v5 con la seccion voice se lee tal cual`() {
        val json = """
            {"llm":{"configVersion":5},
             "voice":{"enabled":true,
                      "stt":{"provider":"WHISPER_CLI","whisperModelPath":"C:/m/ggml-base.bin"},
                      "tts":{"provider":"SYSTEM"},
                      "capture":{"sampleRate":16000,"silenceThreshold":0.05},
                      "speakAnswers":false,
                      "maxSpeakChars":800}}
        """.trimIndent()
        val leida = AppJson.decodeFromString<AppConfig>(json)
        assertTrue(leida.voice.enabled)
        assertEquals(SttProvider.WHISPER_CLI, leida.voice.stt.provider)
        assertEquals("C:/m/ggml-base.bin", leida.voice.stt.whisperModelPath)
        assertEquals(TtsProvider.SYSTEM, leida.voice.tts.provider)
        assertEquals(0.05f, leida.voice.capture.silenceThreshold)
        assertFalse(leida.voice.speakAnswers)
        assertEquals(800, leida.voice.maxSpeakChars)
    }

    @Test
    fun `la config se puede serializar y volver a leer`() {
        val config = AppConfig(
            tools = ToolsConfig(enabled = false, timeoutMs = 1234),
            outputs = OutputsConfig(dir = "salidas", maxFileMb = 3),
            latex = LatexConfig(engine = "xelatex"),
        )
        val json = AppJson.encodeToString(config)
        val leida = AppJson.decodeFromString<AppConfig>(json)
        assertEquals(config, leida)
    }

    @Test
    fun `los topes en bytes salen de los megabytes`() {
        val outputs = OutputsConfig(maxFileMb = 2, maxDownloadMb = 3)
        assertEquals(2L * 1024 * 1024, outputs.maxFileBytes)
        assertEquals(3L * 1024 * 1024, outputs.maxDownloadBytes)
    }

    @Test
    fun `un tope en cero no rompe los calculos`() {
        val outputs = OutputsConfig(maxFileMb = 0, maxDownloadMb = 0)
        assertTrue(outputs.maxFileBytes > 0, "un tope de 0 no puede dejar pasar todo")
        assertTrue(outputs.maxDownloadBytes > 0)
    }
}
