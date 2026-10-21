package app.core

/**
 * Qué modelos maneja un catálogo: los de **texto** (LLM, `.gguf`) o los de
 * **voz** (los `.bin` de whisper.cpp, para el dictado local).
 *
 * Los dos catálogos son independientes: activar un modelo de voz no puede
 * cambiar el motor de inferencia (ni al revés).
 */
enum class ModelCatalogKind { TEXT, VOICE }

/**
 * ¿Este repo de Hugging Face parece de **voz** (dictado) y no de texto?
 *
 * Se usa para etiquetar los resultados del buscador, para que el usuario no
 * confunda un modelo de transcripción con uno de chat.
 */
fun looksLikeVoiceRepo(repoId: String): Boolean {
    val id = repoId.lowercase()
    return listOf("whisper", "tts", "speech", "asr", "wav2vec", "parakeet", "whisper.cpp")
        .any { id.contains(it) }
}

/**
 * ¿Este **nombre de modelo** parece de voz (transcripción) y no de chat?
 *
 * Se usa al configurar una API: un modelo como `whisper-1` no sirve para
 * chatear, y avisarlo al configurarlo es mejor que fallar en runtime.
 */
fun looksLikeVoiceModelName(name: String): Boolean {
    val n = name.lowercase()
    return listOf("whisper", "tts", "speech", "asr", "wav2vec", "parakeet")
        .any { n.contains(it) }
}
