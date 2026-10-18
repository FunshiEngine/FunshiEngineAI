package rag

/** Parte documentos por encabezados Markdown y luego por tamaño, con solapamiento. */
object Chunker {
    fun split(doc: Doc, size: Int, overlap: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var heading = doc.path.substringAfterLast('/')
        val buf = StringBuilder()

        fun flush() {
            val text = buf.toString().trim()
            buf.clear()
            if (text.isBlank()) return
            var start = 0
            while (start < text.length) {
                var end = minOf(start + size, text.length)
                if (end < text.length) {
                    val nl = text.lastIndexOf('\n', end)
                    if (nl > start + size / 2) end = nl
                }
                val piece = text.substring(start, end).trim()
                if (piece.isNotEmpty()) out += Chunk(doc.source, doc.path, heading, piece)
                if (end >= text.length) break
                start = maxOf(end - overlap, start + 1)
            }
        }

        var inFence = false
        for (line in doc.text.lineSequence()) {
            if (line.trimStart().startsWith("```")) inFence = !inFence
            if (!inFence && line.startsWith("#")) {
                flush()
                heading = line.trimStart('#').trim().ifEmpty { heading }
            }
            buf.appendLine(line)
        }
        flush()
        return out
    }
}
