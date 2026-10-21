package rag.config

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * La migración de la carpeta de los modelos de texto (`models/` →
 * `models/texto/`).
 *
 * Lo importante es que el usuario **no pierde** el modelo que ya tenía
 * descargado (son GB) y que la carpeta de voz no se toca.
 */
class ModelsDirMigrationTest {

    @Test
    fun `los modelos se mueven a la carpeta de texto`(@TempDir home: Path) {
        val viejo = home.resolve("models").resolve("Phi-4.gguf")
        Files.createDirectories(viejo.parent)
        Files.write(viejo, ByteArray(10))

        assertTrue(moveLlmModelsToTextoDir(home))

        val nuevo = home.resolve("models/texto/Phi-4.gguf")
        assertTrue(Files.isRegularFile(nuevo), "el modelo debe quedar en models/texto")
        assertFalse(Files.exists(viejo), "no debe quedar nada en la carpeta vieja")
    }

    @Test
    fun `la carpeta de voz no se toca`(@TempDir home: Path) {
        val voz = home.resolve("models/voz/ggml-tiny.bin")
        Files.createDirectories(voz.parent)
        Files.write(voz, ByteArray(5))

        assertTrue(moveLlmModelsToTextoDir(home))

        assertTrue(Files.isRegularFile(voz), "los modelos de voz se quedan donde estaban")
        assertFalse(Files.exists(home.resolve("models/texto/voz")))
    }

    @Test
    fun `la descarga a medias tambien se mueve`(@TempDir home: Path) {
        val parte = home.resolve("models/Phi-4.gguf.part")
        Files.createDirectories(parte.parent)
        Files.write(parte, ByteArray(3))

        assertTrue(moveLlmModelsToTextoDir(home))

        assertTrue(Files.isRegularFile(home.resolve("models/texto/Phi-4.gguf.part")))
    }

    @Test
    fun `si el destino ya tiene el modelo se borra el de la carpeta vieja`(@TempDir home: Path) {
        val viejo = home.resolve("models/Phi-4.gguf")
        val nuevo = home.resolve("models/texto/Phi-4.gguf")
        Files.createDirectories(viejo.parent)
        Files.createDirectories(nuevo.parent)
        Files.write(viejo, ByteArray(10))
        Files.write(nuevo, ByteArray(10))

        assertTrue(moveLlmModelsToTextoDir(home))

        assertTrue(Files.isRegularFile(nuevo), "el destino no se pisa")
        assertFalse(Files.exists(viejo))
    }

    @Test
    fun `si el destino tiene otro archivo con el mismo nombre no se pisa`(@TempDir home: Path) {
        val viejo = home.resolve("models/Phi-4.gguf")
        val nuevo = home.resolve("models/texto/Phi-4.gguf")
        Files.createDirectories(viejo.parent)
        Files.createDirectories(nuevo.parent)
        Files.write(viejo, ByteArray(10))
        Files.write(nuevo, ByteArray(99))

        assertFalse(moveLlmModelsToTextoDir(home), "no se puede migrar sin perder un modelo")

        assertEquals(99L, Files.size(nuevo), "el destino queda intacto")
        assertTrue(Files.exists(viejo), "el origen se queda donde estaba")
    }

    @Test
    fun `sin carpeta models no hay nada que hacer`(@TempDir home: Path) {
        assertTrue(moveLlmModelsToTextoDir(home))
    }

    @Test
    fun `es idempotente`(@TempDir home: Path) {
        val viejo = home.resolve("models/Phi-4.gguf")
        Files.createDirectories(viejo.parent)
        Files.write(viejo, ByteArray(10))

        assertTrue(moveLlmModelsToTextoDir(home))
        assertTrue(moveLlmModelsToTextoDir(home))

        assertTrue(Files.isRegularFile(home.resolve("models/texto/Phi-4.gguf")))
    }
}
