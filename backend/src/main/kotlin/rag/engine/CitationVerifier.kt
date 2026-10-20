package rag.engine

import java.text.Normalizer
import rag.retrieval.Chunk

/**
 * Verificador determinista de citas (F9).
 *
 * La regla central del proyecto es "no inventar sin referencia": el modelo
 * tiene que responder **sólo** con lo que está en el contexto y citar la
 * fuente (`[1]`, `[2]`…). Con un modelo chico eso es una instrucción blanda:
 * puede parafrasear, exagerar o inventar.
 *
 * Este verificador convierte la instrucción en una comprobación programática:
 * por cada cita `[n]` de la respuesta abre la fuente `n` y mira si el fragmento
 * citado **está respaldado** por ella.
 *
 * Determinista y sin red: es una función pura sobre texto. Eso la hace
 * testeable al 100% y utilizable como comprobación post-generación (o como
 * tool, si el modelo quiere verificar antes de responder).
 *
 * Criterio de "respaldada":
 *  1. **Literal**: el fragmento citado aparece tal cual en la fuente (tras
 *     normalizar: minúsculas, sin acentos, sin puntuación).
 *  2. **Sin detalles inventados**: los *términos clave* del fragmento (nombres
 *     propios y números de dos dígitos o más: "Vulkan", "DirectX 12", "1.0")
 *     tienen que aparecer en la fuente. Es lo que separa una paráfrasis de
 *     una invención: si el modelo escribió "usa DirectX 12" y la fuente dice
 *     "usa Vulkan", la cita no está respaldada aunque las frases se parezcan.
 *  3. **Paráfrasis**: al menos el [MIN_WORD_COVERAGE] de las palabras
 *     significativas del fragmento aparecen en la fuente. Alcanza para una
 *     paráfrasis honesta; no alcanza si el modelo dijo otra cosa.
 *
 * Si ninguna de las tres se cumple, la cita queda **sin respaldo**: no se
 * descarta la respuesta (el usuario decide), pero queda marcada.
 */
object CitationVerifier {

    /** Porcentaje de palabras significativas que deben aparecer en la fuente. */
    const val MIN_WORD_COVERAGE = 0.5

    /** Largo máximo del fragmento que se extrae alrededor de la cita. */
    const val MAX_SNIPPET_CHARS = 400

    /** Marcador de cita: `[1]`, `[12]`… */
    private val MARKER = Regex("""\[(\d{1,3})]""")

    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val NUMBER = Regex("[0-9]+([.,][0-9]+)*")

    /**
     * Palabras funcionales que aparecen capitalizadas al empezar una frase.
     * No son nombres propios: no cuentan como término clave.
     */
    private val CAPITALIZED_COMMON = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "este", "esta", "esto",
        "estos", "estas", "ese", "esa", "eso", "aquel", "aquella", "es", "son", "fue",
        "en", "para", "con", "sin", "sobre", "entre", "desde", "hasta", "hacia", "por",
        "que", "quien", "cual", "cuando", "donde", "como", "si", "y", "o", "u", "pero",
        "se", "su", "sus", "tu", "tus", "mi", "mis", "al", "de", "del", "the", "a",
        "an", "this", "that", "these", "those", "it", "its", "in", "on", "for", "with",
        "without", "to", "of", "and", "or", "but", "if", "is", "are", "was", "were",
        "there", "their", "his", "her", "as", "at", "by", "from", "we", "you", "i",
    )

    /** Palabras vacías: no cuentan para medir el respaldo. */
    private val STOP = setOf(
        "este", "esta", "esto", "estos", "estas", "todo", "toda", "todos", "todas",
        "cada", "como", "cuando", "donde", "porque", "para", "pero", "tambien",
        "sobre", "entre", "desde", "hasta", "hacia", "mediante", "segun", "donde",
        "the", "this", "that", "these", "those", "there", "their", "which", "when",
        "where", "with", "from", "into", "than", "then", "they", "them", "been",
    )

    /**
     * Índices citados en la respuesta, en el orden en que aparecen (con
     * repeticiones: la misma fuente puede citarse dos veces).
     */
    fun citedIndexes(answer: String): List<Int> =
        MARKER.findAll(answer)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toList()

    /**
     * Verifica todas las citas de [answer] contra [sources] (en el mismo orden
     * en que se le pasaron al modelo: la fuente `n` es `sources[n-1]`).
     */
    fun verify(answer: String, sources: List<Chunk>): CitationReport {
        val matches = MARKER.findAll(answer).toList()
        if (matches.isEmpty()) {
            return CitationReport(
                checks = emptyList(),
                hasCitations = false,
                valid = false,
                unknown = emptyList(),
                uncited = sources.indices.map { it + 1 },
            )
        }

        val checks = matches.map { match ->
            val index = match.groupValues[1].toIntOrNull() ?: 0
            val snippet = snippetAround(answer, match.range.first, match.range.last + 1)
            if (index < 1 || index > sources.size) {
                CitationCheck(
                    index = index,
                    label = "",
                    snippet = snippet,
                    supported = false,
                    reason = "No hay una fuente [$index] entre las recuperadas.",
                )
            } else {
                val source = sources[index - 1]
                val verdict = checkSnippet(snippet, source.text)
                CitationCheck(
                    index = index,
                    label = source.label,
                    snippet = snippet,
                    supported = verdict.first,
                    reason = if (verdict.first) "" else verdict.second,
                )
            }
        }

        val unknown = checks.filter { it.index < 1 || it.index > sources.size }.map { it.index }.distinct()
        val cited = checks.map { it.index }.filter { it in 1..sources.size }.distinct()
        val uncited = sources.indices.map { it + 1 }.filter { it !in cited }

        return CitationReport(
            checks = checks,
            hasCitations = true,
            valid = checks.all { it.supported },
            unknown = unknown,
            uncited = uncited,
        )
    }

    /**
     * Verifica un fragmento contra el texto de una fuente.
     * @return `true` a la primera componente si está respaldado; la segunda
     *   explica por qué no lo está.
     */
    private fun checkSnippet(snippet: String, sourceText: String): Pair<Boolean, String> {
        val normSnippet = normalize(snippet)
        if (normSnippet.isBlank()) return true to ""

        val normSource = normalize(sourceText)
        if (normSource.isBlank()) {
            return false to "La fuente está vacía: no hay nada que respalde la cita."
        }

        // 1) Literal: el fragmento aparece tal cual.
        if (normSource.contains(normSnippet)) return true to ""

        val sourceWords = normSource.split(' ').toSet()

        // 2) Los detalles concretos tienen que estar en la fuente: un nombre
        //    propio o un número que no aparece es un detalle inventado.
        val inventados = keyTerms(snippet).filter { !normSource.contains(it.text) }
        if (inventados.isNotEmpty()) {
            val lista = inventados.joinToString(", ") { "\"${it.raw}\"" }
            return false to "La cita menciona $lista, que no aparece en la fuente."
        }

        // 3) Paráfrasis: suficientes palabras significativas en común.
        val words = significantWords(normSnippet)
        if (words.isEmpty()) return true to ""
        val covered = words.count { coveredBy(it, sourceWords) }
        val coverage = covered.toDouble() / words.size
        if (coverage >= MIN_WORD_COVERAGE) return true to ""

        val percent = (coverage * 100).toInt()
        return false to "El fragmento no aparece en la fuente (sólo el $percent% de sus palabras)."
    }

    /**
     * ¿La palabra [word] de la respuesta aparece en la fuente?
     *
     * Cuenta la coincidencia exacta y también la de raíz común: "renderiza" y
     * "renderizar" son la misma palabra escrita de otra forma (el modelo
     * parafrasea). Se exige un prefijo de 5 letras y una diferencia de largo
     * de 3 o menos, para no confundir palabras distintas.
     */
    private fun coveredBy(word: String, sourceWords: Set<String>): Boolean {
        if (word in sourceWords) return true
        return sourceWords.any { other ->
            val common = commonPrefix(word, other)
            common >= 5 && kotlin.math.abs(word.length - other.length) <= 3
        }
    }

    /** Largo del prefijo común entre dos palabras. */
    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    /**
     * Términos clave del fragmento: los detalles concretos que, si no están
     * en la fuente, son una invención.
     *
     * Son dos clases:
     *  - **Nombres propios**: palabras que empiezan en mayúscula y no son
     *    palabras funcionales ("Vulkan", "OpenGL", "Windows"). La primera
     *    palabra de la frase no cuenta (está capitalizada por el inicio, no
     *    por ser un nombre) ni las funcionales ("El", "También", "The").
     *  - **Números con forma de versión o cantidad**: dos dígitos o más, o con
     *    punto decimal ("12", "1.0", "2024"). Un dígito solo ("sección 3")
     *    no cuenta: suele ser una referencia interna.
     */
    internal fun keyTerms(snippet: String): List<KeyTerm> {
        val palabras = snippet
            .split(' ', '\n', '\t', ',', ';', ':', '(', ')', '"', '\'', '¿', '?', '!', '¡')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val out = mutableListOf<KeyTerm>()
        for ((i, limpia) in palabras.withIndex()) {
            if (NUMBER.matches(limpia)) {
                if (limpia.count { it.isDigit() } >= 2 || limpia.contains('.')) {
                    out += KeyTerm(normalize(limpia), limpia, isNumber = true)
                }
                continue
            }
            if (!limpia.first().isUpperCase()) continue
            // La primera palabra de la frase está capitalizada por el inicio:
            // capitalización sola no alcanza para sospechar un nombre propio.
            if (i == 0) continue
            // Se compara ya normalizado: "Sí" y "si" son la misma palabra.
            val norm = normalize(limpia)
            if (norm in CAPITALIZED_COMMON) continue
            out += KeyTerm(norm, limpia, isNumber = false)
        }
        return out.filter { it.text.isNotBlank() }.distinctBy { it.text }
    }

    /** El texto de la oración que contiene al marcador, sin el marcador. */
    private fun snippetAround(text: String, markerStart: Int, markerEnd: Int): String {
        var start = 0
        for (i in markerStart - 1 downTo 0) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') {
                start = i + 1
                break
            }
        }
        var end = text.length
        for (i in markerEnd until text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == ';') {
                end = i
                break
            }
        }
        return text.substring(start, end)
            .replace(MARKER, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_SNIPPET_CHARS)
    }

    /** Normaliza para comparar: minúsculas, sin acentos, sin puntuación. */
    internal fun normalize(text: String): String {
        val spaced = text.replace(CAMEL, "$1 $2").lowercase()
        val norm = Normalizer.normalize(spaced, Normalizer.Form.NFD).replace(MARKS, "")
        return norm.replace(NON_ALNUM, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /** Palabras que cuentan para medir el respaldo (largas y no vacías). */
    internal fun significantWords(normalized: String): List<String> =
        normalized.split(' ')
            .filter { it.length >= 4 && it !in STOP }
            .distinct()
}

/**
 * Detalle concreto citado en una respuesta: un nombre propio o un número.
 * Si no aparece en la fuente, la cita es una invención.
 */
data class KeyTerm(
    /** El término normalizado (para comparar). */
    val text: String,
    /** El término como lo escribió el modelo (para mostrarlo). */
    val raw: String,
    /** ¿Es un número? (los números se buscan como substring, no como palabra). */
    val isNumber: Boolean,
)

/** Verificación de una cita concreta. */
data class CitationCheck(
    /** Índice citado (1 = la primera fuente recuperada). */
    val index: Int,
    /** Etiqueta de la fuente (vacía si el índice no existe). */
    val label: String,
    /** Fragmento de la respuesta que accompanye a la cita. */
    val snippet: String,
    /** ¿La fuente respalda el fragmento? */
    val supported: Boolean,
    /** Por qué no está respaldada (vacío si lo está). */
    val reason: String = "",
)

/** Resultado de verificar todas las citas de una respuesta. */
data class CitationReport(
    /** Verificación de cada cita (una por aparición). */
    val checks: List<CitationCheck>,
    /** ¿La respuesta tiene alguna cita? */
    val hasCitations: Boolean,
    /** ¿Todas las citas existen y están respaldadas? */
    val valid: Boolean,
    /** Índices citados que no corresponden a ninguna fuente. */
    val unknown: List<Int> = emptyList(),
    /** Fuentes recuperadas que el modelo no citó. */
    val uncited: List<Int> = emptyList(),
) {
    /** Citas respaldadas. */
    val verified: Int get() = checks.count { it.supported }

    /** Citas sin respaldo (la fuente existe pero no dice eso). */
    val unsupported: Int get() = checks.count { !it.supported && it.index > 0 }

    /** Citas que apuntan a una fuente inexistente. */
    val unknownCount: Int get() = unknown.size
}
