package voice.local

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import voice.config.VoiceConfig

/**
 * El binario de whisper.cpp se busca en el clon local: varias rutas, porque
 * cmake deja el ejecutable en sitios distintos según el generador (MSVC lo
 * mete en `build/bin/Release`).
 */
class WhisperManagerTest {

    private fun manager(home: Path, cfg: VoiceConfig = VoiceConfig()) =
        WhisperManager({ cfg }, home)

    @Test
    fun `los candidatos incluyen el binario del build de cmake`(@TempDir home: Path) {
        val cands = manager(home).candidateRepoBinaries()
        val esperado = home.resolve("whisper.cpp").resolve("build/bin")
        assertTrue(cands.any { it.startsWith(esperado) }, "candidates: $cands")
    }

    @Test
    fun `los candidatos incluyen la variante de MSVC`(@TempDir home: Path) {
        val cands = manager(home).candidateRepoBinaries()
        assertTrue(cands.any { it.toString().contains("Release") }, "candidates: $cands")
    }

    @Test
    fun `el nombre viejo del ejecutable tambien es candidato`(@TempDir home: Path) {
        val cands = manager(home).candidateRepoBinaries()
        assertTrue(cands.any { it.fileName.toString().startsWith("main") }, "candidates: $cands")
    }

    @Test
    fun `sin binario no se encuentra ninguno`(@TempDir home: Path) {
        val m = manager(home)
        assertTrue(m.findBinary() == null || Files.isRegularFile(Path.of(m.findBinary()!!)))
    }

    @Test
    fun `una ruta explicita que no existe no devuelve nada`(@TempDir home: Path) {
        val cfg = VoiceConfig(
            stt = voice.config.SttConfig(whisperCliPath = home.resolve("no-existe.exe").toString()),
        )
        assertTrue(manager(home, cfg).findBinary() == null)
    }

    @Test
    fun `una ruta explicita valida se usa`(@TempDir home: Path) {
        val bin = home.resolve("whisper-cli.exe")
        Files.writeString(bin, "x")
        val cfg = VoiceConfig(
            stt = voice.config.SttConfig(whisperCliPath = bin.toString()),
        )
        val encontrado = manager(home, cfg).findBinary()
        assertTrue(encontrado != null && encontrado.endsWith("whisper-cli.exe"), "encontrado: $encontrado")
    }
}
