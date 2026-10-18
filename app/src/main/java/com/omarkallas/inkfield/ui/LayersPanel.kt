package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.engine.RenderOptions
import com.omarkallas.inkfield.engine.Renderer
import com.omarkallas.inkfield.model.Layer

/** Layer list, top layer first. */
@Composable
fun LayersPanel(ctl: EditorController, onRename: (Layer) -> Unit, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val d = ctl.drawing
    Panel(modifier.width(220.dp)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Layers", fontWeight = FontWeight.SemiBold, color = Ink.text,
                    modifier = Modifier.combinedClickableCompat(onClick = onCollapse),
                )
                Spacer(Modifier.width(8.dp))
                Chip(if (d.autoLayers) "Auto" else "Manual", false) { ctl.setAutoLayers(!d.autoLayers) }
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.PLUS, { ctl.addLayer() }, size = 34.dp, contentDescription = "New layer")
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(d.layers.asReversed(), key = { it.id }) { layer ->
                    LayerRow(ctl, layer, layer.id == d.activeLayerId, onRename)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRow(ctl: EditorController, layer: Layer, active: Boolean, onRename: (Layer) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (active) Ink.activeFill else Color.Transparent)
                .combinedClickable(
                    onClick = { if (active) menu = true else ctl.setActiveLayer(layer.id) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LayerThumb(ctl, layer, active)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(layer.name, color = Ink.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val extra = buildList {
                    if (layer.locked) add("locked")
                    if (layer.opacity < 0.999f) add("${(layer.opacity * 100).toInt()}%")
                }
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), color = Ink.muted, fontSize = 11.sp)
            }
            GlyphButton(
                if (layer.visible) Glyph.EYE else Glyph.EYE_OFF, { ctl.toggleLayerVisible(layer.id) }, size = 32.dp,
                tint = if (layer.visible) Ink.text else Ink.muted, contentDescription = "Toggle visibility",
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            var op by remember { mutableFloatStateOf(layer.opacity) }
            Column(Modifier.padding(horizontal = 14.dp).width(200.dp)) {
                Text("Opacity ${(op * 100).toInt()}%", fontSize = 12.sp, color = Ink.muted)
                Slider(
                    value = op,
                    onValueChange = { op = it; ctl.setLayerOpacity(layer.id, it, final = false) },
                    onValueChangeFinished = { ctl.setLayerOpacity(layer.id, op, final = true) },
                    colors = SliderDefaults.colors(thumbColor = Ink.text, activeTrackColor = Ink.text),
                )
            }
            DropdownMenuItem(text = { Text("Select all") }, onClick = { menu = false; ctl.selectAllInLayer(layer.id) })
            DropdownMenuItem(text = { Text(if (layer.locked) "Unlock" else "Lock") }, onClick = { menu = false; ctl.toggleLayerLock(layer.id) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename(layer) })
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; ctl.duplicateLayer(layer.id) })
            DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; ctl.moveLayer(layer.id, up = true) })
            DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; ctl.moveLayer(layer.id, up = false) })
            DropdownMenuItem(text = { Text("Merge down") }, onClick = { menu = false; ctl.mergeDown(layer.id) })
            DropdownMenuItem(text = { Text("Clear") }, onClick = { menu = false; ctl.clearLayer(layer.id) })
            DropdownMenuItem(text = { Text("Delete", color = Ink.danger) }, onClick = { menu = false; ctl.deleteLayer(layer.id) })
        }
    }
}

@Composable
private fun LayerThumb(ctl: EditorController, layer: Layer, active: Boolean) {
    val d = ctl.drawing
    Canvas(
        Modifier
            .size(44.dp, 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(d.background))
            .border(if (active) 2.dp else 1.dp, if (active) Ink.text else Ink.outline, RoundedCornerShape(6.dp)),
    ) {
        val only = d.copy(strokes = d.strokes.filter { it.layerId == layer.id }, layers = listOf(layer.copy(visible = true, opacity = 1f)))
        val b = Renderer.contentBounds(only, ctl.cache, onlyVisible = false) ?: return@Canvas
        val pad = 3.dp.toPx()
        val k = minOf((size.width - pad * 2) / b.width().coerceAtLeast(1f), (size.height - pad * 2) / b.height().coerceAtLeast(1f))
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(size.width / 2f, size.height / 2f)
            nc.scale(k, k)
            nc.translate(-b.centerX(), -b.centerY())
            Renderer.drawStrokes(nc, only, ctl.cache, RenderOptions(viewScale = k))
            nc.restore()
        }
    }
}
