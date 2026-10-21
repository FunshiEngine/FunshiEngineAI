package app.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Los repos y los nombres de modelo de **voz** se distinguen de los de texto:
 * es lo que permite separarlos en el buscador y avisar si alguien pone un
 * modelo de transcripción como modelo de chat.
 */
class ModelKindsTest {

    @Test
    fun `un repo de whisper es de voz`() {
        assertTrue(looksLikeVoiceRepo("ggerganov/whisper.cpp"))
        assertTrue(looksLikeVoiceRepo("handy-computer/whisper-base-gguf"))
    }

    @Test
    fun `un repo de llm no es de voz`() {
        assertFalse(looksLikeVoiceRepo("unsloth/Phi-4-mini-instruct-GGUF"))
        assertFalse(looksLikeVoiceRepo("Qwen/Qwen3-4B-GGUF"))
    }

    @Test
    fun `un nombre de modelo de transcripcion es de voz`() {
        assertTrue(looksLikeVoiceModelName("whisper-1"))
        assertTrue(looksLikeVoiceModelName("whisper-large-v3-turbo"))
    }

    @Test
    fun `un nombre de modelo de chat no es de voz`() {
        assertFalse(looksLikeVoiceModelName("gpt-4o-mini"))
        assertFalse(looksLikeVoiceModelName("phi-4-mini"))
        assertFalse(looksLikeVoiceModelName("gemini-3.8-flash"))
    }
}
