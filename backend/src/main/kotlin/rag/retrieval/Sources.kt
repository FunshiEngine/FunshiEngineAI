package rag.retrieval

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import rag.config.GithubSource

/** Descarga el zipball de un repo de GitHub y extrae los documentos de texto. */
class GithubDocsSource(
    private val cfg: GithubSource,
    private val exts: Set<String>,
    private val cacheDir: Path
) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
    private val name = "${cfg.owner}/${cfg.repo}"

    fun load(): List<Doc> {
        Files.createDirectories(cacheDir)
        val zip = cacheDir.resolve("${cfg.owner}__${cfg.repo}.zip")
        try {
            download(zip)
        } catch (e: Exception) {
            if (!Files.exists(zip)) throw e // sin red pero con caché => seguimos con la caché
        }
        return readZip(zip)
    }

    private fun download(target: Path) {
        val ref = if (cfg.ref.isBlank()) "" else "/${cfg.ref}"
        val token = cfg.token.ifBlank { System.getenv("GITHUB_TOKEN") ?: "" }
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/${cfg.owner}/${cfg.repo}/zipball$ref"))
            .header("User-Agent", "docs-chatbot")
            .header("Accept", "application/vnd.github+json")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp))
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tmp)
            error("GitHub respondió HTTP ${resp.statusCode()} para $name")
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readZip(zip: Path): List<Doc> {
        val docs = mutableListOf<Doc>()
        ZipInputStream(Files.newInputStream(zip)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val rel = e.name.substringAfter('/') // quita "owner-repo-sha/"
                    val ext = rel.substringAfterLast('.', "").lowercase()
                    val inPaths = cfg.paths.isEmpty() || cfg.paths.any { rel.startsWith(it.trimStart('/')) }
                    if (ext in exts && inPaths && !rel.contains("node_modules/")) {
                        val bytes = zin.readBytes()
                        if (bytes.size < 1_000_000) docs += Doc(name, rel, String(bytes, UTF_8))
                    }
                }
                e = zin.nextEntry
            }
        }
        return docs
    }
}

/** Lee documentos de una carpeta local (documentación interna o del motor). */
class LocalDocsSource(
    private val dir: Path,
    private val exts: Set<String>,
    /** Etiqueta de citación en el chat (ej. "interno/internal-docs", "motor/gemini"). */
    private val label: String = "interno/${dir.fileName}",
) {
    fun load(): List<Doc> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir).use { s ->
            s.filter { Files.isRegularFile(it) && it.extension.lowercase() in exts && Files.size(it) < 2_000_000 }
                .map { Doc(label, dir.relativize(it).toString().replace('\\', '/'), String(Files.readAllBytes(it), UTF_8)) }
                .toList()
        }
    }
}
