package voice.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import voice.config.SttConfig
import voice.config.SttProvider
import voice.config.TtsConfig
import voice.config.TtsProvider
import voice.config.VoiceConfig
import voice.error.VoiceErrorCode
import voice.error.VoiceException

/**
 * Tests de la fachada de voz: según la configuración se elige el motor
 * correcto (o ninguno), y una configuración incompleta falla rápido.
 */
class VoiceServiceTest {

    @Test
    fun `sin proveedor no hay motor de dictado`() {
        val service = VoiceService({ VoiceConfig() })
        assertNull(service.speechToText())
        assertNull(service.textToSpeech())
    }

    @Test
    fun `el motor de dictado por api se elige por la config`() {
        val service = VoiceService({
            VoiceConfig(stt = SttConfig(provider = SttProvider.OPENAI, baseUrl = "https://api.ejemplo.com/v1"))
        })
        val stt = service.speechToText()
        assertNotNull(stt)
        assertEquals("openai", stt!!.id)
    }

    @Test
    fun `el motor de dictado local se elige por la config`() {
        val service = VoiceService({
            VoiceConfig(stt = SttConfig(provider = SttProvider.WHISPER_CLI, whisperModelPath = "C:/m/modelo.bin"))
        })
        assertEquals("whisper-cli", service.speechToText()!!.id)
    }

    @Test
    fun `el motor de locucion del sistema se elige por la config`() {
        val service = VoiceService({ VoiceConfig(tts = TtsConfig(provider = TtsProvider.SYSTEM)) })
        assertEquals("system", service.textToSpeech()!!.id)
    }

    @Test
    fun `un dictado por api sin url falla rapido`() {
        val service = VoiceService({ VoiceConfig(stt = SttConfig(provider = SttProvider.OPENAI)) })
        val error = runCatching { service.speechToText() }.exceptionOrNull()
        assertTrue(error is VoiceException, "esperaba un VoiceException, fue $error")
        assertEquals(VoiceErrorCode.CONFIG_INVALIDA, (error as VoiceException).code)
    }

    @Test
    fun `un dictado local sin modelo falla rapido`() {
        val service = VoiceService({ VoiceConfig(stt = SttConfig(provider = SttProvider.WHISPER_CLI)) })
        val error = runCatching { service.speechToText() }.exceptionOrNull()
        assertTrue(error is VoiceException)
        assertEquals(VoiceErrorCode.CONFIG_INVALIDA, (error as VoiceException).code)
    }

    @Test
    fun `la config se lee en cada llamada`() {
        var activa = false
        val service = VoiceService({
            VoiceConfig(stt = SttConfig(provider = if (activa) SttProvider.OPENAI else SttProvider.NONE, baseUrl = "https://x/v1"))
        })
        assertNull(service.speechToText())
        activa = true
        assertNotNull(service.speechToText())
    }

    @Test
    fun `los problemas de la config se listan`() {
        val service = VoiceService({
            VoiceConfig(enabled = true, stt = SttConfig(provider = SttProvider.OPENAI))
        })
        assertTrue(service.problems().isNotEmpty())
    }
}
