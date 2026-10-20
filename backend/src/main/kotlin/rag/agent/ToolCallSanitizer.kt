package rag.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Sanitizador de tool calls: las llamadas del modelo vienen crudas y suelen
 * traer artefactos (fences extra, texto alrededor del objeto, smart quotes,
 * comas colgantes, JSON cortado). Sigue la idea del proyecto de referencia
 * (StreamingArrayParser): tolerancia primero, pero acá el objeto es JSON y el
 * fallo no se descarta en silencio: [Invalid.reason] vuelve al modelo como
 * feedback para que lo reintente (regla del bucle de predicción: nunca
 * propagar errores transitorios, consumir un intento y seguir).
 *
 * Puro: sin I/O, sin estado global. Todos los pasos intentan parsear y se
 * cortan al primer resultado válido.
 */
sealed class SanitizedToolCall {
    /** Llamada reparada y lista para ejecutar. */
    data class Valid(val name: String, val args: JsonObject, val id: String) : SanitizedToolCall()

    /** Falla tras todos los intentos; [reason] es lo que el modelo verá. */
    data class Invalid(val reason: String) : SanitizedToolCall()
}

object ToolCallSanitizer {

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Contenido crudo de un bloque ```tool_call ... ``` (lo que extrajo el
     * parser de bloques): quita fences sobrantes, recorta al objeto externo,
     * repara y valida la forma {"tool"|"name", "arguments"}.
     */
    fun block(raw: String): SanitizedToolCall {
        val cleaned = stripWrappers(raw)
        val slide = cutToOuterBraces(cleaned)
            ?: return SanitizedToolCall.Invalid(
                "No se encontró un objeto JSON (no hay llaves { } en el bloque).",
            )
        return parseAndValidate(slide)
    }

    /**
     * Argumentos crudos de una llamada NATIVA (`tool_calls` del protocolo
     * OpenAI): puede venir válida, envuelta en {"arguments":...} o rota.
     */
    fun args(name: String, argsJson: String): SanitizedToolCall {
        val slide = cutToOuterBraces(argsJson.ifBlank { "{}" })
            ?: return SanitizedToolCall.Invalid(
                "Los argumentos no contienen un objeto JSON (p.ej. fragmento truncado).",
            )
        return when (val outcome = parseAndValidate(slide, expectedName = name)) {
            is SanitizedToolCall.Valid -> outcome
            is SanitizedToolCall.Invalid -> {
                // {"arguments":{...}} con el tool fuera del JSON: reparación extra.
                val onlyArgs = try { lenient.parseToJsonElement(slide) as? JsonObject } catch (_: Exception) { null }
                if (onlyArgs != null && onlyArgs["arguments"] is JsonObject) {
                    SanitizedToolCall.Valid(name, onlyArgs["arguments"] as JsonObject, name)
                } else {
                    outcome
                }
            }
        }
    }

    /**
     * Parsea + valida + repara en pasos: 1) JSON directo, 2) con reparaciones
     * agresivas (quotes/commas), 3) si la forma es válida pero el nombre está
     * fuera, devuelto los args como objeto. Devuelve el motivo del fallo
     * legible si ninguna alcanza.
     */
    private fun parseAndValidate(raw: String, expectedName: String? = null): SanitizedToolCall {
        // Paso 1: tal cual.
        val direct = try { lenient.parseToJsonElement(raw) } catch (_: Exception) { null }
        if (direct is JsonObject) {
            val shaped = validateShape(direct, expectedName)
            if (shaped is SanitizedToolCall.Valid) return shaped
            // El JSON parsea pero la FORMA no es válida. Suele pasar con las
            // comillas curvas: en modo lenient el parser las acepta como parte
            // de la clave (`“tool”` en vez de `tool`), así que parsea "bien" y
            // la forma queda rota. Por eso la reparación se prueba también
            // después de un parseo exitoso.
            val repaired = repairJson(raw)
            if (repaired != raw) {
                val parsed = try { lenient.parseToJsonElement(repaired) } catch (_: Exception) { null }
                if (parsed is JsonObject) {
                    val reshaped = validateShape(parsed, expectedName)
                    if (reshaped is SanitizedToolCall.Valid) return reshaped
                }
            }
            return shaped
        }

        // Paso 2: reparaciones comunes de modelos pequeños.
        val repaired = repairJson(raw)
        val parsed = try { lenient.parseToJsonElement(repaired) } catch (_: Exception) { null }
        if (parsed is JsonObject) return validateShape(parsed, expectedName)

        return SanitizedToolCall.Invalid(
            "El JSON es inválido incluso tras repararlo (quotes/comas). Ejemplo válido: " +
                "{\"tool\":\"nombre\",\"arguments\":{...}}",
        )
    }

    /** Valida la forma mínima: objeto, nombre en "tool"/"name", args en "arguments"/"args". */
    private fun validateShape(obj: JsonObject, expectedName: String? = null): SanitizedToolCall {
        val nameEl = (obj["tool"] ?: obj["name"]) as? JsonPrimitive
        val name = (nameEl?.contentOrNull ?: expectedName).orEmpty().trim()
        if (name.isBlank()) {
            if (expectedName.isNullOrBlank()) {
                return SanitizedToolCall.Invalid(
                    "Falta el campo \"tool\" con el nombre de la herramienta.",
                )
            }
            // Sin campo name pero con expectedName: es {"arguments": {...}} puro.
            val onlyArgs = obj["arguments"] as? JsonObject
            if (onlyArgs != null) return SanitizedToolCall.Valid(expectedName, onlyArgs, expectedName)
            return SanitizedToolCall.Valid(
                expectedName,
                argsFromLeftovers(obj),
                expectedName,
            )
        }
        val args = obj["arguments"] as? JsonObject
            ?: obj["args"] as? JsonObject
        return SanitizedToolCall.Valid(name, args ?: argsFromLeftovers(obj), name)
    }

    /** Args = objeto original sin las claves reservadas (formato plano del modelo). */
    private fun argsFromLeftovers(obj: JsonObject): JsonObject = JsonObject(
        obj.entries
            .filterNot { it.key in setOf("tool", "name", "arguments", "args") }
            .associate { it.key to it.value },
    )

    /** Quita fences envolventes (```tool_call/json/text) que el modelo re-agrega. */
    private fun stripWrappers(raw: String): String = raw
        .replace(Regex("(?s)^\\s*```+[a-zA-Z_]*\\s*"), "")
        .replace(Regex("(?s)```+\\s*$"), "")
        .trim()

    /** Substring del primer '{' al último '}' (el modelo suele narrar alrededor). */
    private fun cutToOuterBraces(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start < 0 || end <= start || end > raw.length) null else raw.substring(start, end + 1)
    }

    /** Reparaciones seguras y habituales; cada paso se intenta en orden. */
    private fun repairJson(raw: String): String = raw
        .replace("\uFEFF", "")
        .replace('\u201C', '"')
        .replace('\u201D', '"')
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace(Regex(",\\s*([}\\]])"), "$1") // comas colgantes -> nada
        .replace("NaN", "0")
        .replace(": Infinite", ":0")
        .trim()
}
