package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.editor.PropKind
import com.omarkallas.inkfield.model.ToolType
import kotlin.math.roundToInt

/** The tool wheel laid out as a single bar, with the same functions. */
@Composable
fun ToolBar(
    ctl: EditorController,
    onOpenProp: (PropKind) -> Unit,
    onOpenColor: () -> Unit,
    onOpenBrushMenu: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(Glyph.UNDO, { ctl.undo() }, enabled = ctl.canUndo, contentDescription = "Undo")
            ctl.slots.forEachIndexed { i, slot ->
                val active = i == ctl.activeSlot
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) Ink.activeFill else Color.Transparent)
                        .clickable { if (active) onOpenBrushMenu(i) else ctl.selectSlot(i) }
                        .semantics { contentDescription = "Tool slot ${i + 1}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(34.dp)) { drawSlotGlyph(slot, Offset(size.width / 2, size.height / 2), size.width * 0.42f, Color(ctl.color)) }
                }
            }
            Spacer(Modifier.width(6.dp))
            val s = ctl.slot
            val isBrush = s.tool == ToolType.BRUSH
            BarValue("size", formatProp(PropKind.SIZE, s.size, s.tool)) { onOpenProp(PropKind.SIZE) }
            if (isBrush) {
                BarValue("opacity", "${(s.opacity * 100).roundToInt()}%") { onOpenProp(PropKind.OPACITY) }
                BarValue("smooth", "${(s.smoothing * 100).roundToInt()}%") { onOpenProp(PropKind.SMOOTHING) }
            }
            Box(
                Modifier.padding(horizontal = 6.dp).size(34.dp).clip(CircleShape).background(Color(ctl.color))
                    .border(1.dp, Ink.outline, CircleShape).clickable(onClick = onOpenColor)
                    .semantics { contentDescription = "Color" },
            )
            GlyphButton(Glyph.REDO, { ctl.redo() }, enabled = ctl.canRedo, contentDescription = "Redo")
        }
    }
}

@Composable
private fun BarValue(caption: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.text)
        Text(caption, fontSize = 9.sp, color = Ink.muted, modifier = Modifier.height(12.dp))
    }
}
