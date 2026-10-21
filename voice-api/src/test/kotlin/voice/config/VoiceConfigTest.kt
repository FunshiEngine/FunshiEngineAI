package voice.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests de la configuración de voz: la validación tiene que avisar **antes**
 * de tocar el micrófono (fail-fast), y los valores por defecto tienen que
 * dejar la función apagada.
 */
class VoiceConfigTest {

    @Test
    fun `por defecto la voz esta apagada`() {
        val cfg = VoiceConfig()
        assertFalse(cfg.enabled)
        assertFalse(cfg.hasStt)
        assertFalse(cfg.hasTts)
    }

    @Test
    fun `una voz apagada no se valida contra nada`() {
        assertTrue(VoiceConfig().validate().isEmpty())
    }

    @Test
    fun `el dictado por api sin url avisa`() {
        val cfg = VoiceConfig(enabled = true, stt = SttConfig(provider = SttProvider.OPENAI))
        val problemas = cfg.validate()
        assertTrue(problemas.any { it.contains("URL base") }, "esperaba el aviso de la URL: $problemas")
    }

    @Test
    fun `el dictado por api con url y sin clave avisa`() {
        val cfg = VoiceConfig(
            enabled = true,
            stt = SttConfig(provider = SttProvider.OPENAI, baseUrl = "https://api.ejemplo.com/v1"),
        )
        assertTrue(cfg.validate().any { it.contains("clave") })
    }

    @Test
    fun `un endpoint local no pide clave`() {
        val cfg = VoiceConfig(
            enabled = true,
            stt = SttConfig(provider = SttProvider.OPENAI, baseUrl = "http://localhost:9000/v1"),
        )
        assertTrue(cfg.validate().isEmpty(), "los endpoints locales no piden clave: ${cfg.validate()}")
    }

    @Test
    fun `el dictado local sin modelo avisa`() {
        val cfg = VoiceConfig(
            enabled = true,
            stt = SttConfig(provider = SttProvider.WHISPER_CLI, whisperModelPath = ""),
        )
        assertTrue(cfg.validate().any { it.contains("whisper") })
    }

    @Test
    fun `el dictado local con modelo no avisa`() {
        val cfg = VoiceConfig(
            enabled = true,
            stt = SttConfig(provider = SttProvider.WHISPER_CLI, whisperModelPath = "C:/modelos/ggml-base.bin"),
        )
        assertTrue(cfg.validate().isEmpty(), cfg.validate().toString())
    }

    @Test
    fun `una tasa de muestreo fuera de rango avisa`() {
        val cfg = VoiceConfig(capture = CaptureConfig(sampleRate = 100))
        assertTrue(cfg.validate().any { it.contains("sampleRate") })
    }

    @Test
    fun `un umbral de silencio fuera de rango avisa`() {
        val cfg = VoiceConfig(capture = CaptureConfig(silenceThreshold = 5f))
        assertTrue(cfg.validate().any { it.contains("silenceThreshold") })
    }

    @Test
    fun `un tope de grabacion menor que el minimo avisa`() {
        val cfg = VoiceConfig(capture = CaptureConfig(minRecordingMs = 5_000, maxRecordingMs = 1_000))
        assertTrue(cfg.validate().any { it.contains("maxRecordingMs") })
    }

    @Test
    fun `el nombre de un proveedor se convierte desde el texto guardado`() {
        assertEquals(SttProvider.OPENAI, SttProvider.fromName("openai"))
        assertEquals(SttProvider.WHISPER_CLI, SttProvider.fromName(" WHISPER_CLI "))
        assertEquals(null, SttProvider.fromName("no-existe"))
        assertEquals(TtsProvider.SYSTEM, TtsProvider.fromName("system"))
    }
}
