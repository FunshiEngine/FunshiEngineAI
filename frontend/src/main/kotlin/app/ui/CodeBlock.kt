package app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/* ==========================================================================
 * Bloque de código con resaltado de sintaxis, copia y "Guardar como…".
 * ========================================================================== */

private val LANG_TO_EXT = mapOf(
    "kotlin" to "kt", "kts" to "kts", "java" to "java", "python" to "py",
    "py" to "py", "javascript" to "js", "js" to "js", "typescript" to "ts",
    "ts" to "ts", "tsx" to "tsx", "jsx" to "jsx", "glsl" to "glsl",
    "hlsl" to "hlsl", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
    "c" to "c", "h" to "h", "hpp" to "hpp", "rs" to "rs", "rust" to "rs",
    "go" to "go", "cs" to "cs", "csharp" to "cs", "php" to "php",
    "swift" to "swift", "html" to "html", "htm" to "html", "css" to "css",
    "scss" to "scss", "sass" to "sass", "json" to "json", "jsonc" to "json",
    "xml" to "xml", "yaml" to "yml", "yml" to "yml", "bash" to "sh",
    "sh" to "sh", "zsh" to "sh", "fish" to "fish", "sql" to "sql",
    "ruby" to "rb", "rb" to "rb", "lua" to "lua", "perl" to "pl",
    "r" to "r", "dart" to "dart", "scala" to "scala", "groovy" to "groovy",
    "text" to "txt", "plaintext" to "txt", "md" to "md", "markdown" to "md",
)

@Composable
fun CodeBlock(
    lang: String,
    code: String,
    closed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ext = LANG_TO_EXT[lang.lowercase()] ?: "txt"
    
    // Capturar colores del tema AQUÍ (en contexto @Composable)
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceContainerHighest = MaterialTheme.colorScheme.surfaceContainerHighest
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val onSecondaryContainer = MaterialTheme.colorScheme.onSecondaryContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    val highlighted = remember(code, lang, primaryColor, onSurfaceVariant, onSurface, tertiary, secondary) {
        highlightCode(code, lang.lowercase(), primaryColor, onSurfaceVariant, onSurface, tertiary, secondary)
    }
    var copied by remember { mutableStateOf(false) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    Column(modifier
        .fillMaxWidth()
        .background(surfaceContainerHighest)
        .clip(RoundedCornerShape(8.dp))
    ) {
        // Encabezado simple: lenguaje + botones
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (closed) lang.uppercase() else "$lang (en curso)",
                style = MaterialTheme.typography.labelMedium,
                color = onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { copyToClipboard(code); copied = true }) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = if (copied) "Copiado" else "Copiar",
                        tint = if (copied) primaryColor else onSurfaceVariant,
                    )
                }
                IconButton(onClick = { saveAsFile(code, ext) }) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Guardar como…",
                        tint = onSurfaceVariant,
                    )
                }
            }
        }

        // Cuerpo del código
        Text(
            text = highlighted,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Start,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        )

        // Toast "Copiado" / "Guardado en…"
        if (copied) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(primaryContainer.copy(alpha = 0.9f))
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                Text("Copiado al portapapeles", style = MaterialTheme.typography.labelSmall, color = onPrimaryContainer, modifier = Modifier.padding(8.dp))
            }
        }
        if (savedPath != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(secondaryContainer.copy(alpha = 0.9f))
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                Text("Guardado en: $savedPath", style = MaterialTheme.typography.labelSmall, color = onSecondaryContainer, modifier = Modifier.padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    LaunchedEffect(copied) {
        if (copied) { try { Thread.sleep(1500) } catch (_: InterruptedException) { return@LaunchedEffect }; copied = false }
    }
    LaunchedEffect(savedPath) {
        if (savedPath != null) { try { Thread.sleep(2500) } catch (_: InterruptedException) { return@LaunchedEffect }; savedPath = null }
    }
}

private fun copyToClipboard(text: String) {
    try {
        val toolkit = Toolkit.getDefaultToolkit()
        val clipboard = toolkit.systemClipboard
        clipboard.setContents(StringSelection(text), null)
    } catch (e: Exception) { }
}

private fun saveAsFile(code: String, ext: String) {
    val chooser = JFileChooser()
    chooser.dialogTitle = "Guardar bloque de código"
    chooser.selectedFile = File("bloque.$ext")
    chooser.fileFilter = FileNameExtensionFilter("Archivo *.$ext", ext)
    SwingUtilities.invokeLater {
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile.toPath()
            try { Files.writeString(file, code, StandardCharsets.UTF_8) } catch (_: Exception) { }
        }
    }
}

/* ==========================================================================
 * Resaltado de sintaxis ligero.
 * ========================================================================== */

private enum class TokType { KEYWORD, TYPE, STRING, COMMENT, NUMBER, OPERATOR, DEFAULT }
private data class Tok(val type: TokType, val text: String)

/** Tokeniza y construye AnnotatedString (colores pasados como parámetros). */
private fun highlightCode(
    code: String, lang: String,
    primaryColor: Color, onSurfaceVariant: Color, onSurface: Color,
    tertiary: Color, secondary: Color
): AnnotatedString {
    val tokens = tokenize(code, lang)
    val builder = AnnotatedString.Builder()
    tokens.forEach { tok ->
        builder.withStyle(styleFor(tok.type, primaryColor, onSurfaceVariant, onSurface, tertiary, secondary)) {
            append(tok.text)
        }
    }
    return builder.toAnnotatedString()
}

private val KEYWORDS_BY_LANG = mapOf(
    "kotlin" to setOf("fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when", "if", "else", "for", "while", "do", "return", "throw", "try", "catch", "finally", "in", "is", "as", "by", "lateinit", "lazy", "const", "suspend", "coroutineScope", "launch", "async", "await", "private", "public", "internal", "protected", "open", "override", "abstract", "final", "companion", "init", "constructor", "this", "super", "null", "true", "false", "package", "import", "typealias", "annotation", "inline", "crossinline", "noinline", "reified", "tailrec", "operator", "infix", "get", "set", "field", "property", "delegate"),
    "java" to setOf("class", "interface", "enum", "record", "public", "private", "protected", "static", "final", "abstract", "extends", "implements", "new", "this", "super", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "throw", "try", "catch", "finally", "throws", "import", "package", "instanceof", "new", "null", "true", "false", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "String"),
    "javascript" to setOf("const", "let", "var", "function", "async", "await", "class", "extends", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "this", "super", "import", "export", "from", "as", "typeof", "instanceof", "in", "of", "null", "true", "false", "undefined", "NaN", "Infinity"),
    "typescript" to setOf("const", "let", "var", "function", "async", "await", "class", "interface", "type", "extends", "implements", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "this", "super", "import", "export", "from", "as", "typeof", "instanceof", "in", "of", "null", "true", "false", "undefined", "never", "unknown", "any", "void", "string", "number", "boolean", "object", "Array", "Promise"),
    "python" to setOf("def", "class", "if", "elif", "else", "for", "while", "break", "continue", "return", "yield", "import", "from", "as", "with", "as", "try", "except", "finally", "raise", "assert", "lambda", "global", "nonlocal", "del", "pass", "True", "False", "None", "and", "or", "not", "in", "is", "async", "await", "match", "case"),
    "glsl" to setOf("attribute", "uniform", "varying", "const", "in", "out", "inout", "precision", "highp", "mediump", "lowp", "if", "else", "for", "while", "do", "break", "continue", "return", "discard", "struct", "void", "bool", "int", "float", "vec2", "vec3", "vec4", "mat2", "mat3", "mat4", "sampler2D", "samplerCube", "main"),
    "hlsl" to setOf("cbuffer", "ConstantBuffer", "Texture2D", "SamplerState", "float", "float2", "float3", "float4", "int", "uint", "bool", "if", "else", "for", "while", "do", "break", "continue", "return", "discard", "struct", "static", "uniform", "register", "packoffset", "main", "SV_Position", "SV_Target"),
    "cpp" to setOf("class", "struct", "enum", "template", "typename", "public", "private", "protected", "static", "virtual", "override", "final", "constexpr", "const", "volatile", "explicit", "friend", "inline", "namespace", "using", "operator", "new", "delete", "this", "nullptr", "true", "false", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "try", "catch", "throw", "sizeof", "alignof", "static_assert", "auto", "decltype"),
    "c" to setOf("struct", "enum", "union", "typedef", "static", "const", "volatile", "extern", "inline", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "goto", "sizeof", "NULL", "true", "false", "int", "char", "float", "double", "void", "short", "long", "signed", "unsigned"),
    "rust" to setOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "mod", "use", "pub", "crate", "super", "self", "Self", "match", "if", "else", "loop", "while", "for", "break", "continue", "return", "async", "await", "move", "ref", "mut", "dyn", "Box", "Vec", "Option", "Result", "Some", "None", "Ok", "Err", "true", "false"),
    "go" to setOf("func", "var", "const", "type", "struct", "interface", "package", "import", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go", "defer", "select", "chan", "make", "new", "nil", "true", "false", "int", "string", "bool", "float64", "byte", "rune", "error"),
    "cs" to setOf("class", "interface", "struct", "enum", "public", "private", "protected", "internal", "static", "readonly", "const", "virtual", "override", "abstract", "sealed", "partial", "async", "await", "return", "if", "else", "for", "foreach", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "using", "namespace", "this", "base", "null", "true", "false", "string", "int", "bool", "var", "object"),
    "php" to setOf("function", "class", "interface", "trait", "public", "private", "protected", "static", "final", "abstract", "extends", "implements", "new", "return", "if", "elseif", "else", "for", "foreach", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "use", "namespace", "global", "this", "self", "parent", "null", "true", "false", "echo", "print", "include", "require", "isset", "empty", "array"),
    "swift" to setOf("func", "var", "let", "class", "struct", "enum", "protocol", "extension", "public", "private", "internal", "fileprivate", "open", "final", "override", "static", "lazy", "return", "if", "else", "for", "in", "while", "repeat", "switch", "case", "default", "break", "continue", "try", "catch", "throw", "defer", "guard", "import", "init", "self", "super", "nil", "true", "false", "Int", "String", "Bool", "Double", "Float"),
    "html" to setOf("html", "head", "body", "div", "span", "script", "style", "link", "meta", "title", "header", "footer", "nav", "main", "section", "article", "aside", "p", "a", "img", "ul", "ol", "li", "table", "tr", "td", "th", "form", "input", "button", "select", "option", "textarea"),
    "css" to setOf("color", "background", "margin", "padding", "border", "display", "flex", "grid", "position", "top", "left", "right", "bottom", "width", "height", "font", "text", "align", "justify", "content", "items", "gap", "overflow", "z-index", "opacity", "transform", "transition", "animation", "media", "query", "hover", "focus", "active", "before", "after"),
    "json" to setOf("true", "false", "null"),
    "xml" to setOf("xml", "version", "encoding"),
    "yaml" to setOf("true", "false", "null", "yes", "no"),
    "bash" to setOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function", "return", "break", "continue", "local", "export", "source", "alias", "true", "false"),
    "sql" to setOf("SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "ON", "GROUP", "BY", "HAVING", "ORDER", "ASC", "DESC", "LIMIT", "OFFSET", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "INDEX", "VIEW", "DROP", "ALTER", "ADD", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "NOT", "NULL", "UNIQUE", "DEFAULT", "CHECK", "AND", "OR", "TRUE", "FALSE"),
    "lua" to setOf("function", "local", "return", "if", "then", "else", "elseif", "end", "for", "in", "do", "while", "repeat", "until", "break", "continue", "nil", "true", "false", "and", "or", "not", "require", "module", "package"),
    "ruby" to setOf("def", "class", "module", "end", "if", "elsif", "else", "unless", "case", "when", "for", "while", "until", "break", "next", "redo", "retry", "return", "yield", "begin", "rescue", "ensure", "raise", "true", "false", "nil", "self", "super", "require", "include", "extend", "attr_accessor"),
    "dart" to setOf("var", "final", "const", "class", "extends", "implements", "mixin", "function", "return", "if", "else", "for", "in", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "this", "super", "import", "export", "library", "part", "async", "await", "null", "true", "false", "int", "double", "String", "bool", "List", "Map"),
    "scala" to setOf("def", "val", "var", "class", "trait", "object", "case", "extends", "with", "return", "if", "else", "for", "while", "do", "match", "case", "try", "catch", "finally", "throw", "new", "this", "super", "import", "package", "null", "true", "false", "Unit", "Int", "String", "Boolean"),
    "groovy" to setOf("def", "class", "interface", "trait", "return", "if", "else", "for", "in", "while", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "this", "super", "import", "package", "null", "true", "false", "String", "int", "boolean"),
)

private val TYPE_KEYWORDS = setOf(
    "Int", "Long", "Double", "Float", "Boolean", "String", "Char", "Byte", "Short",
    "int", "long", "double", "float", "boolean", "char", "byte", "short",
    "List", "Set", "Map", "Array", "Collection", "Iterable", "Sequence",
    "Future", "Promise", "Result", "Option", "Optional", "Nullable",
    "void", "Void", "Unit", "Nothing", "Any", "Object",
)

private fun tokenize(code: String, lang: String): List<Tok> {
    val keywords = KEYWORDS_BY_LANG[lang] ?: emptySet()
    val hasBlockComment = lang in setOf("java", "cpp", "c", "cs", "js", "ts", "tsx", "jsx", "glsl", "hlsl", "css", "rust", "go", "php", "swift", "kotlin", "dart", "scala")
    val lineCommentChar = when (lang) {
        "python", "bash", "sh", "ruby", "yaml", "toml" -> '#'
        "sql", "lua" -> '-'
        else -> '/'
    }
    val out = ArrayList<Tok>()
    var i = 0
    while (i < code.length) {
        val ch = code[i]
        when {
            ch == '"' || ch == '\'' || (ch == '`' && lang in BACKTICK_LANGS) -> {
                val quote = ch
                val s = i
                i++
                while (i < code.length) {
                    val cur = code[i]
                    if (cur == '\\' && i + 1 < code.length) {
                        i += 2
                    } else if (cur == quote) {
                        i++
                        break
                    } else {
                        i++
                    }
                }
                out += Tok(TokType.STRING, code.substring(s, i))
            }
            ch == '/' && i + 1 < code.length && code[i + 1] == '/' && lineCommentChar == '/' -> {
                val s = i
                i += 2
                while (i < code.length && code[i] != '\n') i++
                out += Tok(TokType.COMMENT, code.substring(s, i))
            }
            (ch == '#' || (ch == '-' && i + 1 < code.length && code[i + 1] == '-')) && lineCommentChar != '/' -> {
                val s = i
                i++
                if (ch == '-' && i < code.length && code[i] == '-') i++
                while (i < code.length && code[i] != '\n') i++
                out += Tok(TokType.COMMENT, code.substring(s, i))
            }
            ch == '/' && i + 1 < code.length && code[i + 1] == '*' && hasBlockComment -> {
                val s = i
                i += 2
                while (i + 1 < code.length) {
                    if (code[i] == '*' && code[i + 1] == '/') {
                        i += 2
                        break
                    }
                    i++
                }
                if (i > code.length) i = code.length
                out += Tok(TokType.COMMENT, code.substring(s, i))
            }
            ch.isDigit() -> {
                val s = i
                i++
                while (i < code.length && code[i] in NUMBER_CHARS) i++
                out += Tok(TokType.NUMBER, code.substring(s, i))
            }
            ch.isLetter() || ch == '_' -> {
                val s = i
                i++
                while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val w = code.substring(s, i)
                val t = when {
                    w in keywords -> TokType.KEYWORD
                    w in TYPE_KEYWORDS -> TokType.TYPE
                    else -> TokType.DEFAULT
                }
                out += Tok(t, w)
            }
            else -> {
                val s = i
                i++
                out += Tok(TokType.OPERATOR, code.substring(s, i))
            }
        }
    }
    return out
}

private val BACKTICK_LANGS = setOf("javascript", "typescript", "ts", "js", "jsx", "tsx")

private const val NUMBER_CHARS = "0123456789._xXbBfFlLuU"

private fun styleFor(
    type: TokType, primaryColor: Color, onSurfaceVariant: Color, onSurface: Color,
    tertiary: Color, secondary: Color
): SpanStyle = when (type) {
    TokType.KEYWORD -> SpanStyle(color = primaryColor, fontWeight = FontWeight.SemiBold)
    TokType.TYPE -> SpanStyle(color = secondary, fontWeight = FontWeight.Medium)
    TokType.STRING -> SpanStyle(color = tertiary)
    TokType.COMMENT -> SpanStyle(color = onSurfaceVariant.copy(alpha = 0.7f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
    TokType.NUMBER -> SpanStyle(color = onSurface.copy(alpha = 0.85f))
    TokType.OPERATOR -> SpanStyle(color = onSurface.copy(alpha = 0.6f))
    else -> SpanStyle(color = onSurface)
}