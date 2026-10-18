package rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Carga las fuentes (GitHub + carpetas internas + docs del motor), las trocea e indexa. */
class KnowledgeBase(private val cfg: RagConfig) {

    @Volatile
    private var index = Bm25Index(emptyList())

    /**
     * Carpeta con la documentación del motor activo (API o modelo local):
     * `app-home/api-docs/<id-motor>`. Se setea antes de cada [sync]; null =
     * el motor no tiene docs propias (sólo se indexan las globales).
     */
    @Volatile
    var motorDocsDir: java.nio.file.Path? = null

    val chunkCount get() = index.size

    suspend fun sync(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        val exts = cfg.extensions.map { it.lowercase() }.toSet()
        val cache = AppPaths.resolve(cfg.cacheDir)
        val docs = mutableListOf<Doc>()
        val warnings = mutableListOf<String>()

        for (g in cfg.githubSources) {
            onStatus("Descargando ${g.owner}/${g.repo}…")
            runCatching { GithubDocsSource(g, exts, cache).load() }
                .onSuccess { docs += it }
                .onFailure { warnings += "${g.owner}/${g.repo}: ${it.message}" }
        }
        for (d in cfg.internalDocsDirs) {
            onStatus("Leyendo $d…")
            docs += LocalDocsSource(AppPaths.resolve(d), exts).load()
        }
        // Docs del motor activo (cada API/modelo local tiene su carpeta propia).
        motorDocsDir?.let { dir ->
            onStatus("Leyendo documentación del motor (${dir.fileName})…")
            docs += LocalDocsSource(dir, exts, label = "motor/${dir.fileName}").load()
        }

        onStatus("Indexando ${docs.size} documentos…")
        val chunks = docs.flatMap { Chunker.split(it, cfg.chunkSize, cfg.chunkOverlap) }
        index = Bm25Index(chunks)
        val warn = if (warnings.isEmpty()) "" else " · ⚠ ${warnings.joinToString("; ")}"
        onStatus("Índice listo: ${docs.size} docs / ${chunks.size} fragmentos$warn")
    }

    fun retrieve(query: String, k: Int): List<Chunk> = index.search(query, k).map { it.first }
}
