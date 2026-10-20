package llm

import kotlinx.serialization.Serializable

/**
 * Definicion de una herramienta para el modelo (formato OpenAI):
 * nombre, descripcion y JSON Schema de los argumentos.
 *
 * vive en `llm-api` (no en backend) porque el gateway la serializa en el
 * request `tools[]`; backend depende de llm-api, al reves no.
 *
 * [parametersSchema] es el JSON Schema como texto (ej. `{"type":"object","properties":{}}`);
 * se guarda como String para no acoplar alla a JsonElement en todos los modulos.
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: String = "{\"type\":\"object\",\"properties\":{}}",
)

/** Chunk tipado del streaming del modelo: texto o pedido de herramienta. */
sealed class LlmChunk {
    /** Fragmento de texto normal (lo que se muestra en el chat). */
    data class Text(val delta: String) : LlmChunk()

    /**
     * Llamada a herramienta completa (acumulada: llega emision unica por call,
     * no fragmentada), lista para ejecutar.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        /** Argumentos como JSON crudo (el esquema valida cada Tool). */
        val argsJson: String,
    ) : LlmChunk()
}
