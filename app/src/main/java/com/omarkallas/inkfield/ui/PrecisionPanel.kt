package com.omarkallas.inkfield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.engine.Units
import com.omarkallas.inkfield.model.GridType
import com.omarkallas.inkfield.model.GuideType
import com.omarkallas.inkfield.model.LengthUnit
import com.omarkallas.inkfield.model.UnitSystem
import java.util.Locale
import kotlin.math.roundToInt

enum class PrecisionSection { GRID, SNAP, MEASURE, GUIDE, RECOGNITION }

/** Five rows: a checkbox toggles the feature, the label opens its options. */
@Composable
fun PrecisionPanel(
    ctl: EditorController,
    open: PrecisionSection?,
    onOpen: (PrecisionSection?) -> Unit,
    onNewGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ctl.drawing.precision
    Panel(modifier.width(196.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("Precision", fontWeight = FontWeight.SemiBold, color = Ink.text, modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp))
            PrecisionRow("Grid", p.grid.type.label, p.gridOn, open == PrecisionSection.GRID,
                { ctl.updatePrecision { it.copy(gridOn = !it.gridOn) } }, { onOpen(PrecisionSection.GRID) })
            PrecisionRow("Snap", if (p.snap.toGrid) "To grid" else if (p.snap.align) "Align" else "Points", p.snapOn, open == PrecisionSection.SNAP,
                { ctl.updatePrecision { it.copy(snapOn = !it.snapOn) } }, { onOpen(PrecisionSection.SNAP) })
            PrecisionRow("Measure", Units.scaleLabel(p.measure.scale), p.measureOn, open == PrecisionSection.MEASURE,
                { ctl.updatePrecision { it.copy(measureOn = !it.measureOn) } }, { onOpen(PrecisionSection.MEASURE) })
            PrecisionRow("Guide", p.guideType.label, p.guideOn, open == PrecisionSection.GUIDE,
                {
                    if (!p.guideOn && p.guide == null) onNewGuide()
                    else ctl.updatePrecision { it.copy(guideOn = !it.guideOn) }
                }, { onOpen(PrecisionSection.GUIDE) })
            PrecisionRow("Shapes", "Draw + hold", p.recognitionOn, open == PrecisionSection.RECOGNITION,
                { ctl.updatePrecision { it.copy(recognitionOn = !it.recognitionOn) } }, { onOpen(PrecisionSection.RECOGNITION) })
        }
    }
}

@Composable
private fun PrecisionRow(label: String, detail: String, on: Boolean, open: Boolean, onToggle: () -> Unit, onOptions: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (open) Ink.activeFill else Color.Transparent).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (on) Ink.text else Color.Transparent)
                    .border(1.5.dp, if (on) Ink.text else Ink.outline, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) { if (on) GlyphIcon(Glyph.CHECK, Color.White, size = 16.dp) }
        }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOptions).padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(label, color = Ink.text, fontSize = 14.sp)
            Text(detail, color = Ink.muted, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** Options popup for one precision section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrecisionOptions(ctl: EditorController, section: PrecisionSection, onClose: () -> Unit, onEditGrid: () -> Unit, onScaleFromSelection: () -> Unit, onNewGuide: (GuideType) -> Unit) {
    val p = ctl.drawing.precision
    Panel(Modifier.width(320.dp)) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (section) {
                        PrecisionSection.GRID -> "Grid"; PrecisionSection.SNAP -> "Snap"; PrecisionSection.MEASURE -> "Measure"
                        PrecisionSection.GUIDE -> "Shape guide"; PrecisionSection.RECOGNITION -> "Shape recognition"
                    },
                    fontWeight = FontWeight.SemiBold, color = Ink.text,
                )
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.CLOSE, onClose, size = 32.dp)
            }
            when (section) {
                PrecisionSection.GRID -> {
                    val g = p.grid
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GridType.entries.forEach { t ->
                            Chip(t.label, g.type == t) { ctl.updatePrecision { it.copy(gridOn = true, grid = it.grid.copy(type = t)) } }
                        }
                    }
                    if (!g.type.perspective) {
                        LabeledSlider("Spacing ${Units.length(g.spacing, p.measure)}", g.spacing, 6f..200f) { v ->
                            ctl.updatePrecision { it.copy(grid = it.grid.copy(spacing = v)) }
                        }
                    } else {
                        LabeledSlider("Rays per point ${g.density}", g.density.toFloat(), 8f..120f) { v ->
                            ctl.updatePrecision { it.copy(grid = it.grid.copy(density = v.roundToInt())) }
                        }
                    }
                    if (g.type == GridType.GRAPH) {
                        LabeledSlider("Divisions ${g.divisions}", g.divisions.toFloat(), 1f..10f) { v ->
                            ctl.updatePrecision { it.copy(grid = it.grid.copy(divisions = v.roundToInt().coerceAtLeast(1))) }
                        }
                    }
                    LabeledSlider("Line weight ${String.format(Locale.US, "%.1f", g.lineWeight)}", g.lineWeight, 0.3f..3f) { v ->
                        ctl.updatePrecision { it.copy(grid = it.grid.copy(lineWeight = v)) }
                    }
                    LabeledSlider("Opacity ${(g.opacity * 100).roundToInt()}%", g.opacity, 0.05f..1f) { v ->
                        ctl.updatePrecision { it.copy(grid = it.grid.copy(opacity = v)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Auto color", g.color == null) { ctl.updatePrecision { it.copy(grid = it.grid.copy(color = null)) } }
                        Chip("Current color", g.color != null) { ctl.updatePrecision { it.copy(grid = it.grid.copy(color = ctl.color)) } }
                    }
                    if (g.type == GridType.LINED || g.type == GridType.ISOMETRIC || g.type == GridType.TRIANGLE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("Landscape", !g.portrait) { ctl.updatePrecision { it.copy(grid = it.grid.copy(portrait = false)) } }
                            Chip("Portrait", g.portrait) { ctl.updatePrecision { it.copy(grid = it.grid.copy(portrait = true)) } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(if (g.type.perspective) "Move vanishing points" else "Move / rotate on canvas", false) { onEditGrid() }
                        Chip("Reset", false) {
                            ctl.updatePrecision { it.copy(grid = com.omarkallas.inkfield.model.GridSettings(type = g.type)) }
                        }
                    }
                }
                PrecisionSection.SNAP -> {
                    val s = p.snap
                    Text("While drawing", color = Ink.muted, fontSize = 12.sp)
                    SwitchRow("Snap to grid", s.toGrid) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(toGrid = v, align = if (v) false else it.snap.align)) } }
                    SwitchRow("Align to grid (direction only)", s.align) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(align = v, toGrid = if (v) false else it.snap.toGrid)) } }
                    SwitchRow("Allow turns", s.allowTurns) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(allowTurns = v)) } }
                    SwitchRow("Auto-complete to nearby ends", s.autoComplete) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(autoComplete = v)) } }
                    Text("While editing", color = Ink.muted, fontSize = 12.sp)
                    SwitchRow("Snap to key points", s.keyPoints) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(keyPoints = v)) } }
                    SwitchRow("Active layer only", s.activeLayerOnly) { v -> ctl.updatePrecision { it.copy(snap = it.snap.copy(activeLayerOnly = v)) } }
                    if (!p.gridOn) Text("Turn the grid on to snap strokes to it.", color = Ink.muted, fontSize = 12.sp)
                }
                PrecisionSection.MEASURE -> {
                    val m = p.measure
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(UnitSystem.METRIC to "Metric", UnitSystem.IMPERIAL to "Imperial", UnitSystem.DIGITAL to "Digital").forEach { (sys, label) ->
                            Chip(label, m.system == sys) { ctl.updatePrecision { it.copy(measure = it.measure.copy(system = sys, unit = null)) } }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.system != UnitSystem.DIGITAL) Chip("Auto", m.unit == null) { ctl.updatePrecision { it.copy(measure = it.measure.copy(unit = null)) } }
                        Units.unitsFor(m.system).forEach { u ->
                            Chip(u.label, m.unit == u) { ctl.updatePrecision { it.copy(measure = it.measure.copy(unit = u)) } }
                        }
                    }
                    Text("Scale ${Units.scaleLabel(m.scale)}", color = Ink.text, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Units.scalePresets.forEach { (label, n) ->
                            Chip(label, kotlin.math.abs(m.scale - n) < 1e-6) { ctl.updatePrecision { it.copy(measure = it.measure.copy(scale = n)) } }
                        }
                    }
                    Chip("Set scale from selected line…", false) { onScaleFromSelection() }
                    LabeledSlider("Decimals ${m.decimals}", m.decimals.toFloat(), 0f..3f) { v ->
                        ctl.updatePrecision { it.copy(measure = it.measure.copy(decimals = v.roundToInt())) }
                    }
                    if (m.system == UnitSystem.IMPERIAL) {
                        SwitchRow("Fractions (1/16)", m.fractional) { v -> ctl.updatePrecision { it.copy(measure = it.measure.copy(fractional = v)) } }
                    }
                    Text("Strokes drawn with Measure on show their length. Hold a number in the status bar to type exact sizes.", color = Ink.muted, fontSize = 12.sp)
                }
                PrecisionSection.GUIDE -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GuideType.entries.forEach { t ->
                            Chip(t.label, p.guideType == t && p.guideOn) { onNewGuide(t) }
                        }
                    }
                    Text(
                        "Drag the handles to shape the guide and the cross to move it. Double-tap the cross for a perfect " +
                            "square, circle, half circle or right angle. Anything you draw follows the guide.",
                        color = Ink.muted, fontSize = 12.sp,
                    )
                    Chip("Bring guide to view", false) { onNewGuide(p.guideType) }
                }
                PrecisionSection.RECOGNITION -> {
                    LabeledSlider("Hold for ${p.recognitionMs} ms", p.recognitionMs.toFloat(), 250f..1500f) { v ->
                        ctl.updatePrecision { it.copy(recognitionMs = v.roundToInt()) }
                    }
                    Text(
                        "Draw a line, arc, triangle, rectangle or circle and keep the pen still at the end. " +
                            "It becomes a clean shape; keep moving to scale and rotate it before lifting.",
                        color = Ink.muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Ink.text, fontSize = 13.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Ink.text, activeTrackColor = Ink.text),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Ink.text, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Typing exact sizes for the selection: width, height and rotation. */
@Composable
fun MeasurementDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val m = ctl.drawing.precision.measure
    val b = ctl.selectionBounds() ?: run { onDismiss(); return }
    val unit = Units.pick(maxOf(b.width(), b.height()).toDouble() * m.scale, m)
    fun fmt(v: Float) = String.format(Locale.US, "%.${m.decimals.coerceAtLeast(1)}f", v.toDouble() * m.scale / unit.points)
    var w by remember { mutableStateOf(fmt(b.width())) }
    var h by remember { mutableStateOf(fmt(b.height())) }
    var rot by remember { mutableStateOf("0") }
    var lock by remember { mutableStateOf(true) }
    val aspect = b.height() / b.width().coerceAtLeast(1e-3f)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Size and rotation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(w, { t ->
                    w = t
                    if (lock) Units.parse(t, m, unit)?.let { cw -> h = fmt(cw * aspect) }
                }, label = { Text("Width (${unit.label})") }, singleLine = true)
                OutlinedTextField(h, { t ->
                    h = t
                    if (lock) Units.parse(t, m, unit)?.let { ch -> w = fmt(ch / aspect.coerceAtLeast(1e-6f)) }
                }, label = { Text("Height (${unit.label})") }, singleLine = true)
                SwitchRow("Keep proportions", lock) { lock = it }
                OutlinedTextField(rot, { rot = it }, label = { Text("Rotate by (°, clockwise)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cw = Units.parse(w, m, unit); val ch = Units.parse(h, m, unit)
                if (cw != null && ch != null && cw > 0f && ch > 0f &&
                    (kotlin.math.abs(cw - b.width()) > 1e-3f || kotlin.math.abs(ch - b.height()) > 1e-3f)
                ) ctl.resizeSelection(cw, ch)
                rot.replace(",", ".").toFloatOrNull()?.let { if (it != 0f) ctl.rotateSelection(it) }
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "This line is ___ long": sets the drawing scale from one selected stroke. */
@Composable
fun ScaleFromSelectionDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val m = ctl.drawing.precision.measure
    val defaultUnit = m.unit ?: when (m.system) { UnitSystem.METRIC -> LengthUnit.M; UnitSystem.IMPERIAL -> LengthUnit.FT; UnitSystem.DIGITAL -> LengthUnit.PT }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val single = ctl.selection.size == 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set scale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!single) {
                    Text("Select exactly one stroke first (for example a line drawn along a known distance on an imported plan), then come back here.")
                } else {
                    Text("How long is the selected line in reality?")
                    OutlinedTextField(text, { text = it; error = null }, label = { Text("Length (e.g. 7.5 ${defaultUnit.label})") }, singleLine = true)
                    error?.let { Text(it, color = Ink.danger, fontSize = 12.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!single) { onDismiss(); return@TextButton }
                // Parse as a real length (scale 1) in points.
                val real = Units.parse(text, m.copy(scale = 1.0), defaultUnit)
                if (real == null || real <= 0f) { error = "Type a length like 7.5 ${defaultUnit.label}"; return@TextButton }
                if (ctl.setScaleFromSelection(real.toDouble())) onDismiss() else error = "That stroke has no length."
            }) { Text(if (single) "Set scale" else "OK") }
        },
        dismissButton = { if (single) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
