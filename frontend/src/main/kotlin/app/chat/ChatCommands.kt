package app.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import llm.api.OpenAiCompatibleGateway
import llm.config.LlmMode
import app.core.UiMessage

/**
 * Comandos de chat (los que empiezan con `/`).
 *
 * Viven fuera del [ChatViewModel] para que ese archivo no crezca: son
 * funciones de extensión sobre el ViewModel, así que cada comando queda
 * aislado en su bloque y el modelo sigue siendo el dueño del estado.
 *
 * Ningún comando toca el modelo: responden al instante en el chat.
 */
internal fun ChatViewModel.handleCommand(raw: String) {
    messages = messages + UiMessage("user", raw) + UiMessage("assistant", "")
    val parts = raw.removePrefix("/").split(Regex("\\s+"))
    val cmd = parts.firstOrNull().orEmpty().lowercase()
    val arg = parts.getOrNull(1)?.lowercase().orEmpty()
    val reply = when (cmd) {
        "help" -> cmdHelp()
        "model" -> cmdModel(arg)
        "thinking" -> cmdThinking(arg)
        "power" -> cmdPower(arg)
        "tools" -> cmdTools()
        else -> "Comando no reconocido: `$raw`.\n\n" + cmdHelp()
    }
    updateLast(reply)
    persistActiveChat()
}

/** /help — lista de comandos disponibles y qué hace cada uno. */
internal fun ChatViewModel.cmdHelp(): String = """
    Comandos disponibles (se ejecutan al instante, sin tocar el modelo):

    · `/help` — muestra esta ayuda.
    · `/model` — lista los modelos del servicio activo.
    · `/model <id>` — cambia el modelo activo al indicado.
    · `/thinking [off|low|medium|high]` — nivel de razonamiento del modelo.
    · `/power [low|medium|high]` — potencia de la respuesta (tokens de salida).
    · `/tools` — lista las herramientas que el modelo puede usar.

    Mientras el modelo responde podés seguir escribiendo: los mensajes
    se encolan y se envían juntos, uno debajo del otro, al terminar.
""".trimIndent()

/** /model — lista modelos del servicio activo (o cambia con `/model <id>`). */
internal fun ChatViewModel.cmdModel(arg: String): String {
    val c = config.llm
    if (c.mode != LlmMode.REMOTE) {
        return "El modo actual es LOCAL. Los modelos se cambian en la pestaña Modelos. " +
            "El activo es `${c.hfFile}`."
    }
    if (c.remoteBaseUrl.isBlank()) {
        return "No hay una API remota activa. Configurala en la pestaña Motor."
    }
    if (arg.isNotEmpty()) {
        // Cambiar modelo: persiste y reinicia el gateway al instante.
        val presets = c.apiPresets.map { if (it.id == c.activeApiId) it.copy(model = arg) else it }
        config = config.copy(llm = c.copy(modelName = arg, apiPresets = presets)).save()
        refreshLists()
        restartGateway()
        return "Modelo cambiado a `$arg`."
    }
    // Listar modelos del endpoint activo.
    scope.launch {
        try {
            val names = withContext(Dispatchers.IO) {
                val gw = OpenAiCompatibleGateway(
                    c.remoteBaseUrl, c.apiKey, c.modelName, c.temperature, c.maxTokens,
                    20_000, c.thinking, c.power,
                )
                try { withTimeout(20_000) { gw.listModels() } } finally { gw.close() }
            }
            val list = names.take(40).joinToString("\n") { "· $it" }
            updateLast(
                "Modelo activo: `${c.modelName}`\n\n" +
                    "Modelos disponibles en ${c.remoteBaseUrl} (${names.size}):\n$list" +
                    if (names.size > 40) "\n…y ${names.size - 40} más." else "",
            )
        } catch (e: Exception) {
            updateLast("No pude listar los modelos: ${friendly(e)}")
        }
    }
    return "Consultando los modelos disponibles…"
}

/** /thinking [off|low|medium|high] — nivel de razonamiento. */
internal fun ChatViewModel.cmdThinking(arg: String): String {
    if (arg.isEmpty()) {
        return "Nivel de thinking actual: `${thinking}`.\n" +
            "Cambiá con `/thinking off`, `/thinking low`, `/thinking medium` o `/thinking high`."
    }
    if (arg !in ChatViewModel.THINKING_LEVELS) {
        return "Nivel inválido: `$arg`. Valores válidos: ${ChatViewModel.THINKING_LEVELS.joinToString(", ")}."
    }
    changeThinking(arg)
    return if (arg == "off") "Thinking desactivado."
    else "Thinking activado en nivel `$arg`."
}

/** /power [low|medium|high] — potencia de la respuesta. */
internal fun ChatViewModel.cmdPower(arg: String): String {
    if (arg.isEmpty()) {
        return "Potencia actual: `$power`.\n" +
            "Cambiá con `/power low`, `/power medium` o `/power high`."
    }
    if (arg !in ChatViewModel.POWER_LEVELS) {
        return "Nivel inválido: `$arg`. Valores válidos: ${ChatViewModel.POWER_LEVELS.joinToString(", ")}."
    }
    changePower(arg)
    return "Potencia de respuesta ajustada a `$arg`."
}

/** /tools — catálogo de herramientas registradas para el modelo. */
internal fun ChatViewModel.cmdTools(): String {
    if (tools.isEmpty) return "No hay herramientas registradas todavía."
    return "Herramientas que puede usar el modelo (el ${if (config.llm.mode == LlmMode.REMOTE) "protocolo OpenAI nativo" else "protocolo por prompt"}):\n\n" + tools.describe()
}
