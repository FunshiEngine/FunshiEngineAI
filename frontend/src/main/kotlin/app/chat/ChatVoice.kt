package app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import voice.audio.JdkAudioPlayback
import voice.audio.JdkMicrophoneCapture
import voice.config.SttProvider
import voice.config.TtsProvider
import voice.error.VoiceException
import voice.session.VoiceSession

/**
 * Voz en el chat (V1): **dictado por voz** y **chat por voz**.
 *
 * Son dos funciones sobre la misma machinery:
 *
 *  - **Dictado**: se pulsa el micrófono, se habla, y el texto transcrito
 *    aparece en el redactor. No se envía solo: el usuario lo revisa y lo
 *    manda como cualquier otro mensaje.
 *  - **Chat por voz**: el bucle `escuchar → enviar → responder → hablar`.
 *    El texto transcrito entra por [ChatViewModel.send], o sea por **el mismo
 *    camino que el chat de texto**: RAG, herramientas, permisos, citas y
 *    persistencia funcionan igual. La respuesta se lee en voz alta.
 *
 * Son funciones de extensión sobre el ViewModel (mismo patrón que el resto de
 * áreas): el ViewModel sigue siendo el dueño del estado.
 */

/**
 * Compone la sesión de voz con los motores de la configuración.
 *
 * Devuelve `null` (y deja el motivo en [ChatViewModel.voiceError]) si no hay
 * motor de dictado o si la configuración está incompleta: mejor un mensaje
 * claro que un micrófono que no transcribe nada.
 */
internal fun ChatViewModel.buildVoiceSession(): VoiceSession? {
    val cfg = config.resolvedVoice()
    if (!cfg.enabled) {
        voiceError = "La voz está apagada. Activala en Ajustes → Voz."
        return null
    }
    val problemas = cfg.validate()
    if (problemas.isNotEmpty()) {
        voiceError = problemas.joinToString(" ")
        return null
    }
    val stt = try {
        voiceService.speechToText()
    } catch (e: VoiceException) {
        voiceError = e.message
        return null
    }
    if (stt == null) {
        voiceError = "No hay motor de dictado configurado. Elegilo en Ajustes → Voz."
        return null
    }
    val tts = try {
        voiceService.textToSpeech()
    } catch (_: VoiceException) {
        null // Sin locución el chat por voz igual funciona (responde por texto).
    }
    return VoiceSession(
        capture = JdkMicrophoneCapture(),
        stt = stt,
        playback = JdkAudioPlayback(),
        tts = tts,
        settings = cfg.capture,
    )
}

/**
 * Dictado: empieza a escuchar o, si ya está escuchando, corta.
 *
 * El texto transcrito **se deja en el redactor**: el usuario lo revisa y lo
 * envía cuando quiere (dictar y enviar sin querer sería una sorpresa).
 */
internal fun ChatViewModel.toggleDictation() {
    if (voiceListening) {
        stopDictation()
        return
    }
    if (voiceActive) {
        voiceError = "El chat por voz está activo: termínalo antes de dictar."
        return
    }
    val session = buildVoiceSession() ?: return
    voiceError = ""
    voiceTranscript = ""
    voiceJob = scope.launch {
        try {
            voiceListening = true
            when (val result = session.listen()) {
                is VoiceSession.ListenResult.Text -> {
                    voiceTranscript = result.text
                    val actual = composerInput.trim()
                    composerInput = if (actual.isEmpty()) result.text else "$actual ${result.text}"
                }
                is VoiceSession.ListenResult.Silence -> voiceError = "No se oyó nada."
                is VoiceSession.ListenResult.Failed -> voiceError = result.error.message
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            voiceError = friendly(e)
        } finally {
            voiceListening = false
            session.stop()
        }
    }
}

/** Corta el dictado en curso. */
internal fun ChatViewModel.stopDictation() {
    voiceJob?.cancel()
    voiceJob = null
    voiceListening = false
}

/**
 * Chat por voz: arranca el bucle `escuchar → responder → hablar`.
 *
 * El micrófono se abre y se cierra en cada turno, y **nunca** está abierto
 * mientras se reproduce la respuesta: así el micrófono no escucha la voz del
 * modelo (el clásico problema de la realimentación).
 */
internal fun ChatViewModel.startVoiceChat() {
    if (voiceActive) return
    val session = buildVoiceSession() ?: return
    voiceError = ""
    voiceActive = true
    voiceJob = scope.launch {
        try {
            while (voiceActive && isActive) {
                voiceListening = true
                voiceStatus = "Escuchando…"
                val listened = session.listen()
                voiceListening = false
                val text = when (listened) {
                    is VoiceSession.ListenResult.Text -> listened.text
                    is VoiceSession.ListenResult.Silence -> continue
                    is VoiceSession.ListenResult.Failed -> {
                        voiceError = listened.error.message
                        voiceStatus = ""
                        break
                    }
                }
                voiceTranscript = text
                voiceStatus = "Pensando…"
                val answer = askAndWaitForAnswer(text)
                if (answer.isBlank()) continue
                if (config.voice.speakAnswers) {
                    voiceSpeaking = true
                    voiceStatus = "Hablando…"
                    try {
                        session.speak(textForSpeech(answer))
                    } finally {
                        voiceSpeaking = false
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            voiceError = friendly(e)
        } finally {
            voiceActive = false
            voiceListening = false
            voiceSpeaking = false
            voiceStatus = ""
            session.stop()
        }
    }
}

/** Corta el chat por voz. */
internal fun ChatViewModel.stopVoiceChat() {
    voiceActive = false
    voiceJob?.cancel()
    voiceJob = null
    voiceListening = false
    voiceSpeaking = false
    voiceStatus = ""
    voiceAnswer?.let { pending ->
        voiceAnswer = null
        pending.deferred.complete("")
    }
}

/**
 * Respuesta que el chat por voz está esperando.
 *
 * Lleva la **pregunta** que la voz mandó: así el ViewModel puede distinguir
 * la respuesta esperada de la de otro mensaje (si el usuario escribe mientras
 * la voz espera).
 */
internal class VoiceAwait(
    val question: String,
    val deferred: CompletableDeferred<String>,
)

/**
 * Manda el texto al chat y espera la respuesta.
 *
 * Es el **único** punto donde la voz toca el chat: usa [ChatViewModel.send]
 * (el mismo camino que el usuario) y espera la respuesta por el enganche
 * `voiceAnswer`.
 */
private suspend fun ChatViewModel.askAndWaitForAnswer(text: String): String {
    // No puede haber una respuesta en vuelo: si la hay, se espera a que
    // termine (si no, la respuesta que se espera sería de otro turno).
    while (busy) delay(BUSY_POLL_MS)
    val pending = CompletableDeferred<String>()
    val await = VoiceAwait(text, pending)
    voiceAnswer = await
    send(text)
    return try {
        withTimeout(ANSWER_TIMEOUT_MS) { pending.await() }
    } catch (e: TimeoutCancellationException) {
        voiceError = "El modelo no respondió a tiempo."
        ""
    } finally {
        if (voiceAnswer === await) voiceAnswer = null
    }
}

/**
 * Prepara un texto para leerlo en voz alta: sin markdown y sin pasarse de
 * largo.
 */
internal fun ChatViewModel.textForSpeech(text: String): String {
    val plain = plainForSpeech(text)
    val max = config.voice.maxSpeakChars
    if (max <= 0 || plain.length <= max) return plain
    val cut = plain.take(max)
    // Si hay un punto antes del corte, se corta ahí: queda una frase entera.
    val lastStop = cut.indexOfLast { it == '.' || it == '!' || it == '?' }
    val head = if (lastStop > max / 2) cut.take(lastStop + 1) else cut.trimEnd()
    return "$head…"
}

/**
 * Quita el markdown de un texto para poder leerlo en voz alta.
 *
 * Los bloques de código se reemplazan por "(código)": leerlos palabra por
 * palabra no sirve de nada.
 */
internal fun plainForSpeech(text: String): String {
    var out = text.replace(Regex("```[\\s\\S]*?```"), " (código) ")
    out = out.replace(Regex("`([^`]*)`"), "$1")
    out = out.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    out = out.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    out = out.replace(Regex("(?m)^#{1,6}\\s*"), "")
    out = out.replace("**", "").replace("__", "")
    out = out.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
    out = out.replace(Regex("\\s+"), " ").trim()
    return out
}

/* ---------------------------------------------------------------------------
 * Ajustes de voz (pantalla Ajustes → Voz).
 *
 * Cada cambio se persiste al instante, como el resto de los ajustes.
 * ------------------------------------------------------------------------- */

/** Interruptor general de la voz. */
internal fun ChatViewModel.setVoiceEnabled(enabled: Boolean) {
    config = config.copy(voice = config.voice.copy(enabled = enabled)).save()
}

/** Motor de dictado. */
internal fun ChatViewModel.setSttProvider(provider: String) {
    val p = SttProvider.fromName(provider) ?: return
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(provider = p))).save()
}

/** URL base del servicio de transcripción. */
internal fun ChatViewModel.setSttBaseUrl(url: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(baseUrl = url.trim()))).save()
}

/** Clave del servicio de transcripción. */
internal fun ChatViewModel.setSttApiKey(key: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(apiKey = key.trim()))).save()
}

/** Modelo de transcripción. */
internal fun ChatViewModel.setSttModel(model: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(model = model.trim()))).save()
}

/** Idioma forzado de la transcripción (vacío = que lo detecte). */
internal fun ChatViewModel.setSttLanguage(language: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(language = language.trim()))).save()
}

/** Ruta del binario de whisper.cpp (vacío = autodetectar). */
internal fun ChatViewModel.setWhisperCliPath(path: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(whisperCliPath = path.trim()))).save()
}

/** Ruta del modelo GGML de whisper. */
internal fun ChatViewModel.setWhisperModelPath(path: String) {
    config = config.copy(voice = config.voice.copy(stt = config.voice.stt.copy(whisperModelPath = path.trim()))).save()
}

/** Timeout de la transcripción, en segundos. */
internal fun ChatViewModel.setSttTimeoutSeconds(seconds: Long) {
    config = config.copy(
        voice = config.voice.copy(stt = config.voice.stt.copy(timeoutMs = seconds.coerceAtLeast(1) * 1000)),
    ).save()
}

/** Motor de locución. */
internal fun ChatViewModel.setTtsProvider(provider: String) {
    val p = TtsProvider.fromName(provider) ?: return
    config = config.copy(voice = config.voice.copy(tts = config.voice.tts.copy(provider = p))).save()
}

/** URL base del servicio de locución. */
internal fun ChatViewModel.setTtsBaseUrl(url: String) {
    config = config.copy(voice = config.voice.copy(tts = config.voice.tts.copy(baseUrl = url.trim()))).save()
}

/** Clave del servicio de locución. */
internal fun ChatViewModel.setTtsApiKey(key: String) {
    config = config.copy(voice = config.voice.copy(tts = config.voice.tts.copy(apiKey = key.trim()))).save()
}

/** Modelo de locución. */
internal fun ChatViewModel.setTtsModel(model: String) {
    config = config.copy(voice = config.voice.copy(tts = config.voice.tts.copy(model = model.trim()))).save()
}

/** Voz del motor de locución. */
internal fun ChatViewModel.setTtsVoice(voice: String) {
    config = config.copy(voice = config.voice.copy(tts = config.voice.tts.copy(voice = voice.trim()))).save()
}

/** Velocidad de la locución. */
internal fun ChatViewModel.setTtsSpeed(speed: Float) {
    config = config.copy(
        voice = config.voice.copy(tts = config.voice.tts.copy(speed = speed.coerceIn(0.5f, 2.0f))),
    ).save()
}

/** Timeout de la locución, en segundos. */
internal fun ChatViewModel.setTtsTimeoutSeconds(seconds: Long) {
    config = config.copy(
        voice = config.voice.copy(tts = config.voice.tts.copy(timeoutMs = seconds.coerceAtLeast(1) * 1000)),
    ).save()
}

/** Tasa de muestreo de la captura. */
internal fun ChatViewModel.setCaptureSampleRate(rate: Int) {
    config = config.copy(
        voice = config.voice.copy(capture = config.voice.capture.copy(sampleRate = rate.coerceIn(8_000, 48_000))),
    ).save()
}

/** Umbral de silencio (0..1). */
internal fun ChatViewModel.setSilenceThreshold(value: Float) {
    config = config.copy(
        voice = config.voice.copy(capture = config.voice.capture.copy(silenceThreshold = value.coerceIn(0f, 1f))),
    ).save()
}

/** Silencio (ms) que cierra la frase. */
internal fun ChatViewModel.setSilenceTimeoutMs(ms: Long) {
    config = config.copy(
        voice = config.voice.copy(capture = config.voice.capture.copy(silenceTimeoutMs = ms.coerceAtLeast(100))),
    ).save()
}

/** Voz mínima (ms) para dar la frase por válida. */
internal fun ChatViewModel.setMinRecordingMs(ms: Long) {
    config = config.copy(
        voice = config.voice.copy(capture = config.voice.capture.copy(minRecordingMs = ms.coerceAtLeast(0))),
    ).save()
}

/** Tope de la grabación (ms). */
internal fun ChatViewModel.setMaxRecordingMs(ms: Long) {
    config = config.copy(
        voice = config.voice.copy(capture = config.voice.capture.copy(maxRecordingMs = ms.coerceAtLeast(1000))),
    ).save()
}

/** Leer en voz alta las respuestas. */
internal fun ChatViewModel.setSpeakAnswers(on: Boolean) {
    config = config.copy(voice = config.voice.copy(speakAnswers = on)).save()
}

/** Tope de caracteres que se leen en voz alta. */
internal fun ChatViewModel.setMaxSpeakChars(chars: Int) {
    config = config.copy(voice = config.voice.copy(maxSpeakChars = chars.coerceAtLeast(0))).save()
}

/** Espera entre sondeos del estado "ocupado" (ms). */
private const val BUSY_POLL_MS = 120L

/** Espera máxima por respuesta en el chat por voz (ms). */
private const val ANSWER_TIMEOUT_MS = 180_000L
