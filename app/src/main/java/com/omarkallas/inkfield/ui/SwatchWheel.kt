package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.omarkallas.inkfield.editor.EditorController
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** Ring geometry in dp, measured from the wheel center. */
private object Rings {
    const val HOLE = 112f
    const val TONES_IN = 114f; const val TONES_OUT = 146f
    const val GRAYS_IN = 148f; const val GRAYS_OUT = 184f
    const val FAM_IN = 188f; const val FAM_STEP = 33f
    const val GAP_DEG = 0.5f
}

/**
 * A large spinning ring of swatches centered on the tool wheel. Drag anywhere
 * to spin it, tap a swatch to use it, tap the middle or outside to close.
 */
@Composable
fun SwatchWheel(
    ctl: EditorController,
    center: Offset,
    onClose: () -> Unit,
    onMode: (String) -> Unit,
    onEyedropper: () -> Unit,
) {
    val density = LocalDensity.current
    var rotation by remember { mutableFloatStateOf(ctl.prefs.swatchRotation) }
    val measurer = rememberTextMeasurer()
    val labels = remember {
        val cache = HashMap<String, TextLayoutResult>()
        val all = SwatchSet.tones + SwatchSet.grays + SwatchSet.families.flatMap { it.steps }
        for (s in all) {
            val dark = ColorUtils.calculateLuminance(s.color) < 0.45
            cache[s.name] = measurer.measure(
                s.name,
                TextStyle(fontSize = 7.5.sp, color = if (dark) Color.White.copy(alpha = 0.9f) else Color(0xCC1B1B1F)),
            )
        }
        cache
    }
    val px = with(density) { 1.dp.toPx() }
    val fams = SwatchSet.families

    fun hit(p: Offset): Int? {
        val d = hypot(p.x - center.x, p.y - center.y) / px
        val a = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()
        val rel = ((a + 90f - rotation) % 360f + 720f) % 360f
        return when {
            d < Rings.HOLE -> null
            d < Rings.TONES_OUT -> SwatchSet.tones[(floor(rel / (360f / SwatchSet.tones.size)).toInt()).coerceIn(0, SwatchSet.tones.size - 1)].color
            d < Rings.GRAYS_OUT -> SwatchSet.grays[(floor(rel / (360f / SwatchSet.grays.size)).toInt()).coerceIn(0, SwatchSet.grays.size - 1)].color
            d < Rings.FAM_IN + Rings.FAM_STEP * 6 -> {
                val col = floor(rel / (360f / fams.size)).toInt().coerceIn(0, fams.size - 1)
                val step = floor((d - Rings.FAM_IN) / Rings.FAM_STEP).toInt().coerceIn(0, 5)
                fams[col].steps[step].color
            }
            else -> null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(center) {
                    detectDragGestures(
                        onDragEnd = { ctl.prefs.swatchRotation = rotation },
                        onDrag = { ch, _ ->
                            ch.consume()
                            val a0 = atan2(ch.previousPosition.y - center.y, ch.previousPosition.x - center.x)
                            val a1 = atan2(ch.position.y - center.y, ch.position.x - center.x)
                            var dd = Math.toDegrees((a1 - a0).toDouble()).toFloat()
                            if (dd > 180f) dd -= 360f
                            if (dd < -180f) dd += 360f
                            rotation = (rotation + dd) % 360f
                        },
                    )
                }
                .pointerInput(center) {
                    detectTapGestures { p ->
                        val c = hit(p)
                        if (c == null) onClose() else ctl.setColor(c, applyToSelection = true)
                    }
                },
        ) {
            val current = ctl.color
            // Shade the hole edge so the wheel reads as a separate layer.
            drawCircle(Color.Black.copy(alpha = 0.06f), (Rings.FAM_IN + Rings.FAM_STEP * 6 + 8f) * px, center)
            ring(SwatchSet.tones, Rings.TONES_IN, Rings.TONES_OUT, rotation, center, px, labels, current)
            ring(SwatchSet.grays, Rings.GRAYS_IN, Rings.GRAYS_OUT, rotation, center, px, labels, current)
            val n = fams.size
            for (j in 0 until n) {
                for (k in 0 until 6) {
                    val r0 = Rings.FAM_IN + k * Rings.FAM_STEP
                    cell(fams[j].steps[k], r0, r0 + Rings.FAM_STEP - 1.5f, rotation + j * 360f / n, 360f / n, center, px, labels, current)
                }
            }
        }
        // Mode bar
        Panel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Swatches", true) {}
                Spacer(Modifier.width(6.dp))
                Chip("HSV", false) { onMode("hsv") }
                Spacer(Modifier.width(6.dp))
                Chip("RGB", false) { onMode("rgb") }
                Spacer(Modifier.width(6.dp))
                GlyphButton(Glyph.DROPPER, onEyedropper, size = 36.dp, contentDescription = "Pick a color from the canvas")
                GlyphButton(Glyph.CHECK, onClose, size = 36.dp, contentDescription = "Done")
            }
        }
    }
}

private fun DrawScope.ring(
    list: List<Swatch>, rIn: Float, rOut: Float, rotation: Float, center: Offset, px: Float,
    labels: Map<String, TextLayoutResult>, current: Int,
) {
    val sweep = 360f / list.size
    list.forEachIndexed { i, s -> cell(s, rIn, rOut, rotation + i * sweep, sweep, center, px, labels, current) }
}

/** One swatch cell: an annular sector starting at [start] degrees from the top. */
private fun DrawScope.cell(
    s: Swatch, rIn: Float, rOut: Float, start: Float, sweep: Float, center: Offset, px: Float,
    labels: Map<String, TextLayoutResult>, current: Int,
) {
    val rm = (rIn + rOut) / 2f * px
    val w = (rOut - rIn) * px
    val a0 = start - 90f + Rings.GAP_DEG / 2f
    val sw = sweep - Rings.GAP_DEG
    drawArc(
        Color(s.color), a0, sw, false,
        topLeft = Offset(center.x - rm, center.y - rm), size = Size(rm * 2, rm * 2),
        style = Stroke(width = w),
    )
    val mid = Math.toRadians((a0 + sw / 2f).toDouble())
    val p = Offset(center.x + (rm * cos(mid)).toFloat(), center.y + (rm * sin(mid)).toFloat())
    if (s.color == current) {
        drawCircle(Color.White, 7f * px, p, style = Stroke(2.5f * px))
        drawCircle(Color.Black.copy(alpha = 0.5f), 8.5f * px, p, style = Stroke(1f * px))
        return
    }
    // Only label cells wide enough to hold the text.
    val t = labels[s.name] ?: return
    val arcLen = rm * Math.toRadians(sw.toDouble()).toFloat()
    if (arcLen < t.size.width + 4f * px || w < t.size.height) return
    drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y - t.size.height / 2f))
}
