package rag.mcp

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Servidor MCP de mentira que corre como **proceso real**.
 *
 * Habla JSON-RPC por stdin/stdout como un servidor MCP de verdad, así se
 * puede probar el transporte completo: lanzar el proceso, el handshake, las
 * herramientas y el cierre. No usa red ni archivos.
 *
 * Se lanza con la misma JVM del test (`java -cp <classpath> rag.mcp.FakeMcpServer`),
 * así que no hace falta ningún intérprete extra en la máquina.
 */
object FakeMcpServer {

    @JvmStatic
    fun main(args: Array<String>) {
        val entrada = System.`in`.bufferedReader()
        val salida = System.out.bufferedWriter()

        // Un log por stderr: el cliente tiene que leerlo aparte (si no, el
        // proceso se bloquea al escribir mucho) y no lo puede confundir con
        // un mensaje del protocolo.
        System.err.println("servidor MCP de prueba: arrancado")

        while (true) {
            val linea = entrada.readLine() ?: break
            val mensaje = McpProtocol.parse(linea) ?: continue
            val method = McpProtocol.methodOf(mensaje) ?: continue
            val id = McpProtocol.idOf(mensaje) ?: continue
            val respuesta = when (method) {
                "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18",""" +
                    """"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"servidor-real","version":"1.0"}}}"""
                "tools/list" -> """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"saludar",""" +
                    """"description":"Saluda a alguien","inputSchema":{"type":"object",""" +
                    """"properties":{"nombre":{"type":"string"}}}}]}}"""
                "tools/call" -> {
                    val params = mensaje["params"] as? JsonObject
                    val args = params?.get("arguments") as? JsonObject
                    val quien = (args?.get("nombre") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "mundo"
                    """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text",""" +
                        """"text":"hola, $quien"}],"isError":false}}"""
                }
                else -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"no existe"}}"""
            }
            salida.write(respuesta)
            salida.write("\n")
            salida.flush()
        }
    }

    /** Comando (y argumentos) para lanzar este servidor con la JVM actual. */
    fun command(): Pair<String, List<String>> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val exe = if (os.contains("win")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", exe).toString()
        return java to listOf("-cp", System.getProperty("java.class.path"), "rag.mcp.FakeMcpServer")
    }
}

/** Declaración de un servidor MCP que apunta al proceso de prueba. */
internal fun fakeServerConfig(id: String = "real"): McpServerConfig {
    val (comando, args) = FakeMcpServer.command()
    return McpServerConfig(
        id = id,
        name = "Servidor real",
        transport = "stdio",
        command = comando,
        args = args,
    )
}

/** Argumentos de ejemplo para llamar a la herramienta de prueba. */
internal fun fakeToolArgs(nombre: String): JsonObject = buildJsonObject { put("nombre", nombre) }
