package llm.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests de la validación de la config del LLM.
 *
 * La validación es fail-fast: la app tiene que avisar de una config rota con
 * un mensaje claro, en vez de fallar a mitad de una conversación con un error
 * que nadie entiende.
 */
class LlmConfigTest {

    @Test
    fun `la config por defecto es valida`() {
        assertTrue(LlmConfig().validate().isEmpty(), "los defaults tienen que ser válidos")
    }

    @Test
    fun `maxTokens tiene que ser positivo`() {
        val errors = LlmConfig(maxTokens = 0).validate()
        assertTrue(errors.any { "maxTokens" in it }, "hay que avisar del maxTokens: $errors")
    }

    @Test
    fun `la temperatura tiene que estar entre 0 y 2`() {
        assertTrue(LlmConfig(temperature = 2.5).validate().any { "temperature" in it })
        assertTrue(LlmConfig(temperature = -0.1).validate().any { "temperature" in it })
    }

    @Test
    fun `el contexto tiene que estar en rango`() {
        assertTrue(LlmConfig(contextSize = 10).validate().any { "contextSize" in it })
        assertTrue(LlmConfig(contextSize = 999_999).validate().any { "contextSize" in it })
    }

    @Test
    fun `el puerto tiene que estar en el rango valido`() {
        assertTrue(LlmConfig(localPort = 0).validate().any { "localPort" in it })
        assertTrue(LlmConfig(localPort = 70_000).validate().any { "localPort" in it })
    }

    @Test
    fun `el timeout de chat no puede ser negativo`() {
        assertTrue(LlmConfig(chatTimeoutMs = -1).validate().any { "chatTimeoutMs" in it })
        assertTrue(LlmConfig(chatTimeoutMs = 0).validate().isEmpty(), "0 = sin límite, es válido")
    }

    @Test
    fun `en modo local hacen falta repo y archivo`() {
        val errors = LlmConfig(mode = LlmMode.LOCAL, hfRepo = "", hfFile = "").validate()
        assertTrue(errors.any { "hfRepo" in it }, "hay que avisar del repo: $errors")
    }

    @Test
    fun `en modo remoto hace falta la url`() {
        val errors = LlmConfig(mode = LlmMode.REMOTE, remoteBaseUrl = "").validate()
        assertTrue(errors.any { "remoteBaseUrl" in it }, "hay que avisar de la URL: $errors")
    }

    @Test
    fun `una url pegada con la clave se detecta`() {
        val errors = LlmConfig(
            mode = LlmMode.REMOTE,
            remoteBaseUrl = "sk-abc123",
            modelName = "gpt-4o-mini",
            apiKey = "sk-abc123",
        ).validate()
        assertTrue(
            errors.any { "http" in it },
            "el error tiene que explicar el formato de la URL: $errors",
        )
    }

    @Test
    fun `en modo remoto hace falta el nombre del modelo`() {
        val errors = LlmConfig(
            mode = LlmMode.REMOTE,
            remoteBaseUrl = "https://api.openai.com/v1",
            modelName = "",
            apiKey = "sk-abc",
        ).validate()
        assertTrue(errors.any { "modelName" in it }, "hay que avisar del modelo: $errors")
    }

    @Test
    fun `una api remota sin clave es un error`() {
        val errors = LlmConfig(
            mode = LlmMode.REMOTE,
            remoteBaseUrl = "https://api.openai.com/v1",
            modelName = "gpt-4o-mini",
            apiKey = "",
        ).validate()
        assertTrue(errors.any { "apiKey" in it }, "una API remota exige clave: $errors")
    }

    @Test
    fun `un endpoint localhost no necesita clave`() {
        val errors = LlmConfig(
            mode = LlmMode.REMOTE,
            remoteBaseUrl = "http://localhost:11434/v1",
            modelName = "llama3.2",
            apiKey = "",
        ).validate()
        assertTrue(errors.isEmpty(), "Ollama y LM Studio funcionan sin clave: $errors")
    }

    @Test
    fun `la version del esquema esta declarada`() {
        assertTrue(LlmConfig.CURRENT_CONFIG_VERSION >= 3)
        assertEquals(LlmConfig.CURRENT_CONFIG_VERSION, LlmConfig().configVersion)
    }
}
