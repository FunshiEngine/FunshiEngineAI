package rag.agent.tools

import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import llm.api.ToolSpec
import rag.agent.Tool
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolResult

/**
 * Herramientas web (F4): buscar en la web y leer una página.
 *
 * Las dos son de la categoría `WEB`: piden permiso (apagado por defecto, se
 * concede por chat con `/permitir-web`).
 *
 * `web_search` usa un [SearchProvider]; la implementación incluida es una
 * cadena: [DuckDuckGoProvider] como motor principal (no necesita clave de API)
 * y [WikipediaProvider] como respaldo cuando el buscador bloquea al cliente.
 * `web_fetch` trae la página, le saca el HTML y devuelve texto legible.
 */

// ---------------------------------------------------------------------------
// Contrato de búsqueda
// ---------------------------------------------------------------------------

/** Un resultado de búsqueda. [source] dice de qué motor salió (para citarlo). */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "",
)

/**
 * Proveedor de búsqueda. La interfaz está para poder agregar otros motores
 * (Bing, Brave, Searx…) sin tocar las herramientas.
 */
interface SearchProvider {
    /** Busca [query] y devuelve hasta [limit] resultados. */
    suspend fun search(query: String, limit: Int): List<SearchResult>
}

/**
 * Búsqueda en DuckDuckGo sin clave de API.
 *
 * Fuente principal: el HTML de `html.duckduckgo.com`, que trae los resultados
 * reales (título, URL y fragmento).
 *
 * DuckDuckGo no ofrece una API oficial sin clave y su HTML está protegido por
 * un control anti-bot: a veces contesta con una página de verificación en lugar
 * de resultados. Para sortearlo sin hacer trampas:
 *
 *  1. **Preparar** el cliente: antes de la primera búsqueda se visita la
 *     portada (así el servidor ve una sesión normal) y se conserva un cookie
 *     jar en memoria.
 *  2. **Reintentar**: si llega la página de verificación, se espera un poco y
 *     se vuelve a pedir (la verificación no salta siempre).
 *  3. **Respaldar**: si aun así no hay resultados, se consulta la API de
 *     "instant answer" (JSON, mucho más permisiva).
 *
 * Los anuncios del buscador se descartan: no son resultados orgánicos.
 */
class DuckDuckGoProvider(
    private val http: HttpClient = defaultHttpClient(withCookies = true),
) : SearchProvider {

    /** ¿Ya se preparó el cliente? */
    private val primed = java.util.concurrent.atomic.AtomicBoolean(false)

    override suspend fun search(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        prime()

        // Intentos con espera creciente: la verificación es intermitente.
        for (attempt in 1..SEARCH_ATTEMPTS) {
            val res = htmlResults(q)
            if (res.isNotEmpty()) return@withContext res.take(limit)
            if (attempt < SEARCH_ATTEMPTS) delay(RETRY_DELAY_MS)
        }

        // Respaldo: instant answers (siempre disponible).
        instantAnswers(q).take(limit)
    }

    /**
     * Prepara el cliente: una visita a la portada para que el servidor no
     * conteste con la página de verificación. Sólo se hace una vez.
     */
    private fun prime() {
        if (!primed.compareAndSet(false, true)) return
        try {
            val req = HttpRequest.newBuilder(URI.create("https://duckduckgo.com/"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .GET()
                .build()
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) {
            // Si la portada falla, la búsqueda se intenta igual.
        }
    }

    /** API de instant answer: JSON con el resumen y los temas relacionados. */
    private fun instantAnswers(query: String): List<SearchResult> {
        val url = "https://api.duckduckgo.com/?q=" + enc(query) +
            "&format=json&no_html=1&no_redirect=1&skip_disambig=1"
        val body = getText(url) ?: return emptyList()
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<SearchResult>()
        val abstract = root.str("Abstract")
        val abstractUrl = root.str("AbstractURL")
        if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
            out += SearchResult(root.str("Heading").ifBlank { "DuckDuckGo" }, abstractUrl, abstract, SOURCE)
        }

        for (topic in (root["RelatedTopics"] as? JsonArray).orEmpty()) {
            collectTopic(topic, out)
        }
        for (res in (root["Results"] as? JsonArray).orEmpty()) {
            val obj = res as? JsonObject ?: continue
            val link = obj.str("FirstURL").ifBlank { obj.str("Result") }
            val label = obj.str("Text")
            if (link.isNotBlank() && label.isNotBlank()) {
                out += SearchResult(label.substringBefore(" - "), link, label, SOURCE)
            }
        }
        return out
    }

    /** Un topic puede traer sub-topics anidados (`Topics`). */
    private fun collectTopic(topic: JsonElement, out: MutableList<SearchResult>) {
        val obj = topic as? JsonObject ?: return
        val link = obj.str("FirstURL")
        val text = obj.str("Text")
        if (link.isNotBlank() && text.isNotBlank()) {
            out += SearchResult(text.substringBefore(" - "), link, text, SOURCE)
        }
        (obj["Topics"] as? JsonArray)?.forEach { collectTopic(it, out) }
    }

    /**
     * HTML de `html.duckduckgo.com`: se parsean los enlaces de resultado.
     *
     * La página se parte en **bloques** (un div por resultado) y de cada bloque
     * se saca el título y el fragmento. Así el fragmento siempre corresponde a
     * su título, aunque interspersed se ignore un anuncio.
     *
     * Devuelve una lista vacía si el sitio contestó con la página de
     * verificación (el llamador reintenta) o si de verdad no hay resultados.
     */
    private fun htmlResults(query: String): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=" + enc(query)
        val resp = get(url) ?: return emptyList()

        // La verificación responde 202 con una página sin resultados.
        if (resp.status == 202 || isChallenge(resp.body)) return emptyList()

        val out = mutableListOf<SearchResult>()
        for (block in resp.body.split(RESULT_BLOCK_RE).drop(1)) {
            val titleM = RESULT_TITLE_RE.find(block) ?: continue
            val link = cleanUrl(titleM.groupValues[1].attr("href"))
            if (link.isBlank() || isAd(link)) continue
            val title = text(titleM.groupValues[2])
            val snippet = RESULT_SNIPPET_RE.find(block)?.let { text(it.groupValues[1]) }.orEmpty()
            out += SearchResult(title.ifBlank { link }, link, snippet, SOURCE)
        }
        return out
    }

    /** ¿La respuesta es la página de verificación de DuckDuckGo? */
    private fun isChallenge(body: String): Boolean =
        body.contains("anomaly-modal", ignoreCase = true) ||
            body.contains("challenge-form", ignoreCase = true) ||
            body.contains("bots use DuckDuckGo", ignoreCase = true)

    /** Un GET simple que devuelve el cuerpo como texto (null si falla). */
    private fun getText(url: String): String? = get(url)?.body

    /** Un GET simple: devuelve estado y cuerpo (null si falla la conexión). */
    private fun get(url: String): HttpReply? = try {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(20))
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .header("Referer", "https://duckduckgo.com/")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        HttpReply(resp.statusCode(), resp.body())
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Nombre del motor: aparece en el resultado que ve el modelo. */
        const val SOURCE = "DuckDuckGo"

        /** Intentos de búsqueda HTML antes de pasar al respaldo. */
        const val SEARCH_ATTEMPTS = 3

        /** Espera entre intentos. */
        const val RETRY_DELAY_MS = 800L

        /** Un div que abre un resultado (`class="result results_links…"`). */
        private val RESULT_BLOCK_RE =
            Regex("""(?i)<div[^>]*class="[^"]*\bresult\b[^"]*"""")

        /** El enlace con el título: `<a class="result__a" href="…">Título</a>`. */
        private val RESULT_TITLE_RE =
            Regex("""(?is)<a\s+([^>]*?class="[^"]*\bresult__a\b[^"]*"[^>]*?)>(.*?)</a>""")

        /** El fragmento: `<a class="result__snippet" …>texto</a>`. */
        private val RESULT_SNIPPET_RE =
            Regex("""(?is)<a\s+[^>]*?class="[^"]*\bresult__snippet\b[^"]*"[^>]*?>(.*?)</a>""")
    }
}

/** Respuesta HTTP simple (estado + cuerpo). */
internal data class HttpReply(val status: Int, val body: String)

/**
 * ¿Es un enlace patrocinado del buscador? DuckDuckGo los sirve como
 * `duckduckgo.com/y.js?ad_domain=...`; no son resultados orgánicos.
 */
internal fun isAd(url: String): Boolean =
    url.contains("duckduckgo.com/y.js", ignoreCase = true) ||
        url.contains("ad_provider=", ignoreCase = true)

// ---------------------------------------------------------------------------
// Proveedor de respaldo: Wikipedia
// ---------------------------------------------------------------------------

/**
 * Búsqueda en Wikipedia (API oficial, sin clave y sin control anti-bot).
 *
 * No sustituye a un buscador general, pero es el respaldo más confiable: si el
 * motor principal contesta con la página de verificación, al menos se le
 * entrega al modelo material enciclopédico con su fuente.
 */
class WikipediaProvider(
    private val http: HttpClient = defaultHttpClient(),
    private val languages: List<String> = listOf("es", "en"),
) : SearchProvider {

    override suspend fun search(query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        for (lang in languages) {
            val results = searchIn(lang, q, limit)
            if (results.isNotEmpty()) return@withContext results
        }
        emptyList()
    }

    /** Nombre de la fuente (aparece en el resultado que ve el modelo). */
    companion object {
        const val SOURCE = "Wikipedia"
    }

    /** Busca en la wikipedia de un idioma. */
    private fun searchIn(lang: String, query: String, limit: Int): List<SearchResult> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
            enc(query) + "&srlimit=$limit&format=json&origin=*"
        val body = try {
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) return emptyList()
            resp.body()
        } catch (_: Exception) {
            return emptyList()
        }

        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val search = (root["query"] as? JsonObject)?.get("search") as? JsonArray ?: return emptyList()

        return search.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj.str("title")
            if (title.isBlank()) return@mapNotNull null
            val pageId = obj.str("pageid")
            val link = if (pageId.isNotBlank()) {
                "https://$lang.wikipedia.org/?curid=$pageId"
            } else {
                "https://$lang.wikipedia.org/wiki/" + enc(title.replace(' ', '_'))
            }
            SearchResult(title, link, text(obj.str("snippet")), SOURCE)
        }
    }
}

/**
 * Encadena proveedores: se prueban en orden y el primero que devuelva
 * resultados gana. Si uno falla o devuelve vacío, se pasa al siguiente.
 */
class ChainSearchProvider(
    private val providers: List<SearchProvider>,
) : SearchProvider {

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        for (p in providers) {
            val results = try {
                p.search(query, limit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (results.isNotEmpty()) return results.take(limit)
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------------------
// web_search
// ---------------------------------------------------------------------------

/** Herramienta `web_search`: busca en la web con DuckDuckGo. */
class WebSearchTool(
    private val provider: SearchProvider = ChainSearchProvider(
        listOf(DuckDuckGoProvider(), WikipediaProvider()),
    ),
) : Tool {

    override val category = ToolCategory.WEB

    override val spec = ToolSpec(
        name = "web_search",
        description = "Busca informacion en la web (DuckDuckGo, sin clave de API). " +
            "Usala cuando el usuario pida informacion actual, enlaces o paginas que no esten " +
            "en la documentacion local. Devuelve titulo, URL y un fragmento de cada resultado.",
        parametersSchema = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"texto a buscar"}
            },"required":["query"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val q = args.str("query")
        return if (q.isBlank()) {
            "Buscar en la web."
        } else {
            "Buscar en la web: \"$q\"."
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.str("query").trim()
        if (query.isEmpty()) return@withContext ToolResult.Error("Falta el texto a buscar.")

        val results = try {
            provider.search(query, MAX_RESULTS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ToolResult.Error(
                "La búsqueda falló: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        if (results.isEmpty()) {
            return@withContext ToolResult.Ok(
                "La búsqueda de \"$query\" no devolvió resultados. Probá con otros términos.",
            )
        }

        val body = results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n")

        val sources = results.map { it.source }.filter { it.isNotBlank() }.distinct().joinToString(", ")

        ToolResult.Ok(
            "Resultados para \"$query\" (${results.size}${if (sources.isNotBlank()) ", fuente: $sources" else ""}):\n\n" +
                "$body\n\n" +
                "Usá `web_fetch` con la URL que te interese para leer la página completa.",
        )
    }

    companion object {
        /** Máximo de resultados que se le devuelven al modelo. */
        const val MAX_RESULTS = 8
    }
}

// ---------------------------------------------------------------------------
// web_fetch
// ---------------------------------------------------------------------------

/** Herramienta `web_fetch`: lee una página web y devuelve su texto. */
class WebFetchTool(
    private val http: HttpClient = defaultHttpClient(),
) : Tool {

    override val category = ToolCategory.WEB

    override val spec = ToolSpec(
        name = "web_fetch",
        description = "Lee una pagina web (http/https) y devuelve su texto limpio: titulo, URL final " +
            "y contenido. Usala despues de `web_search` para leer el resultado que interesa. " +
            "Para archivos binarios (PDF, imagenes) usa `download_url`.",
        parametersSchema = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"URL completa de la pagina"}
            },"required":["url"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val url = args.str("url")
        return if (url.isBlank()) {
            "Leer una página web."
        } else {
            "Leer la página $url."
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val raw = args.str("url").trim()
        if (raw.isEmpty()) return@withContext ToolResult.Error("Falta la URL a leer.")
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            return@withContext ToolResult.Error("La URL debe empezar por `http://` o `https://`.")
        }

        val uri = try {
            URI.create(raw)
        } catch (_: Exception) {
            return@withContext ToolResult.Error("URL inválida: `$raw`.")
        }
        if (uri.host.isNullOrBlank()) {
            return@withContext ToolResult.Error("La URL no tiene un dominio válido: `$raw`.")
        }

        try {
            val req = HttpRequest.newBuilder(uri)
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .GET()
                .build()

            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299) {
                resp.body().close()
                return@withContext ToolResult.Error(
                    "No se pudo leer la página: el servidor respondió HTTP ${resp.statusCode()}.",
                )
            }

            val contentType = resp.headers().firstValue("content-type").orElse("")
            val charset = charsetOf(contentType)

            // El cuerpo puede quedar sin leer si supera el tope: hay que cerrarlo
            // igual, o la conexión queda colgada.
            val bytes = try {
                readLimited(resp.body(), MAX_FETCH_BYTES)
            } finally {
                resp.body().close()
            }
            val body = String(bytes, charset)
            val finalUrl = resp.uri().toString()

            // Binarios (PDF, imágenes): no son texto, se delegan a download_url.
            if (contentType.startsWith("application/pdf") ||
                contentType.startsWith("image/") ||
                contentType.startsWith("video/") ||
                contentType.startsWith("audio/")
            ) {
                return@withContext ToolResult.Error(
                    "La URL es un archivo binario ($contentType), no una página de texto. " +
                        "Usá `download_url` para bajarlo y entregarlo como adjunto.",
                )
            }

            val isHtml = contentType.contains("html", ignoreCase = true) || body.trimStart().startsWith("<")
            val title = if (isHtml) htmlTitle(body) else ""
            val text = if (isHtml) htmlToText(body) else body.trim()

            val trimmed = text.take(MAX_TEXT_CHARS)
            val note = if (text.length > MAX_TEXT_CHARS) {
                "\n\n(texto recortado: la página completa tiene ${text.length} caracteres)"
            } else {
                ""
            }

            ToolResult.Ok(
                "Título: ${title.ifBlank { "(sin título)" }}\nURL final: $finalUrl\n\n$trimmed$note",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error("No se pudo leer `$raw`: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        /** Tope de descarga de la página: 2 MB. */
        const val MAX_FETCH_BYTES = 2L * 1024 * 1024

        /** Tope de texto devuelto al modelo. */
        const val MAX_TEXT_CHARS = 20_000
    }
}

// ---------------------------------------------------------------------------
// Utilidades de HTML
// ---------------------------------------------------------------------------

/** User-Agent propio: los sitios y las APIs rechazan los genéricos. */
internal const val USER_AGENT = "FunshiEngineAI/1.0 (+https://github.com/FunshiEngine/FunshiEngineAI)"

/**
 * User-Agent de navegador: DuckDuckGo rechaza los clientes que no se parecen
 * a un navegador real (contestan con una página de verificación).
 */
internal const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Safari/537.36"

/**
 * Cliente HTTP por defecto (red con timeout y sin redirecciones rompidas).
 * Con [withCookies] mantiene un cookie jar en memoria: algunos sitios (como
 * DuckDuckGo) rechazan la petición si el cliente no pasó antes por la portada.
 */
internal fun defaultHttpClient(withCookies: Boolean = false): HttpClient {
    val builder = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(java.time.Duration.ofSeconds(20))
    if (withCookies) {
        builder.cookieHandler(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL))
    }
    return builder.build()
}

/** Codifica un texto para ponerlo en una query string. */
internal fun enc(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

/** Lee como mucho [max] bytes del flujo; si se pasa, corta. */
internal fun readLimited(input: java.io.InputStream, max: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1 shl 16)
    var total = 0L
    while (total < max) {
        val n = input.read(buf)
        if (n < 0) break
        val allowed = minOf(n.toLong(), max - total).toInt()
        out.write(buf, 0, allowed)
        total += allowed
        if (allowed < n) break          // se pasó del tope: se corta acá
    }
    return out.toByteArray()
}

/** Charset de la cabecera `Content-Type` (UTF-8 por defecto). */
internal fun charsetOf(contentType: String): Charset {
    val m = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE).find(contentType)
    val name = m?.groupValues?.getOrNull(1) ?: "utf-8"
    return try {
        Charset.forName(name)
    } catch (_: Exception) {
        StandardCharsets.UTF_8
    }
}

/** Quita los scripts, estilos y comentarios de un HTML. */
internal fun stripNoise(html: String): String {
    var s = html
    s = Regex("""(?is)<!--.*?-->""").replace(s, " ")
    s = Regex("""(?is)<script\b[^>]*>.*?</script>""").replace(s, " ")
    s = Regex("""(?is)<style\b[^>]*>.*?</style>""").replace(s, " ")
    s = Regex("""(?is)<noscript\b[^>]*>.*?</noscript>""").replace(s, " ")
    s = Regex("""(?is)<svg\b[^>]*>.*?</svg>""").replace(s, " ")
    s = Regex("""(?is)<head\b[^>]*>.*?</head>""").replace(s, " ")
    return s
}

/** Título de la página (vacío si no hay). */
internal fun htmlTitle(html: String): String {
    val m = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html) ?: return ""
    return text(m.groupValues[1]).trim()
}

/**
 * Convierte HTML en texto legible: saca los tags, pone saltos de línea en los
 * bloques y decodifica las entidades más comunes.
 */
internal fun htmlToText(html: String): String {
    var s = stripNoise(html)
    s = Regex("""(?i)<br\s*/?>""").replace(s, "\n")
    s = Regex("""(?i)</(p|div|li|tr|h1|h2|h3|h4|h5|h6|section|article|header|footer|ul|ol|table|pre|blockquote)>""")
        .replace(s, "\n")
    s = Regex("""(?i)<(p|div|li|tr|h1|h2|h3|h4|h5|h6|section|article|header|footer|ul|ol|table|pre|blockquote)\b[^>]*>""")
        .replace(s, "\n")
    s = Regex("""(?s)<[^>]+>""").replace(s, " ")
    s = decodeEntities(s)
    s = s.replace("\r\n", "\n").replace('\r', '\n')
    s = s.lines().joinToString("\n") { it.trim() }
    s = Regex("""\n{3,}""").replace(s, "\n\n")
    return s.trim()
}

/**
 * Decodifica las entidades HTML: las nombradas (Latin-1, puntuación, símbolos y
 * griegas) y las numéricas (`&#160;`, `&#xA0;`).
 *
 * Se resuelve todo en **una sola pasada**: así `&amp;lt;` queda `&lt;` y no
 * `<` (decodificar dos veces rompería el texto).
 */
internal fun decodeEntities(s: String): String =
    ENTITY_RE.replace(s) { m ->
        val body = m.groupValues[1]
        if (body.startsWith("#")) {
            val code = if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
            if (code == null || code < 0 || code > 0x10FFFF) m.value else String(Character.toChars(code))
        } else {
            ENTITIES[body] ?: m.value
        }
    }

/** Coincide con una entidad nombrada o numérica. */
private val ENTITY_RE = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});""")

/**
 * Entidades HTML nombradas.
 *
 * Los nombres **distinguen mayúsculas** (`&Aacute;` es Á y `&aacute;` es á),
 * así que la tabla se guarda tal cual la define el estándar. Los espacios y
 * los caracteres invisibles se convierten en espacio normal o en vacío, para
 * que el texto extraído se pueda limpiar con una expresión regular.
 */
private val ENTITIES: Map<String, String> = mapOf(
    // Basicos
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">",
    "quot" to "\"", "apos" to "'", "excl" to "¡", "iquest" to "¿",
    "num" to "#", "dollar" to "$", "percnt" to "%", "lpar" to "(", "rpar" to ")",
    "ast" to "*", "midast" to "*", "plus" to "+", "comma" to ",", "period" to ".",
    "sol" to "/", "colon" to ":", "semi" to ";", "equals" to "=",
    "commat" to "@", "lsqb" to "[", "rsqb" to "]", "Hat" to "ˆ",
    "lowbar" to "_", "grave" to "`", "lcub" to "{", "rcub" to "}",
    "verbar" to "|", "bsol" to "\\", "tilde" to "~", "circ" to "ˆ",
    // Latin-1
    "iexcl" to "¡", "cent" to "¢", "pound" to "£", "curren" to "¤",
    "yen" to "¥", "brvbar" to "¦", "sect" to "§", "uml" to "¨",
    "copy" to "©", "ordf" to "ª", "laquo" to "«", "not" to "¬",
    "shy" to "", "reg" to "®", "macr" to "¯", "deg" to "°",
    "plusmn" to "±", "sup2" to "²", "sup3" to "³", "acute" to "´",
    "micro" to "µ", "para" to "¶", "middot" to "·", "cedil" to "¸",
    "sup1" to "¹", "ordm" to "º", "raquo" to "»", "frac14" to "¼",
    "frac12" to "½", "frac34" to "¾",
    "agrave" to "à", "aacute" to "á", "acirc" to "â", "atilde" to "ã",
    "auml" to "ä", "aring" to "å", "aelig" to "æ", "ccedil" to "ç",
    "egrave" to "è", "eacute" to "é", "ecirc" to "ê", "euml" to "ë",
    "igrave" to "ì", "iacute" to "í", "icirc" to "î", "iuml" to "ï",
    "eth" to "ð", "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó",
    "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "divide" to "÷",
    "oslash" to "ø", "ugrave" to "ù", "uacute" to "ú", "ucirc" to "û",
    "uuml" to "ü", "yacute" to "ý", "thorn" to "þ", "yuml" to "ÿ",
    "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â", "Atilde" to "Ã",
    "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ", "Ccedil" to "Ç",
    "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê", "Euml" to "Ë",
    "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î", "Iuml" to "Ï",
    "ETH" to "Ð", "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó",
    "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "times" to "×",
    "Oslash" to "Ø", "Ugrave" to "Ù", "Uacute" to "Ú", "Ucirc" to "Û",
    "Uuml" to "Ü", "Yacute" to "Ý", "THORN" to "Þ", "szlig" to "ß",
    // Puntuacion y simbolos
    "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘",
    "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”",
    "bdquo" to "„", "dagger" to "†", "Dagger" to "‡", "bull" to "•",
    "permil" to "‰", "lsaquo" to "‹", "rsaquo" to "›", "oline" to "‾",
    "frasl" to "⁄", "euro" to "€", "trade" to "™", "larr" to "←",
    "uarr" to "↑", "rarr" to "→", "darr" to "↓", "harr" to "↔",
    "crarr" to "↵", "lArr" to "⇐", "uArr" to "⇑", "rArr" to "⇒",
    "dArr" to "⇓", "hArr" to "⇔", "forall" to "∀", "part" to "∂",
    "exist" to "∃", "empty" to "∅", "nabla" to "∇", "isin" to "∈",
    "notin" to "∉", "ni" to "∋", "prod" to "∏", "sum" to "∑",
    "minus" to "−", "lowast" to "∗", "prop" to "∝", "ang" to "∠",
    "and" to "∧", "or" to "∨", "cap" to "∩", "cup" to "∪",
    "int" to "∫", "there4" to "∴", "sim" to "∼", "cong" to "≅",
    "asymp" to "≈", "ne" to "≠", "equiv" to "≡", "le" to "≤",
    "ge" to "≥", "sub" to "⊂", "sup" to "⊃", "nsub" to "⊄",
    "sube" to "⊆", "supe" to "⊇", "oplus" to "⊕", "otimes" to "⊗",
    "perp" to "⊥", "sdot" to "⋅", "lceil" to "⌈", "rceil" to "⌉",
    "lfloor" to "⌊", "rfloor" to "⌋", "lang" to "〈", "rang" to "〉",
    "loz" to "◊", "spades" to "♠", "clubs" to "♣", "hearts" to "♥",
    "diams" to "♦", "weierp" to "℘", "image" to "ℑ", "real" to "ℜ",
    "alefsym" to "ℵ", "prime" to "′", "Prime" to "″",
    // Griegas
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
    "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
    "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π",
    "rho" to "ρ", "sigmaf" to "ς", "sigma" to "σ", "tau" to "τ",
    "upsilon" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ",
    "omega" to "ω", "upsih" to "ϒ", "phiv" to "ϕ",
    "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ",
    "Epsilon" to "Ε", "Zeta" to "Ζ", "Eta" to "Η", "Theta" to "Θ",
    "Iota" to "Ι", "Kappa" to "Κ", "Lambda" to "Λ", "Mu" to "Μ",
    "Nu" to "Ν", "Xi" to "Ξ", "Omicron" to "Ο", "Pi" to "Π",
    "Rho" to "Ρ", "Sigma" to "Σ", "Tau" to "Τ", "Upsilon" to "Υ",
    "Phi" to "Φ", "Chi" to "Χ", "Psi" to "Ψ", "Omega" to "Ω",
    // Espacios y caracteres invisibles
    "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "zwnj" to "", "zwj" to "", "lrm" to "", "rlm" to "",
)

/** Texto plano de un fragmento HTML (tags fuera, entidades resueltas). */
internal fun text(html: String): String = decodeEntities(
    Regex("""(?s)<[^>]+>""").replace(html, " "),
).replace(Regex("""\s+"""), " ").trim()

/** Extrae un atributo de una etiqueta (atributo="valor"). */
private fun String.attr(name: String): String {
    val m = Regex("""(?i)\b""" + Regex.escape(name) + """\s*=\s*"([^"]*)"""").find(this)
    return m?.groupValues?.getOrNull(1).orEmpty()
}

/** Quita la redirección de DuckDuckGo y deja la URL final. */
private fun cleanUrl(raw: String): String {
    var url = raw.trim()
    if (url.startsWith("//")) url = "https:" + url
    if (url.contains("duckduckgo.com/l/")) {
        val idx = url.indexOf("uddg=")
        if (idx >= 0) {
            val rest = url.substring(idx + 5)
            val amp = rest.indexOf('&')
            val enc = if (amp >= 0) rest.substring(0, amp) else rest
            url = try {
                URLDecoder.decode(enc, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                enc
            }
        }
    }
    return url
}
