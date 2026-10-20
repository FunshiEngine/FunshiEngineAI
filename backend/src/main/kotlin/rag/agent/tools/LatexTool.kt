package rag.agent.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import llm.api.ToolSpec
import rag.agent.Artifact
import rag.agent.Tool
import rag.agent.ToolCategory
import rag.agent.ToolContext
import rag.agent.ToolResult

/**
 * `latex_compile`: compila un documento LaTeX a PDF (F5b).
 *
 * Es de la categoría **FILES**: escribe archivos, así que pide permiso como
 * `create_file` (apagado por defecto, se concede por chat con
 * `/permitir-archivos`).
 *
 * La compilación la hace un motor de TeX instalado en el sistema
 * (`pdflatex` o `xelatex`, de TinyTeX, MiKTeX o TeX Live). **Si no hay
 * ninguno, la herramienta falla rápido** con una guía de instalación: es
 * mejor un error claro que un PDF que no se puede generar. La matemática del
 * chat (F5a) no necesita TeX: va con JLaTeXMath, que es una biblioteca de Java.
 *
 * El motor se ejecuta en un directorio temporal: los `.aux`, `.log` y demás no
 * ensucian la carpeta del chat. Al final sólo quedan el `.tex` y el `.pdf`.
 *
 * @param engineFinder resuelve el nombre de un motor (`pdflatex`, `xelatex`) a
 *   un comando ejecutable, o `null` si no está instalado. Es inyectable para
 *   poder probar la herramienta sin TeX en la máquina.
 */
class LatexCompileTool(
    private val engineFinder: (String) -> String? = { command -> findOnPath(command) },
) : Tool {

    override val category = ToolCategory.FILES

    override val spec = ToolSpec(
        name = "latex_compile",
        description = "Compila un documento LaTeX a PDF y lo deja como adjunto del chat. " +
            "Pasale el codigo LaTeX: puede ser un documento completo (con \\documentclass) " +
            "o un fragmento, en cuyo caso se le agrega un preambulo estandar. " +
            "Si la compilacion falla, devuelve el error de LaTeX.",
        parametersSchema = """
            {"type":"object","properties":{
              "document":{"type":"string","description":"codigo LaTeX del documento"},
              "name":{"type":"string","description":"nombre del PDF, sin extension (ej. informe)"},
              "engine":{"type":"string","description":"motor de TeX: pdflatex (por defecto) o xelatex"}
            },"required":["document"]}
        """.trimIndent(),
    )

    override fun describeCall(args: JsonObject): String {
        val name = args.str("name").trim()
        return if (name.isBlank()) {
            "Compilar un documento LaTeX a PDF."
        } else {
            "Compilar el documento LaTeX `$name` a PDF."
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val document = args.str("document")
        if (document.isBlank()) {
            return@withContext ToolResult.Error("Falta el documento LaTeX a compilar.")
        }
        val bytes = document.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            return@withContext ToolResult.Error(
                "El documento es demasiado grande: ${bytes.size} bytes (máximo $MAX_DOCUMENT_BYTES).",
            )
        }

        // Motor: el que pidió el modelo (si vale) o el primero que esté instalado.
        val requested = args.str("engine").trim().lowercase()
        if (requested.isNotEmpty() && requested !in ENGINES) {
            return@withContext ToolResult.Error(
                "Motor desconocido: `$requested`. Usá `pdflatex` o `xelatex`.",
            )
        }
        val engineLabel: String
        val engineCommand: String
        if (requested.isNotEmpty()) {
            val found = engineFinder(requested)
            if (found == null) {
                return@withContext ToolResult.Error(
                    "El motor `$requested` no está instalado en el sistema. ${installGuide(requested)}",
                )
            }
            engineLabel = requested
            engineCommand = found
        } else {
            val found = ENGINES.firstNotNullOfOrNull { engineFinder(it) }
            if (found == null) {
                return@withContext ToolResult.Error(
                    "No hay ningún motor de LaTeX instalado en el sistema. ${installGuide()}",
                )
            }
            engineLabel = ENGINES.first { engineFinder(it) == found }
            engineCommand = found
        }

        val name = sanitizeBaseName(args.str("name"))
        val source = withPreamble(document)

        val dir = ctx.outputsDir
        Files.createDirectories(dir)
        val texName = "$name.tex"
        val texTarget = dir.resolve(texName).normalize()
        if (!texTarget.startsWith(dir)) {
            return@withContext ToolResult.Error("No se pudo construir una ruta segura para `$texName`.")
        }
        Files.write(texTarget, source.toByteArray(StandardCharsets.UTF_8))

        // Directorio de trabajo aparte: los .aux y .log no quedan en la carpeta
        // del chat. El .tex se copia acá porque el motor lo busca en su carpeta.
        val work = try {
            Files.createTempDirectory("funshi-latex-")
        } catch (e: Exception) {
            return@withContext ToolResult.Error(
                "No se pudo crear el directorio de trabajo: ${e.message ?: e.javaClass.simpleName}",
            )
        }

        try {
            Files.copy(texTarget, work.resolve(texName), StandardCopyOption.REPLACE_EXISTING)
            val run = runEngine(engineCommand, work, texName)
            if (run.error != null) {
                return@withContext ToolResult.Error(run.error)
            }

            val produced = findPdf(work, name)
            if (produced == null) {
                return@withContext ToolResult.Error(
                    "LaTeX terminó sin errores pero no generó el PDF. " +
                        (run.log?.takeLast(600)?.trim().orEmpty()),
                )
            }

            val pdfTarget = dir.resolve("$name.pdf").normalize()
            if (!pdfTarget.startsWith(dir)) {
                return@withContext ToolResult.Error("No se pudo construir una ruta segura para `$name.pdf`.")
            }
            Files.copy(produced, pdfTarget, StandardCopyOption.REPLACE_EXISTING)
            val size = Files.size(pdfTarget)

            ToolResult.Ok(
                text = "PDF compilado: $name.pdf ($size bytes, motor $engineLabel). " +
                    "El usuario puede abrirlo o guardarlo desde la tarjeta del chat. " +
                    "El código fuente quedó en $texName.",
                artifacts = listOf(
                    Artifact(
                        name = "$name.pdf",
                        path = pdfTarget.toAbsolutePath().toString(),
                        sizeBytes = size,
                        mime = "application/pdf",
                    ),
                    Artifact(
                        name = texName,
                        path = texTarget.toAbsolutePath().toString(),
                        sizeBytes = Files.size(texTarget),
                        mime = "application/x-tex",
                    ),
                ),
            )
        } finally {
            // El temporal se borra siempre: sólo quedan el .tex y el .pdf.
            try {
                work.toFile().deleteRecursively()
            } catch (_: Exception) {
                // si no se puede borrar, no es grave: está en el temp del sistema
            }
        }
    }

    /**
     * Corre el motor de TeX sobre [texName] (que está en [work]).
     *
     * Devuelve el log en [EngineRun.log] o el error (legible) en
     * [EngineRun.error].
     */
    private fun runEngine(command: String, work: Path, texName: String): EngineRun {
        val arguments = listOf(
            "-interaction=nonstopmode",
            "-halt-on-error",
            "-file-line-error",
            texName,
        )
        // En Windows, los .bat y .cmd no se ejecutan solos: van por `cmd /c`.
        val line = if (isWindows && (command.endsWith(".bat", true) || command.endsWith(".cmd", true))) {
            listOf("cmd.exe", "/c", command) + arguments
        } else {
            listOf(command) + arguments
        }
        val builder = ProcessBuilder(line)
            .directory(work.toFile())
            .redirectErrorStream(true)

        val process = try {
            builder.start()
        } catch (e: Exception) {
            return EngineRun(null, "No se pudo ejecutar el motor de LaTeX: ${e.message ?: e.javaClass.simpleName}")
        }

        // LaTeX no debe esperar entrada: se cierra la entrada estándar.
        try { process.outputStream.close() } catch (_: Exception) { }

        val log = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    r.forEachLine { line -> synchronized(log) { log.appendLine(line) } }
                }
            } catch (_: Exception) {
                // el proceso terminó o se cortó: no importa
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = try {
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return EngineRun(null, "La compilación se interrumpió.")
        }

        if (!finished) {
            process.destroyForcibly()
            return EngineRun(null, "La compilación tardó más de $TIMEOUT_SECONDS segundos y se cortó.")
        }

        // El lector puede tener pendiente el final del log.
        reader.join(READER_JOIN_MS)

        val text = synchronized(log) { log.toString() }
        val exit = process.exitValue()
        if (exit != 0) {
            return EngineRun(text, "LaTeX no pudo compilar el documento (código de salida $exit).\n${extractErrors(text)}")
        }
        return EngineRun(text, null)
    }

    /**
     * Busca el PDF generado en [work]: normalmente es `<name>.pdf`, pero si el
     * motor usó otro nombre (por ejemplo por `\jobname`), se toma el único PDF
     * que haya.
     */
    private fun findPdf(work: Path, name: String): Path? {
        val expected = work.resolve("$name.pdf")
        if (Files.isRegularFile(expected)) return expected
        val candidates = try {
            Files.list(work).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
        val pdfs = candidates.filter { it.fileName.toString().endsWith(".pdf", true) }
        return pdfs.singleOrNull()
    }

    /** Saca del log los errores de LaTeX (las líneas `!` y las `archivo:línea:`). */
    private fun extractErrors(log: String): String {
        val lines = log.lines()
        val errors = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            val text = line.trim()
            val isError = text.startsWith("!") || FILE_ERROR_RE.containsMatchIn(text)
            if (isError && errors.size < 6) {
                errors += text
                // La línea siguiente suele decir dónde está el problema ("l.12 ...").
                lines.getOrNull(index + 1)?.trim()?.takeIf { it.startsWith("l.") }?.let { errors += it }
            }
        }
        if (errors.isEmpty()) {
            val tail = lines.takeLast(10)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            return "Últimas líneas del log:\n$tail"
        }
        return errors.joinToString("\n")
    }

    /** Guía de instalación según el motor que falta. */
    private fun installGuide(engine: String = ""): String = when (engine) {
        "xelatex" ->
            "xelatex viene con TeX Live y con MiKTeX: instalá `TeX Live` (https://texlive.org) " +
                "o `MiKTeX` (https://miktex.org) y agregá la carpeta `bin` al PATH."
        else ->
            "Para compilar PDFs hace falta TeX: instalá `TeX Live` (https://texlive.org, " +
                "o el paquete `texlive-full`) o `MiKTeX` (https://miktex.org), y agregá la carpeta " +
                "`bin` al PATH. La matemática del chat no lo necesita: se dibuja con JLaTeXMath."
    }

    /** Nombre base del PDF: sin rutas ni separadores. */
    private fun sanitizeBaseName(raw: String): String {
        val cleaned = raw.trim()
            .replace(UNSAFE_NAME_RE, "-")
            .trim('.', '-')
            .take(MAX_NAME_LENGTH)
            .trim('.', '-')
        return cleaned.ifBlank { "documento" }
    }

    /**
     * Si el documento no trae `\documentclass`, se le envuelve en un preámbulo
     * estándar: así el modelo puede mandar un fragmento y sale igual.
     */
    private fun withPreamble(document: String): String {
        if (document.contains("\\documentclass")) return document
        return buildString {
            appendLine(PREAMBLE)
            appendLine(document)
            if (!document.contains("\\end{document}")) appendLine("\\end{document}")
        }
    }

    /** Resultado de correr el motor. */
    private data class EngineRun(val log: String?, val error: String?)

    companion object {
        /** Motores que se buscan, en orden de preferencia. */
        val ENGINES = listOf("pdflatex", "xelatex")

        /** Tope del documento LaTeX: 1 MB. */
        const val MAX_DOCUMENT_BYTES = 1024L * 1024

        /** Tope del nombre base del PDF. */
        const val MAX_NAME_LENGTH = 60

        /** Segundos que puede tardar la compilación. */
        const val TIMEOUT_SECONDS = 60L

        /** Espera a que el lector del log termine. */
        const val READER_JOIN_MS = 1_000L

        /** Preámbulo que se usa cuando el documento es un fragmento. */
        const val PREAMBLE = """\documentclass[11pt,a4paper]{article}
\usepackage[utf8]{inputenc}
\usepackage{amsmath,amssymb}
\usepackage[margin=2.5cm]{geometry}
\begin{document}"""

        /** Errores de LaTeX con el formato `archivo.tex:12: mensaje`. */
        private val FILE_ERROR_RE = Regex("""^\.?[\w./\\-]*\.tex:\d+:""")

        /** Caracteres que no pueden ir en un nombre de archivo. */
        private val UNSAFE_NAME_RE = Regex("""[\\/:*?"<>|\s]+""")

        /** ¿Estamos en Windows? (cambia las extensiones que se buscan). */
        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("win")

        /**
         * Busca un comando en el PATH y devuelve su ruta absoluta, o `null` si
         * no está. No lanza procesos: sólo mira el disco.
         */
        fun findOnPath(command: String): String? {
            val path = System.getenv("PATH") ?: return null
            val separator = System.getProperty("path.separator", ";")
            val extensions = if (isWindows) listOf(".exe", ".bat", ".cmd", "") else listOf("")
            for (dir in path.split(separator)) {
                if (dir.isBlank()) continue
                for (ext in extensions) {
                    try {
                        val file = Path.of(dir, command + ext)
                        if (Files.isRegularFile(file)) return file.toAbsolutePath().toString()
                    } catch (_: Exception) {
                        // ruta inválida en el PATH: se ignora
                    }
                }
            }
            return null
        }
    }
}
