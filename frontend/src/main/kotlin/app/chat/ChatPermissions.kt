package app.chat

import app.core.ChatNode
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import rag.agent.PermissionDecision
import rag.agent.PermissionRequest
import rag.agent.ToolCategory
import rag.config.AppPaths

/**
 * Permisos de herramientas (F3).
 *
 * Reglas del diseño:
 *  - El permiso es **por categoría** (`FILES`, `WEB`, `MCP`), no por
 *    herramienta: conceder FILES habilita `create_file` y `download_url`.
 *  - Está **apagado por defecto**: sin permiso concedido, cada ejecución
 *    abre el diálogo "¿Permitir?" con tres respuestas (una vez / siempre /
 *    cancelar).
 *  - Se concede **por chat**: el permiso vive en el nodo del chat y se
 *    persiste en `config/chats.json` (`grantedCats`).
 *
 * Son funciones de extensión sobre el ViewModel, que sigue siendo el dueño
 * del estado (mismo patrón que el resto de áreas).
 */

/** Carpeta de salidas de un chat: `app-home/outputs/<chat-id>/`. */
internal fun ChatViewModel.outputsDirFor(chatId: String): Path =
    AppPaths.resolve("outputs/" + chatId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

/** Categorías concedidas en un chat (vacío = todo apagado). */
internal fun ChatViewModel.grantedCategories(chatId: String): Set<ToolCategory> =
    (tree.nodeOf(chatId) as? ChatNode.Chat)?.grantedCats ?: emptySet()

/** Concede una categoría en un chat y lo persiste. */
internal fun ChatViewModel.grantCategory(chatId: String, category: ToolCategory) {
    val chat = tree.nodeOf(chatId) as? ChatNode.Chat ?: return
    chat.grantedCats = chat.grantedCats + category
    persistTree()
}

/** Revoca una categoría de un chat y lo persiste. */
internal fun ChatViewModel.revokeCategory(chatId: String, category: ToolCategory) {
    val chat = tree.nodeOf(chatId) as? ChatNode.Chat ?: return
    chat.grantedCats = chat.grantedCats - category
    persistTree()
}

/** Texto con las categorías concedidas en el chat activo (para la cabecera). */
internal fun ChatViewModel.grantedCategoriesLabel(): String {
    val cats = grantedCategories(activeChatId).sortedBy { it.ordinal }
    return if (cats.isEmpty()) "ninguno" else cats.joinToString(", ") { it.label }
}

/**
 * Pide permiso al usuario y espera su respuesta.
 *
 * Se llama desde el bucle de herramientas (hilo de E/S): el diálogo se arma
 * en el hilo de UI y la respuesta se espera con un `CompletableDeferred`.
 * Si se cancela la generación con el diálogo abierto, el diálogo se cierra y
 * la cancelación se propaga (no se traga).
 */
internal suspend fun ChatViewModel.askPermission(req: PermissionRequest): PermissionDecision {
    val deferred = CompletableDeferred<PermissionDecision>()
    withContext(Dispatchers.Main) {
        permissionAnswer = deferred
        permissionRequest = req
    }
    return try {
        deferred.await()
    } finally {
        withContext(NonCancellable + Dispatchers.Main) {
            permissionAnswer = null
            permissionRequest = null
        }
    }
}

/**
 * Responde la petición de permiso pendiente (la llama la UI desde el
 * diálogo). "Permitir siempre" además concede la categoría en el chat.
 */
internal fun ChatViewModel.answerPermission(decision: PermissionDecision) {
    val req = permissionRequest
    val deferred = permissionAnswer
    permissionRequest = null
    permissionAnswer = null
    if (decision == PermissionDecision.ALLOW_ALWAYS && req != null) {
        grantCategory(req.chatId, req.category)
    }
    deferred?.complete(decision)
}
