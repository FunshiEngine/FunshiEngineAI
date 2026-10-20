package app.ui

import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula

/**
 * Render de fórmulas LaTeX a imagen (F5a).
 *
 * Se usa **JLaTeXMath**: es una biblioteca de Java pura que dibuja la fórmula
 * en un `BufferedImage` en memoria. No habla con ningún servicio ni necesita
 * una instalación de TeX: el chat muestra matemática sin conexión.
 *
 * Si la fórmula está mal escrita se devuelve `null` y el chat la muestra como
 * texto, para no perder lo que el modelo quiso decir.
 */
object MathRender {

    /** Tamaño de letra del render (en puntos, como los del tema). */
    const val FONT_SIZE = 20f

    /**
     * Renderiza [latex] a una imagen con fondo transparente.
     *
     * @param display `true` para el estilo de fórmula centrada (`$$…$$`),
     *   `false` para el de texto (`$…$`).
     * @param color color del trazo: el render sale en negro y se recolorea.
     */
    fun render(
        latex: String,
        display: Boolean,
        color: Color = Color.BLACK,
        fontSize: Float = FONT_SIZE,
    ): BufferedImage? {
        val src = latex.trim()
        if (src.isEmpty()) return null
        val image = try {
            val icon = TeXFormula(src).createTeXIcon(
                if (display) TeXConstants.STYLE_DISPLAY else TeXConstants.STYLE_TEXT,
                fontSize,
            )
            val w = icon.iconWidth
            val h = icon.iconHeight
            if (w <= 0 || h <= 0) return null
            BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { img ->
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                // TeXIcon es un Icon de Swing: el Component no se usa.
                icon.paintIcon(null, g, 0, 0)
                g.dispose()
            }
        } catch (_: Exception) {
            // Fórmula inválida: se devuelve null y se muestra el texto.
            return null
        }
        return tint(image, color)
    }

    /**
     * Pinta el color pedido sobre los trazos del render.
     *
     * JLaTeXMath dibuja siempre en negro, así que se recolorea la imagen: como
     * el fondo es transparente, alcanza con cambiar el RGB de cada píxel con
     * alfa y dejar el alfa como estaba (los bordes suavizados se mantienen).
     */
    private fun tint(img: BufferedImage, color: Color): BufferedImage {
        val r = color.red
        val g = color.green
        val b = color.blue
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val argb = img.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha != 0) {
                    img.setRGB(x, y, (alpha shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
        }
        return img
    }
}

/**
 * Un tramo de texto: o texto normal o una fórmula.
 *
 * [MathSegment.Formula.id] identifica la fórmula dentro del párrafo (sirve
 * para enchufar la imagen en el texto) y [MathSegment.Formula.display] dice si
 * es una fórmula centrada o en línea.
 */
sealed class MathSegment {
    data class Plain(val text: String) : MathSegment()
    data class Formula(val id: String, val latex: String, val display: Boolean) : MathSegment()
}

/**
 * Parte un texto en tramos de texto y de fórmula.
 *
 * Reglas:
 * - `$$…$$` es una fórmula **centrada**; `$…$` es una fórmula **en línea**.
 * - Una fórmula sin cerrar se deja como texto (importante con streaming: el
 *   modelo escribe `$x` y recién después el `$$` final).
 * - Los signos `$` de precios (`$5 … $10`) no se toman como fórmula: el cierre
 *   no puede estar seguido de un dígito.
 * - El código en línea (`…`) se copia tal cual: ahí no hay matemática.
 */
fun splitMath(src: String): List<MathSegment> {
    val out = ArrayList<MathSegment>()
    val plain = StringBuilder()
    var index = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            out += MathSegment.Plain(plain.toString())
            plain.clear()
        }
    }

    while (index < src.length) {
        val c = src[index]

        // Código en línea: se copia entero.
        if (c == '`') {
            val end = src.indexOf('`', index + 1)
            if (end > index) {
                plain.append(src, index, end + 1)
                index = end + 1
                continue
            }
        }

        // Fórmula centrada: $$…$$
        if (c == '$' && index + 1 < src.length && src[index + 1] == '$') {
            val end = src.indexOf("$$", index + 2)
            if (end > index + 2) {
                flush()
                out += MathSegment.Formula(
                    id = "math${out.size}",
                    latex = src.substring(index + 2, end).trim(),
                    display = true,
                )
                index = end + 2
                continue
            }
        }

        // Fórmula en línea: $…$
        if (c == '$') {
            val end = findInlineMathEnd(src, index + 1)
            if (end > index) {
                flush()
                out += MathSegment.Formula(
                    id = "math${out.size}",
                    latex = src.substring(index + 1, end).trim(),
                    display = false,
                )
                index = end + 1
                continue
            }
        }

        plain.append(c)
        index++
    }
    flush()
    return out
}

/**
 * Busca el `$` que cierra una fórmula en línea que empieza en [from].
 * Devuelve -1 si no hay cierre válido.
 */
private fun findInlineMathEnd(src: String, from: Int): Int {
    var i = from
    while (i < src.length) {
        val c = src[i]
        if (c == '\n') return -1                       // la fórmula no corta de línea
        if (c == '\\') { i += 2; continue }           // carácter escapado
        if (c == '$') {
            if (i == from) return -1                  // "$$" no es inline
            if (src[i - 1].isWhitespace()) return -1   // "$ x $" no es fórmula
            val next = src.getOrNull(i + 1)
            if (next != null && next.isDigit()) return -1  // "$5 … $10" son precios
            return i
        }
        i++
    }
    return -1
}

/** Una fórmula ya renderizada (o fallida, si [image] es `null`). */
data class MathRendered(
    val id: String,
    val latex: String,
    val image: BufferedImage?,
)
