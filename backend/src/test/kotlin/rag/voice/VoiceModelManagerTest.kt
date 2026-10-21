package rag.voice

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import voice.catalog.VoiceModelPreset
import voice.catalog.defaultVoiceModels
import voice.config.SttProvider
import voice.config.VoiceConfig

/**
 * El gestor de modelos de voz: catálogo, activación y **separación** del
 * motor de inferencia.
 *
 * Lo importante: activar un modelo de voz escribe en `voice.stt.whisperModelPath`
 * y **nunca** toca `llm.hfRepo`/`llm.hfFile`.
 */
class VoiceModelManagerTest {

    private fun manager(
        home: Path,
        cfg: VoiceConfig,
        save: (VoiceConfig) -> Unit,
    ) = VoiceModelManager({ cfg }, home, save)

    @Test
    fun `el catalogo trae los modelos por defecto`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        assertEquals(defaultVoiceModels().size, m.entries().size)
    }

    @Test
    fun `activar un modelo escribe su ruta absoluta`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        val preset = guardada.models.first()
        m.activate(preset)
        val esperado = home.resolve(guardada.modelsDir).resolve(preset.file).toAbsolutePath().normalize()
        assertEquals(esperado.toString(), guardada.stt.whisperModelPath)
    }

    @Test
    fun `activar un modelo de voz deja el motor en whisper local`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        m.activate(guardada.models.first())
        assertEquals(SttProvider.WHISPER_CLI, guardada.stt.provider)
    }

    @Test
    fun `un modelo descargado aparece como descargado`(@TempDir home: Path) {
        val preset = VoiceModelPreset(
            id = "x", label = "X", repo = "r/x", file = "ggml-x.bin", sizeMb = 10,
        )
        var guardada = VoiceConfig(models = listOf(preset))
        val m = manager(home, guardada) { guardada = it }
        Files.createDirectories(m.modelsDir())
        Files.write(m.modelsDir().resolve("ggml-x.bin"), ByteArray(10))
        val entry = m.entries().first()
        assertTrue(entry.downloaded)
        assertEquals(10L, entry.sizeBytes)
    }

    @Test
    fun `una descarga a medias se ve como parcial`(@TempDir home: Path) {
        val preset = VoiceModelPreset(
            id = "x", label = "X", repo = "r/x", file = "ggml-x.bin", sizeMb = 10,
        )
        var guardada = VoiceConfig(models = listOf(preset))
        val m = manager(home, guardada) { guardada = it }
        Files.createDirectories(m.modelsDir())
        Files.write(m.modelsDir().resolve("ggml-x.bin.part"), ByteArray(4))
        val entry = m.entries().first()
        assertFalse(entry.downloaded)
        assertEquals(4L, entry.partBytes)
    }

    @Test
    fun `el modelo en uso es el que esta en la config`(@TempDir home: Path) {
        val preset = VoiceModelPreset(
            id = "x", label = "X", repo = "r/x", file = "ggml-x.bin", sizeMb = 10,
        )
        val destino = home.resolve("models/voz/ggml-x.bin").toAbsolutePath().normalize().toString()
        var guardada = VoiceConfig(
            models = listOf(preset),
            stt = voice.config.SttConfig(whisperModelPath = destino),
        )
        val m = manager(home, guardada) { guardada = it }
        assertTrue(m.entries().first().active)
    }

    @Test
    fun `agregar un modelo lo agrega al catalogo`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        val antes = guardada.models.size
        val preset = VoiceModelPreset(id = "nuevo", label = "Nuevo", repo = "r/n", file = "ggml-n.bin")
        m.addModel(preset)
        assertEquals(antes + 1, guardada.models.size)
        assertEquals("nuevo", guardada.models.last().id)
    }

    @Test
    fun `quitar un modelo lo saca del catalogo`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        m.removeModel("nuevo")
        assertTrue(guardada.models.none { it.id == "nuevo" })
    }

    @Test
    fun `la carpeta de los modelos de voz es propia`(@TempDir home: Path) {
        var guardada = VoiceConfig()
        val m = manager(home, guardada) { guardada = it }
        assertEquals(home.resolve(guardada.modelsDir).toAbsolutePath().normalize(), m.modelsDir())
    }
}
