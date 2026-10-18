package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.editor.PropKind
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.ToolType
import com.omarkallas.inkfield.prefs.ToolSlot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

val WHEEL_SIZE = 196.dp

/**
 * The Tool Wheel: outer ring of 8 tool slots, middle ring with Size, Opacity
 * and Smoothing, and a center disc showing the color. Undo/redo sit beside it.
 * Long-press and drag anywhere on it to move it.
 */
@Composable
fun ToolWheel(
    ctl: EditorController,
    onOpenProp: (PropKind) -> Unit,
    onOpenColor: () -> Unit,
    onOpenBrushMenu: (Int) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Panel { GlyphButton(Glyph.UNDO, { ctl.undo() }, enabled = ctl.canUndo, size = 44.dp, contentDescription = "Undo") }
        Spacer(Modifier.width(6.dp))
        Canvas(
            Modifier
                .size(WHEEL_SIZE)
                .semantics { contentDescription = "Tool wheel" }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount -> change.consume(); onDrag(amount) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val r = hypot(pos.x - c.x, pos.y - c.y) / (size.width / 2f)
                        val ang = Math.toDegrees(atan2((pos.y - c.y).toDouble(), (pos.x - c.x).toDouble())).toFloat()
                        when {
                            r < 0.36f -> onOpenColor()
                            r < 0.63f -> {
                                val a = (ang + 360f) % 360f
                                val kind = when {
                                    a >= 210f && a < 330f -> PropKind.SIZE
                                    a >= 330f || a < 90f -> PropKind.OPACITY
                                    else -> PropKind.SMOOTHING
                                }
                                onOpenProp(kind)
                            }
                            r <= 1.02f -> {
                                val idx = (((ang + 90f + 22.5f + 720f) % 360f) / 45f).toInt().coerceIn(0, 7)
                                if (idx == ctl.activeSlot) onOpenBrushMenu(idx) else ctl.selectSlot(idx)
                            }
                        }
                    }
                },
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val R = size.width / 2f
            // Outer ring
            drawCircle(Ink.panel, R, c)
            drawCircle(Ink.outline, R - 0.5f, c, style = Stroke(1.dp.toPx()))
            val slots = ctl.slots
            for (i in 0 until 8) {
                val mid = -90f + i * 45f
                if (i == ctl.activeSlot) {
                    drawArc(
                        Ink.activeFill, mid - 22.5f, 45f, true,
                        topLeft = Offset(c.x - R + 2f, c.y - R + 2f), size = Size((R - 2f) * 2, (R - 2f) * 2),
                    )
                }
                val rad = Math.toRadians(mid.toDouble())
                val gr = R * 0.815f
                val gc = Offset(c.x + (gr * cos(rad)).toFloat(), c.y + (gr * sin(rad)).toFloat())
                drawSlotGlyph(slots[i], gc, R * 0.15f, Color(ctl.color))
            }
            // separators
            for (i in 0 until 8) {
                val a = Math.toRadians((-90f + i * 45f + 22.5f).toDouble())
                drawLine(
                    Ink.outline,
                    Offset(c.x + (R * 0.64f * cos(a)).toFloat(), c.y + (R * 0.64f * sin(a)).toFloat()),
                    Offset(c.x + (R * 0.99f * cos(a)).toFloat(), c.y + (R * 0.99f * sin(a)).toFloat()),
                    1.dp.toPx(),
                )
            }
            // Middle ring
            val mr = R * 0.63f
            drawCircle(Color(0xFFF0EEEA), mr, c)
            drawCircle(Ink.outline, mr, c, style = Stroke(1.dp.toPx()))
            for (deg in listOf(-30f, 90f, 210f)) {
                val a = Math.toRadians(deg.toDouble())
                drawLine(
                    Ink.outline,
                    Offset(c.x + (R * 0.36f * cos(a)).toFloat(), c.y + (R * 0.36f * sin(a)).toFloat()),
                    Offset(c.x + (mr * cos(a)).toFloat(), c.y + (mr * sin(a)).toFloat()),
                    1.dp.toPx(),
                )
            }
            val slot = ctl.slot
            val isBrush = slot.tool == ToolType.BRUSH
            val sizeText = if (slot.size >= 10f) slot.size.roundToInt().toString() else "%.1f".format(slot.size)
            val labels = listOf(
                Triple(-90f, sizeText, "size"),
                Triple(30f, if (isBrush) "${(slot.opacity * 100).roundToInt()}%" else "–", "opacity"),
                Triple(150f, if (isBrush) "${(slot.smoothing * 100).roundToInt()}%" else "–", "smooth"),
            )
            for ((deg, value, caption) in labels) {
                val a = Math.toRadians(deg.toDouble())
                val rr = R * 0.5f
                val p = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
                val v = measurer.measure(value, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text))
                val cap = measurer.measure(caption, TextStyle(fontSize = 8.sp, color = Ink.muted))
                drawText(v, topLeft = Offset(p.x - v.size.width / 2f, p.y - v.size.height / 2f - 4.dp.toPx()))
                drawText(cap, topLeft = Offset(p.x - cap.size.width / 2f, p.y + v.size.height / 2f - 5.dp.toPx()))
            }
            // Center disc: current color over a checker for opacity
            val cr = R * 0.33f
            drawCircle(Color.White, cr, c)
            drawCircle(Color(ctl.color).copy(alpha = if (isBrush) slot.opacity.coerceAtLeast(0.15f) else 1f), cr, c)
            drawCircle(Ink.outline, cr, c, style = Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.width(6.dp))
        Panel { GlyphButton(Glyph.REDO, { ctl.redo() }, enabled = ctl.canRedo, size = 44.dp, contentDescription = "Redo") }
    }
}

/** Small preview of a slot's brush or tool, drawn around [c] within radius [r]. */
fun DrawScope.drawSlotGlyph(slot: ToolSlot, c: Offset, r: Float, color: Color) {
    val ink = Ink.text
    when (slot.tool) {
        ToolType.BRUSH -> drawBrushSample(slot.brush, c, r, color)
        ToolType.SLICE -> {
            val st = Stroke(r * 0.16f, cap = StrokeCap.Round)
            drawCircle(ink, r * 0.62f, c, style = st)
            drawLine(ink, Offset(c.x - r * 0.9f, c.y + r * 0.9f), Offset(c.x + r * 0.9f, c.y - r * 0.9f), r * 0.16f, StrokeCap.Round)
        }
        ToolType.SELECT -> {
            val p = Path().apply {
                moveTo(c.x - r * 0.8f, c.y)
                quadraticTo(c.x - r * 0.8f, c.y - r * 0.85f, c.x + r * 0.1f, c.y - r * 0.8f)
                quadraticTo(c.x + r * 0.95f, c.y - r * 0.7f, c.x + r * 0.8f, c.y + r * 0.1f)
                quadraticTo(c.x + r * 0.6f, c.y + r * 0.85f, c.x - r * 0.2f, c.y + r * 0.7f)
                quadraticTo(c.x - r * 0.85f, c.y + r * 0.55f, c.x - r * 0.8f, c.y)
            }
            drawPath(p, ink, style = Stroke(r * 0.14f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.3f, r * 0.22f))))
        }
        ToolType.EYEDROPPER -> {
            val w = r * 0.14f
            drawLine(ink, Offset(c.x - r * 0.75f, c.y + r * 0.75f), Offset(c.x + r * 0.35f, c.y - r * 0.35f), w * 1.6f, StrokeCap.Round)
            drawCircle(ink, r * 0.32f, Offset(c.x + r * 0.52f, c.y - r * 0.52f))
            drawCircle(color, r * 0.18f, Offset(c.x - r * 0.75f, c.y + r * 0.75f))
        }
        ToolType.PAN -> {
            val w = r * 0.14f
            drawLine(ink, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w, StrokeCap.Round)
            drawLine(ink, Offset(c.x, c.y - r), Offset(c.x, c.y + r), w, StrokeCap.Round)
            for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                val tip = Offset(c.x + dx * r, c.y + dy * r)
                drawLine(ink, tip, Offset(tip.x - dx * r * 0.35f + dy * r * 0.3f, tip.y - dy * r * 0.35f + dx * r * 0.3f), w, StrokeCap.Round)
                drawLine(ink, tip, Offset(tip.x - dx * r * 0.35f - dy * r * 0.3f, tip.y - dy * r * 0.35f - dx * r * 0.3f), w, StrokeCap.Round)
            }
        }
    }
}

/** A short wave drawn the way each brush looks. */
fun DrawScope.drawBrushSample(b: BrushType, c: Offset, r: Float, color: Color) {
    val n = 24
    val pts = List(n + 1) { i ->
        val u = i / n.toFloat()
        Offset(c.x - r + 2 * r * u, c.y + (sin(u * Math.PI * 2).toFloat()) * r * 0.45f)
    }
    fun widthAt(u: Float): Float = when (b) {
        BrushType.PEN -> r * (0.10f + 0.08f * sin(u * Math.PI).toFloat())
        BrushType.FOUNTAIN -> r * (0.06f + 0.16f * kotlin.math.abs(cos(u * Math.PI * 2)).toFloat())
        BrushType.DYNAMIC -> r * (0.04f + 0.28f * sin(u * Math.PI).toFloat())
        BrushType.FIXED -> r * 0.12f
        BrushType.WIRE -> r * 0.05f
        BrushType.MARKER -> r * 0.42f
        BrushType.AIRBRUSH -> r * 0.5f
    }
    val alpha = when (b) { BrushType.MARKER -> 0.55f; BrushType.AIRBRUSH -> 0.12f; else -> 1f }
    if (b == BrushType.AIRBRUSH) {
        for (k in 0..2) for (i in 0..n step 2) drawCircle(color.copy(alpha = alpha), widthAt(0f) * (1f - k * 0.25f), pts[i])
        return
    }
    for (i in 1..n) {
        val u = i / n.toFloat()
        drawLine(
            color.copy(alpha = alpha), pts[i - 1], pts[i], widthAt(u) * 2f,
            if (b == BrushType.MARKER) StrokeCap.Butt else StrokeCap.Round,
        )
    }
}

// ----------------------------------------------------------------------
// Property popup: slider plus four presets
// ----------------------------------------------------------------------

private fun toSlider(kind: PropKind, v: Float, tool: ToolType): Float = when (kind) {
    PropKind.SIZE -> {
        val (lo, hi) = sizeRange(tool)
        ((ln(v) - ln(lo)) / (ln(hi) - ln(lo))).coerceIn(0f, 1f)
    }
    else -> v
}

private fun fromSlider(kind: PropKind, s: Float, tool: ToolType): Float = when (kind) {
    PropKind.SIZE -> {
        val (lo, hi) = sizeRange(tool)
        exp(ln(lo) + (ln(hi) - ln(lo)) * s)
    }
    else -> s
}

private fun sizeRange(tool: ToolType) = if (tool == ToolType.SLICE) 4f to 240f else 0.3f to 300f

fun formatProp(kind: PropKind, v: Float, tool: ToolType): String = when (kind) {
    PropKind.SIZE -> (if (v >= 10f) v.roundToInt().toString() else "%.1f".format(v)) + if (tool == ToolType.SLICE) " px" else " pt"
    else -> "${(v * 100).roundToInt()}%"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PropPanel(ctl: EditorController, kind: PropKind, onClose: () -> Unit) {
    val slot = ctl.slot
    val tool = slot.tool
    val current = when (kind) { PropKind.SIZE -> slot.size; PropKind.OPACITY -> slot.opacity; PropKind.SMOOTHING -> slot.smoothing }
    val presets = when (kind) { PropKind.SIZE -> slot.sizePresets; PropKind.OPACITY -> slot.opacityPresets; PropKind.SMOOTHING -> slot.smoothingPresets }
    var sliderPos by remember(kind, ctl.activeSlot) { mutableFloatStateOf(toSlider(kind, current, tool)) }
    val title = when (kind) { PropKind.SIZE -> "Size"; PropKind.OPACITY -> "Opacity"; PropKind.SMOOTHING -> "Smoothing" }
    Panel(Modifier.width(300.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Ink.text)
                Spacer(Modifier.weight(1f))
                Text(formatProp(kind, fromSlider(kind, sliderPos, tool), tool), color = Ink.text)
                GlyphButton(Glyph.CLOSE, onClose, size = 32.dp)
            }
            if (tool != ToolType.BRUSH && kind != PropKind.SIZE) {
                Text("Only brushes use this setting.", color = Ink.muted, fontSize = 13.sp)
                return@Column
            }
            Slider(
                value = sliderPos,
                onValueChange = {
                    sliderPos = it
                    ctl.setProp(kind, fromSlider(kind, it, tool), applyToSelection = false)
                },
                onValueChangeFinished = { ctl.setProp(kind, fromSlider(kind, sliderPos, tool), applyToSelection = true) },
                colors = SliderDefaults.colors(thumbColor = Ink.text, activeTrackColor = Ink.text),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEachIndexed { i, v ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ink.activeFill)
                            .combinedClickable(
                                onClick = {
                                    sliderPos = toSlider(kind, v, tool)
                                    ctl.setProp(kind, v)
                                },
                                onLongClick = { ctl.setPreset(kind, i, fromSlider(kind, sliderPos, tool)) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text(formatProp(kind, v, tool), fontSize = 12.sp, color = Ink.text) }
                }
            }
            Text("Tap a preset to use it. Hold to save the current value.", color = Ink.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ----------------------------------------------------------------------
// Brushes menu for a slot
// ----------------------------------------------------------------------

@Composable
fun BrushMenu(ctl: EditorController, slotIndex: Int, onClose: () -> Unit) {
    val slot = ctl.slots[slotIndex]
    Panel(Modifier.width(260.dp)) {
        Column(Modifier.padding(vertical = 10.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Brushes", fontWeight = FontWeight.SemiBold, color = Ink.text)
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.CLOSE, onClose, size = 32.dp)
            }
            BrushType.entries.forEach { b ->
                val active = slot.tool == ToolType.BRUSH && slot.brush == b
                MenuRow(active, { ctl.setSlotBrush(slotIndex, ToolType.BRUSH, b); onClose() }) {
                    Canvas(Modifier.size(44.dp, 26.dp)) { drawBrushSample(b, Offset(size.width / 2, size.height / 2), size.width * 0.42f, Color(ctl.color)) }
                    Spacer(Modifier.width(12.dp))
                    Text(b.label, color = Ink.text)
                }
            }
            Text("Tools", color = Ink.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
            listOf(ToolType.SLICE to "Slice (eraser)", ToolType.SELECT to "Select (lasso)", ToolType.EYEDROPPER to "Color picker", ToolType.PAN to "Pan").forEach { (t, label) ->
                MenuRow(slot.tool == t, {
                    if (slot.tool != t) ctlSetTool(ctl, slotIndex, t)
                    onClose()
                }) {
                    Canvas(Modifier.size(44.dp, 26.dp)) { drawSlotGlyph(ToolSlot.tool(t), Offset(size.width / 2, size.height / 2), size.height * 0.4f, Ink.text) }
                    Spacer(Modifier.width(12.dp))
                    Text(label, color = Ink.text)
                }
            }
        }
    }
}

private fun ctlSetTool(ctl: EditorController, i: Int, t: ToolType) = ctl.setSlotTool(i, t)

@Composable
fun MenuRow(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (active) Ink.activeFill else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Ink.text else Ink.activeFill)
            .border(1.dp, if (selected) Ink.text else Ink.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, fontSize = 13.sp, color = if (selected) Color.White else Ink.text) }
}
