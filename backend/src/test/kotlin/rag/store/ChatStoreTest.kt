package rag.store

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests de la persistencia de chats (F3).
 *
 * Los chats son lo que el usuario no quiere perder: si el guardado o la
 * lectura se rompen, se pierden conversaciones enteras. Por eso la prueba
 * va por el archivo real (en una carpeta temporal), no por mocks.
 */
class ChatStoreTest {

    private fun tempFile(): Path =
        Files.createTempDirectory("chatstore-test-").resolve("chats.json")

    @Test
    fun `un chat guardado se vuelve a leer igual`() {
        val f = tempFile()
        val original = StoredNode(
            id = "chat-1",
            name = "Mi chat",
            createdAt = 1700000000L,
            messages = listOf(
                StoredMessage("user", "hola"),
                StoredMessage("assistant", "buenas"),
            ),
        )
        ChatStore.save(listOf(original), f)

        val leido = ChatStore.load(f)
        assertEquals(1, leido.size)
        assertEquals(original, leido[0])
    }

    @Test
    fun `un archivo que no existe devuelve una lista vacia`() {
        val f = Files.createTempDirectory("chatstore-test-").resolve("no-existe.json")
        assertTrue(ChatStore.load(f).isEmpty())
    }

    @Test
    fun `un archivo corrupto devuelve una lista vacia`() {
        val f = tempFile()
        Files.writeString(f, "{esto no es json")
        assertTrue(ChatStore.load(f).isEmpty(), "un archivo roto no puede romper la app")
    }

    @Test
    fun `las carpetas con hijos se guardan en el arbol`() {
        val f = tempFile()
        val arbol = listOf(
            StoredNode(
                id = "carpeta",
                folder = true,
                name = "Trabajo",
                children = listOf(
                    StoredNode(id = "chat-a", name = "Chat A"),
                    StoredNode(
                        id = "subcarpeta",
                        folder = true,
                        name = "Sub",
                        children = listOf(StoredNode(id = "chat-b", name = "Chat B")),
                    ),
                ),
            ),
        )
        ChatStore.save(arbol, f)
        val leido = ChatStore.load(f)
        assertEquals(1, leido.size)
        assertTrue(leido[0].folder)
        assertEquals(2, leido[0].children.size)
        assertEquals(1, leido[0].children[1].children.size)
        assertEquals("Chat B", leido[0].children[1].children[0].name)
    }

    @Test
    fun `los permisos concedidos se guardan con el chat`() {
        val f = tempFile()
        ChatStore.save(listOf(StoredNode(id = "c1", grantedCats = listOf("FILES", "WEB"))), f)
        val leido = ChatStore.load(f)
        assertEquals(listOf("FILES", "WEB"), leido[0].grantedCats)
    }

    @Test
    fun `los adjuntos y las tarjetas de tool se guardan`() {
        val f = tempFile()
        val mensaje = StoredMessage(
            role = "assistant",
            text = "listo",
            toolCards = listOf(StoredToolCall("create_file", "{\"name\":\"a.py\"}", true, "creado")),
            attachments = listOf(StoredAttachment("a.py", "/tmp/a.py", 12, "text/x-python")),
        )
        ChatStore.save(listOf(StoredNode(id = "c1", messages = listOf(mensaje))), f)
        val leido = ChatStore.load(f)
        assertEquals(1, leido[0].messages[0].toolCards.size)
        assertEquals("create_file", leido[0].messages[0].toolCards[0].name)
        assertEquals(1, leido[0].messages[0].attachments.size)
        assertEquals("a.py", leido[0].messages[0].attachments[0].name)
    }

    @Test
    fun `la verificacion de citas se guarda con el mensaje`() {
        val f = tempFile()
        val mensaje = StoredMessage(
            role = "assistant",
            text = "El motor usa Vulkan [1].",
            citation = StoredCitation(verified = 1, unsupported = 0, unknown = 0),
        )
        ChatStore.save(listOf(StoredNode(id = "c1", messages = listOf(mensaje))), f)
        val leido = ChatStore.load(f)
        assertEquals(1, leido[0].messages[0].citation?.verified)
        assertEquals(0, leido[0].messages[0].citation?.unsupported)
    }

    @Test
    fun `un mensaje viejo sin verificacion de citas sigue leyendose`() {
        val f = tempFile()
        Files.writeString(f, """[{"id":"c1","messages":[{"role":"assistant","text":"hola"}]}]""")
        val leido = ChatStore.load(f)
        assertEquals(1, leido[0].messages.size)
        assertEquals(null, leido[0].messages[0].citation)
    }

    @Test
    fun `el formato viejo (lista plana) se migra solo`() {
        val f = tempFile()
        Files.writeString(
            f,
            """[{"id":"chat-1","title":"Chat viejo","createdAt":123,"messages":[{"role":"user","text":"hola"}]}]""",
        )
        val leido = ChatStore.load(f)
        assertEquals(1, leido.size)
        assertEquals("Chat viejo", leido[0].name)
        assertEquals(1, leido[0].messages.size)
        assertEquals("hola", leido[0].messages[0].text)
    }

    @Test
    fun `un chat viejo sin titulo recibe un nombre por defecto`() {
        val f = tempFile()
        Files.writeString(f, """[{"id":"chat-1","title":"","messages":[]}]""")
        val leido = ChatStore.load(f)
        assertEquals("Chat nuevo", leido[0].name)
    }

    @Test
    fun `guardar crea la carpeta si no existe`() {
        val dir = Files.createTempDirectory("chatstore-test-")
        val f = dir.resolve("config").resolve("chats.json")
        ChatStore.save(listOf(StoredNode(id = "c1")), f)
        assertTrue(Files.exists(f))
    }

    @Test
    fun `guardar una lista vacia deja el archivo con la lista vacia`() {
        val f = tempFile()
        ChatStore.save(listOf(StoredNode(id = "c1")), f)
        ChatStore.save(emptyList(), f)
        assertTrue(ChatStore.load(f).isEmpty())
    }
}
