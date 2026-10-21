package voice.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import voice.api.AudioClip
import voice.api.AudioFormat
import voice.api.SpeechToTextGateway
import voice.api.TextToSpeechGateway
import voice.api.Transcription
import voice.audio.AudioPlayback
import voice.audio.MicrophoneCapture
import voice.config.CaptureConfig
import voice.error.VoiceErrorCode
import voice.error.VoiceException
import voice.test.runTest

/**
 * Tests de la sesión de voz con dobles: no hay micrófono, no hay motor y no
 * hay red. Lo que se comprueba es el **ciclo**: escuchar → transcribir →
 * entregar el texto, y texto → voz.
 */
class VoiceSessionTest {

    /** Micrófono falso: devuelve los tramos que se le pasen, uno por vez. */
    private class FakeCapture(
        private val frames: List<ByteArray>,
        private val format: AudioFormat = AudioFormat(16_000, channels = 1),
    ) : MicrophoneCapture {
        var closed = false
            private set
        private var index = 0

        override suspend fun open(sampleRate: Int): AudioFormat = format

        override suspend fun read(): ByteArray {
            if (index >= frames.size) return ByteArray(0)
            return frames[index++]
        }

        override fun close() {
            closed = true
        }
    }

    /** Motor de dictado falso. */
    private class FakeStt(
        private val result: Transcription = Transcription("hola"),
        private val error: VoiceException? = null,
    ) : SpeechToTextGateway {
        override val id = "fake"
        var clip: AudioClip? = null
            private set

        override suspend fun transcribe(clip: AudioClip): Transcription {
            this.clip = clip
            error?.let { throw it }
            return result
        }
    }

    /** Reproductor falso: guarda lo que se le pasó. */
    private class FakePlayback : AudioPlayback {
        var played: AudioClip? = null
            private set
        var stopped = false
            private set

        override suspend fun play(clip: AudioClip) {
            played = clip
        }

        override fun stop() {
            stopped = true
        }
    }

    /** Motor de locución falso. */
    private class FakeTts : TextToSpeechGateway {
        override val id = "fake"
        var texto: String? = null
            private set

        override suspend fun synthesize(text: String, options: voice.api.TtsOptions): AudioClip {
            texto = text
            return AudioClip(ByteArray(8), 16_000)
        }
    }

    /** PCM de la amplitud dada (altavoz = 8000, silencio = 0). */
    private fun pcm(samples: Int, amplitude: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Ajustes que cortan la frase rápido (100 ms de silencio). */
    private fun ajustes() = CaptureConfig(
        sampleRate = 16_000,
        silenceThreshold = 0.02f,
        silenceTimeoutMs = 100,
        minRecordingMs = 50,
        maxRecordingMs = 5_000,
    )

    @Test
    fun `la voz escuchada llega como texto`() = runTest {
        val stt = FakeStt(Transcription("hola qué tal"))
        val session = VoiceSession(
            capture = FakeCapture(listOf(pcm(1600, 8000), pcm(1600, 0))),
            stt = stt,
            playback = FakePlayback(),
            settings = ajustes(),
        )
        val result = session.listen()
        assertTrue(result is VoiceSession.ListenResult.Text, "esperaba texto, fue $result")
        assertEquals("hola qué tal", (result as VoiceSession.ListenResult.Text).text)
    }

    @Test
    fun `el microfono se cierra al terminar de escuchar`() = runTest {
        val capture = FakeCapture(listOf(pcm(1600, 8000), pcm(1600, 0)))
        val session = VoiceSession(capture, FakeStt(), FakePlayback(), settings = ajustes())
        session.listen()
        assertTrue(capture.closed, "el micrófono no puede quedar abierto")
    }

    @Test
    fun `el audio se lleva al formato que pide el motor`() = runTest {
        val stt = FakeStt()
        // El micrófono entrega 44,1 kHz mono: la sesión lo deja en 16 kHz.
        val capture = FakeCapture(
            listOf(pcm(4410, 8000), pcm(4410, 0)),
            format = AudioFormat(44_100, channels = 1),
        )
        val session = VoiceSession(capture, stt, FakePlayback(), settings = ajustes())
        session.listen()
        assertEquals(16_000, stt.clip!!.sampleRate)
    }

    @Test
    fun `sin voz se avisa y no se transcribe`() = runTest {
        val stt = FakeStt()
        // Sólo silencio: el detector nunca cierra la frase, pero el tope
        // de la grabación la corta.
        val session = VoiceSession(
            capture = FakeCapture(listOf(pcm(16000, 0))),
            stt = stt,
            playback = FakePlayback(),
            settings = ajustes().copy(maxRecordingMs = 500, minRecordingMs = 50),
        )
        val result = session.listen()
        assertTrue(result is VoiceSession.ListenResult.Silence, "esperaba silencio, fue $result")
        assertEquals(null, stt.clip, "sin voz no se transcribe")
    }

    @Test
    fun `un fallo del motor de dictado se devuelve como error`() = runTest {
        val fallo = VoiceException(VoiceErrorCode.TRANSCRIPCION_FALLIDA, "se cayó el servicio")
        val session = VoiceSession(
            capture = FakeCapture(listOf(pcm(1600, 8000), pcm(1600, 0))),
            stt = FakeStt(error = fallo),
            playback = FakePlayback(),
            settings = ajustes(),
        )
        val result = session.listen()
        assertTrue(result is VoiceSession.ListenResult.Failed, "esperaba un fallo, fue $result")
        assertEquals("se cayó el servicio", (result as VoiceSession.ListenResult.Failed).error.message)
    }

    @Test
    fun `un microfono que se abre mal devuelve el error`() = runTest {
        val roto = object : MicrophoneCapture {
            override suspend fun open(sampleRate: Int): AudioFormat =
                throw VoiceException(VoiceErrorCode.MICROFONO_NO_DISPONIBLE, "no hay micrófono")

            override suspend fun read(): ByteArray = ByteArray(0)
            override fun close() = Unit
        }
        val session = VoiceSession(roto, FakeStt(), FakePlayback(), settings = ajustes())
        val result = session.listen()
        assertTrue(result is VoiceSession.ListenResult.Failed)
        assertEquals(
            VoiceErrorCode.MICROFONO_NO_DISPONIBLE,
            (result as VoiceSession.ListenResult.Failed).error.code,
        )
    }

    @Test
    fun `hablar reproduce el audio`() = runTest {
        val playback = FakePlayback()
        val tts = FakeTts()
        val session = VoiceSession(FakeCapture(emptyList()), FakeStt(), playback, tts, ajustes())
        val ok = session.speak("hola")
        assertTrue(ok)
        assertEquals("hola", tts.texto)
        assertTrue(playback.played != null)
    }

    @Test
    fun `sin motor de locucion no se puede hablar`() = runTest {
        val session = VoiceSession(FakeCapture(emptyList()), FakeStt(), FakePlayback(), null, ajustes())
        assertFalse(session.speak("hola"))
    }

    @Test
    fun `un texto vacio no se lee`() = runTest {
        val tts = FakeTts()
        val session = VoiceSession(FakeCapture(emptyList()), FakeStt(), FakePlayback(), tts, ajustes())
        assertFalse(session.speak("   "))
        assertEquals(null, tts.texto)
    }
}
