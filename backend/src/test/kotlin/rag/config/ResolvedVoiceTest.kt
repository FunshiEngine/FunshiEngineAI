package rag.config

import llm.config.LlmConfig
import llm.config.LlmMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import voice.config.SttConfig
import voice.config.SttProvider
import voice.config.TtsConfig
import voice.config.TtsProvider
import voice.config.VoiceConfig

/**
 * Tests de la config de voz **resuelta**: si el dictado o la locución no
 * tienen URL ni clave propias, se usan los del motor de inferencia activo.
 */
class ResolvedVoiceTest {

    @Test
    fun `sin url propia se usa la del motor activo`() {
        val config = AppConfig(
            llm = LlmConfig(mode = LlmMode.REMOTE, remoteBaseUrl = "https://api.ejemplo.com/v1", apiKey = "secreto"),
            voice = VoiceConfig(
                stt = SttConfig(provider = SttProvider.OPENAI),
                tts = TtsConfig(provider = TtsProvider.OPENAI),
            ),
        )
        val resuelta = config.resolvedVoice()
        assertEquals("https://api.ejemplo.com/v1", resuelta.stt.baseUrl)
        assertEquals("secreto", resuelta.stt.apiKey)
        assertEquals("https://api.ejemplo.com/v1", resuelta.tts.baseUrl)
        assertEquals("secreto", resuelta.tts.apiKey)
    }

    @Test
    fun `la url propia tiene prioridad`() {
        val config = AppConfig(
            llm = LlmConfig(mode = LlmMode.REMOTE, remoteBaseUrl = "https://api.ejemplo.com/v1", apiKey = "secreto"),
            voice = VoiceConfig(
                stt = SttConfig(provider = SttProvider.OPENAI, baseUrl = "https://voz.ejemplo.com/v1", apiKey = "otra"),
            ),
        )
        val resuelta = config.resolvedVoice()
        assertEquals("https://voz.ejemplo.com/v1", resuelta.stt.baseUrl)
        assertEquals("otra", resuelta.stt.apiKey)
    }

    @Test
    fun `en modo local la voz no hereda nada`() {
        val config = AppConfig(llm = LlmConfig(mode = LlmMode.LOCAL))
        val resuelta = config.resolvedVoice()
        assertEquals("", resuelta.stt.baseUrl)
        assertEquals("", resuelta.tts.baseUrl)
    }
}
