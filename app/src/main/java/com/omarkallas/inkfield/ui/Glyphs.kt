package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Monoline icons drawn in code, on a 24-unit grid. */
enum class Glyph {
    GALLERY, LAYERS, EXPORT, SETTINGS, PAPER, HELP, PLUS, MORE, UNDO, REDO, TRASH, DUPLICATE,
    FLIP_H, FLIP_V, CHECK, CLOSE, EYE, EYE_OFF, LOCK, UNLOCK, SHARE, RESTORE, PALETTE, PEN,
}

@Composable
fun GlyphIcon(g: Glyph, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawGlyph(g, tint) }
}

fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    val u = size.minDimension / 24f
    val st = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
    fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), c, style = st)
    fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.q(x1: Float, y1: Float, x: Float, y: Float) = quadraticTo(x1 * u, y1 * u, x * u, y * u)
    fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float = 2.5f) =
        drawRoundRect(c, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = st)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(c, r * u, p(x, y)) else drawCircle(c, r * u, p(x, y), style = st)

    when (g) {
        Glyph.GALLERY -> { rrect(4f, 4f, 7f, 7f, 1.5f); rrect(13f, 4f, 7f, 7f, 1.5f); rrect(4f, 13f, 7f, 7f, 1.5f); rrect(13f, 13f, 7f, 7f, 1.5f) }
        Glyph.LAYERS -> {
            path { m(12f, 4f); l(21f, 9f); l(12f, 14f); l(3f, 9f); close() }
            path { m(3f, 13.5f); l(12f, 18.5f); l(21f, 13.5f) }
        }
        Glyph.EXPORT -> { path { m(8f, 9f); l(4f, 9f); l(4f, 20f); l(20f, 20f); l(20f, 9f); l(16f, 9f) }; line(12f, 3f, 12f, 14f); path { m(8.5f, 6.5f); l(12f, 3f); l(15.5f, 6.5f) } }
        Glyph.SHARE -> { circle(6f, 12f, 2.5f); circle(18f, 6f, 2.5f); circle(18f, 18f, 2.5f); line(8.2f, 10.8f, 15.8f, 7.2f); line(8.2f, 13.2f, 15.8f, 16.8f) }
        Glyph.SETTINGS -> { line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f); circle(9f, 7f, 2.4f, true); circle(15f, 17f, 2.4f, true) }
        Glyph.PAPER -> { rrect(5f, 3f, 14f, 18f, 2f); line(8.5f, 8f, 15.5f, 8f); line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 13f, 16f) }
        Glyph.HELP -> { circle(12f, 12f, 9f); path { m(9.3f, 9.5f); q(9.5f, 6.8f, 12f, 6.8f); q(14.7f, 6.8f, 14.7f, 9.4f); q(14.7f, 11.2f, 12f, 12.4f); l(12f, 14f) }; circle(12f, 17.3f, 1.1f, true) }
        Glyph.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Glyph.MORE -> { circle(6f, 12f, 1.6f, true); circle(12f, 12f, 1.6f, true); circle(18f, 12f, 1.6f, true) }
        Glyph.UNDO -> { path { m(9f, 5f); l(4.5f, 9.5f); l(9f, 14f) }; path { m(4.5f, 9.5f); l(14f, 9.5f); q(20f, 9.5f, 20f, 14.5f); q(20f, 19.5f, 14f, 19.5f); l(10f, 19.5f) } }
        Glyph.REDO -> { path { m(15f, 5f); l(19.5f, 9.5f); l(15f, 14f) }; path { m(19.5f, 9.5f); l(10f, 9.5f); q(4f, 9.5f, 4f, 14.5f); q(4f, 19.5f, 10f, 19.5f); l(14f, 19.5f) } }
        Glyph.TRASH -> { line(4f, 6.5f, 20f, 6.5f); path { m(9f, 6.5f); l(9.5f, 3.5f); l(14.5f, 3.5f); l(15f, 6.5f) }; path { m(6f, 6.5f); l(7f, 20f); l(17f, 20f); l(18f, 6.5f) } }
        Glyph.DUPLICATE -> { rrect(8f, 8f, 12f, 12f); path { m(16f, 8f); l(16f, 4f); l(4f, 4f); l(4f, 16f); l(8f, 16f) } }
        Glyph.FLIP_H -> {
            drawLine(c, p(12f, 3f), p(12f, 21f), st.width, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * u, 2.5f * u)))
            path { m(9f, 6f); l(3f, 18f); l(9f, 18f); close() }; path { m(15f, 6f); l(21f, 18f); l(15f, 18f); close() }
        }
        Glyph.FLIP_V -> {
            drawLine(c, p(3f, 12f), p(21f, 12f), st.width, StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * u, 2.5f * u)))
            path { m(6f, 9f); l(18f, 3f); l(18f, 9f); close() }; path { m(6f, 15f); l(18f, 21f); l(18f, 15f); close() }
        }
        Glyph.CHECK -> path { m(5f, 12.5f); l(10f, 17.5f); l(19f, 7f) }
        Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.EYE -> { path { m(2.5f, 12f); q(12f, 2f, 21.5f, 12f); q(12f, 22f, 2.5f, 12f) }; circle(12f, 12f, 3f) }
        Glyph.EYE_OFF -> { path { m(2.5f, 12f); q(12f, 2f, 21.5f, 12f); q(12f, 22f, 2.5f, 12f) }; line(4f, 20f, 20f, 4f) }
        Glyph.LOCK -> { rrect(5f, 11f, 14f, 10f); path { m(8f, 11f); l(8f, 8f); q(8f, 4f, 12f, 4f); q(16f, 4f, 16f, 8f); l(16f, 11f) } }
        Glyph.UNLOCK -> { rrect(5f, 11f, 14f, 10f); path { m(8f, 11f); l(8f, 8f); q(8f, 4f, 12f, 4f); q(15f, 4f, 15.8f, 6.5f) } }
        Glyph.RESTORE -> { path { m(4f, 12f); q(4f, 4f, 12f, 4f); q(20f, 4f, 20f, 12f); q(20f, 20f, 12f, 20f); q(7f, 20f, 5f, 16f) }; path { m(2f, 9f); l(4f, 12f); l(7f, 10f) } }
        Glyph.PALETTE -> { circle(12f, 12f, 8.5f); circle(9f, 9f, 1.4f, true); circle(14.5f, 8f, 1.4f, true); circle(16.5f, 12.5f, 1.4f, true); circle(10f, 15f, 1.8f, true) }
        Glyph.PEN -> { path { m(5f, 19f); l(6f, 15f); l(16f, 5f); l(19f, 8f); l(9f, 18f); close() }; line(14f, 7f, 17f, 10f) }
    }
}

/** Unused helper kept small: a rect from two offsets. */
internal fun rectOf(a: Offset, b: Offset) = Rect(a, b)
