package voice.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Los modelos de voz son los `ggml-*.bin` de whisper.cpp: ni los `.gguf` de
 * otros proyectos (que son para transcribe.cpp) ni los baselines de los tests
 * sirven para el motor local.
 */
class VoiceModelsTest {

    @Test
    fun `un modelo de whisper se reconoce`() {
        assertTrue(isWhisperModelFile("ggml-base.bin"))
        assertTrue(isWhisperModelFile("ggml-large-v3-turbo-q5_0.bin"))
    }

    @Test
    fun `un gguf de whisper no sirve para whisper-cli`() {
        assertFalse(isWhisperModelFile("whisper-base-Q4_K_M.gguf"))
    }

    @Test
    fun `los baselines de los tests no son modelos`() {
        assertFalse(isWhisperModelFile("for-tests-ggml-base.bin"))
    }

    @Test
    fun `los archivos que no son del modelo no cuentan`() {
        assertFalse(isWhisperModelFile("README.md"))
        assertFalse(isWhisperModelFile("models/for-tests-ggml-tiny.bin"))
    }

    @Test
    fun `el catalogo por defecto trae los modelos oficiales`() {
        val catalogo = defaultVoiceModels()
        assertTrue(catalogo.isNotEmpty())
        assertTrue(catalogo.all { it.repo == WHISPER_CPP_REPO })
        assertTrue(catalogo.all { isWhisperModelFile(it.file) })
    }

    @Test
    fun `el catalogo por defecto va de chico a grande`() {
        val tamanos = defaultVoiceModels().map { it.sizeMb }
        assertEquals(tamanos.sorted(), tamanos)
    }

    @Test
    fun `el catalogo por defecto no tiene ids repetidos`() {
        val ids = defaultVoiceModels().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
