package rag

data class Doc(val source: String, val path: String, val text: String)

data class Chunk(val source: String, val path: String, val heading: String, val text: String) {
    val label get() = "$source: $path"
}
