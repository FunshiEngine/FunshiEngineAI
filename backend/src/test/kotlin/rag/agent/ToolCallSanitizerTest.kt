package rag.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del sanitizador de tool calls (F1).
 *
 * El modelo devuelve JSON "casi válido": con fences de más, texto alrededor,
 * comas colgantes, comillas curvas o cortado a la mitad. El sanitizador es lo
 * que decide si la llamada se ejecuta o si vuelve al modelo como feedback.
 */
class ToolCallSanitizerTest {

    private fun valid(call: SanitizedToolCall): SanitizedToolCall.Valid {
        assertTrue(call is SanitizedToolCall.Valid, "se esperaba una llamada válida y llegó: $call")
        return call as SanitizedToolCall.Valid
    }

    @Test
    fun `json limpio pasa tal cual`() {
        val call = valid(ToolCallSanitizer.block("""{"tool":"get_time","arguments":{}}"""))
        assertEquals("get_time", call.name)
        assertEquals(0, call.args.size)
    }

    @Test
    fun `quita los fences que el modelo re-agrega`() {
        val call = valid(ToolCallSanitizer.block("```tool_call\n{\"tool\":\"get_time\",\"arguments\":{}}\n```"))
        assertEquals("get_time", call.name)
    }

    @Test
    fun `ignora el texto que el modelo narra alrededor del objeto`() {
        val call = valid(
            ToolCallSanitizer.block("Claro, uso la herramienta:\n{\"tool\":\"get_time\",\"arguments\":{}}\nListo."),
        )
        assertEquals("get_time", call.name)
    }

    @Test
    fun `repara comas colgantes`() {
        val call = valid(ToolCallSanitizer.block("""{"tool":"get_time","arguments":{"tz":"UTC",}}"""))
        assertEquals("UTC", call.args["tz"]?.toString()?.trim('"'))
    }

    @Test
    fun `repara comillas curvas`() {
        val call = valid(ToolCallSanitizer.block("{\u201Ctool\u201D:\u201Cget_time\u201D,\u201Carguments\u201D:{}}"))
        assertEquals("get_time", call.name)
    }

    @Test
    fun `acepta el campo name en vez de tool`() {
        val call = valid(ToolCallSanitizer.block("""{"name":"get_time","arguments":{}}"""))
        assertEquals("get_time", call.name)
    }

    @Test
    fun `acepta el campo args en vez de arguments`() {
        val call = valid(ToolCallSanitizer.block("""{"tool":"get_time","args":{"tz":"UTC"}}"""))
        assertEquals("UTC", call.args["tz"]?.toString()?.trim('"'))
    }

    @Test
    fun `los argumentos sueltos se toman como argumentos`() {
        // Phi-4-mini a veces aplana: {"tool":"create_file","path":"a.txt","content":"x"}
        val call = valid(ToolCallSanitizer.block("""{"tool":"create_file","path":"a.txt","content":"x"}"""))
        assertEquals("create_file", call.name)
        assertEquals("\"a.txt\"", call.args["path"].toString())
        assertEquals("\"x\"", call.args["content"].toString())
    }

    @Test
    fun `sin objeto JSON devuelve un motivo legible`() {
        val call = ToolCallSanitizer.block("no hay json aqui")
        assertTrue(call is SanitizedToolCall.Invalid)
        val reason = (call as SanitizedToolCall.Invalid).reason
        assertTrue(reason.isNotBlank(), "el motivo no puede estar vacío: el modelo lo lee")
    }

    @Test
    fun `el motivo explica que falta el nombre`() {
        val call = ToolCallSanitizer.block("""{"arguments":{"x":1}}""")
        assertTrue(call is SanitizedToolCall.Invalid)
        assertTrue("tool" in (call as SanitizedToolCall.Invalid).reason)
    }

    @Test
    fun `args nativas envueltas en arguments se desenvuelven`() {
        val call = valid(ToolCallSanitizer.args("get_time", """{"arguments":{"tz":"UTC"}}"""))
        assertEquals("get_time", call.name)
        assertEquals("UTC", call.args["tz"]?.toString()?.trim('"'))
    }

    @Test
    fun `args nativas con el nombre esperado completan el nombre`() {
        val call = valid(ToolCallSanitizer.args("get_time", """{"tz":"UTC"}"""))
        assertEquals("get_time", call.name)
        assertEquals("UTC", call.args["tz"]?.toString()?.trim('"'))
    }

    @Test
    fun `args nativas vacias son un objeto vacio`() {
        val call = valid(ToolCallSanitizer.args("get_time", ""))
        assertEquals("get_time", call.name)
        assertEquals(0, call.args.size)
    }

    @Test
    fun `args nativas rotas devuelven un motivo`() {
        val call = ToolCallSanitizer.args("get_time", """{"tz": """)
        assertTrue(call is SanitizedToolCall.Invalid)
    }
}
