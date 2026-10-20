package rag.retrieval

import java.text.Normalizer
import kotlin.math.ln

/** Índice BM25 en memoria. Sin dependencias ni modelo de embeddings. */
class Bm25Index(private val chunks: List<Chunk>) {
    private val k1 = 1.5
    private val b = 0.75

    private val tfs: List<Map<String, Int>> =
        chunks.map { c -> tokenize("${c.heading} ${c.path} ${c.text}").groupingBy { it }.eachCount() }
    private val lens = tfs.map { it.values.sum() }
    private val avgLen = if (lens.isEmpty()) 1.0 else lens.average().coerceAtLeast(1.0)
    private val df: Map<String, Int> = HashMap<String, Int>().also { m ->
        tfs.forEach { tf -> tf.keys.forEach { m.merge(it, 1, Int::plus) } }
    }

    val size get() = chunks.size

    fun search(query: String, k: Int): List<Pair<Chunk, Double>> {
        if (chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val scores = DoubleArray(chunks.size)
        for (term in tokenize(query).distinct()) {
            val d = df[term] ?: continue
            val idf = ln(1 + (n - d + 0.5) / (d + 0.5))
            for (i in chunks.indices) {
                val f = tfs[i][term] ?: continue
                scores[i] += idf * f * (k1 + 1) / (f + k1 * (1 - b + b * lens[i] / avgLen))
            }
        }
        return scores.withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(k)
            .map { chunks[it.index] to it.value }
    }

    companion object {
        private val CAMEL = Regex("([a-z0-9])([A-Z])")
        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val STOP = setOf(
            "el", "la", "los", "las", "de", "del", "un", "una", "y", "o", "en", "que", "por", "para", "con", "es", "se",
            "como", "cual", "the", "an", "and", "or", "of", "to", "in", "is", "for", "on", "with", "how", "what"
        )

        fun tokenize(text: String): List<String> {
            val spaced = text.replace(CAMEL, "$1 $2").lowercase()
            val norm = Normalizer.normalize(spaced, Normalizer.Form.NFD).replace(MARKS, "")
            return norm.split(NON_ALNUM).filter { it.length > 1 && it !in STOP }
        }
    }
}
