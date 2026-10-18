package com.omarkallas.inkfield.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.engine.Assets
import com.omarkallas.inkfield.engine.BrushLib
import com.omarkallas.inkfield.engine.CustomBrush
import com.omarkallas.inkfield.engine.StampShape
import kotlin.math.roundToInt

/** Create or edit a stamp brush. [initial] null makes a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrushEditorDialog(ctl: EditorController, initial: CustomBrush?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var b by remember { mutableStateOf(initial ?: CustomBrush(BrushLib.newId(), "My brush")) }
    val isNew = initial == null || BrushLib.user.none { it.id == initial.id }
    var imageError by remember { mutableStateOf<String?>(null) }
    // Bump to refresh the preview when the stamp image changes.
    var rev by remember { mutableStateOf(0) }

    val pickStamp = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = Assets.decode { context.contentResolver.openInputStream(uri) }
            val name = bmp?.let { BrushLib.saveStampImage(it) }
            if (name == null) imageError = "Couldn't read that image." else { b = b.copy(stamp = StampShape.IMAGE, image = name); rev++ }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New brush" else "Edit brush") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Live preview: the brush is saved temporarily so the renderer can find it.
                val preview = remember(b, rev) { b.copy(id = "preview.${b.hashCode()}.$rev") }
                Canvas(
                    Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
                ) {
                    BrushPreviewRegistry.set(preview)
                    drawStampSample(preview, Offset(size.width / 2, size.height / 2), size.width * 0.42f, Color(ctl.color))
                }
                OutlinedTextField(b.name, { b = b.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                Text("Stamp", color = Ink.muted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StampShape.entries.filter { it != StampShape.IMAGE }.forEach { st ->
                        Chip(st.label, b.stamp == st) { b = b.copy(stamp = st) }
                    }
                    Chip(if (b.stamp == StampShape.IMAGE) "Image ✓" else "From image…", b.stamp == StampShape.IMAGE) {
                        pickStamp.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                imageError?.let { Text(it, color = Ink.danger, fontSize = 12.sp) }
                Text("Dark parts of an image stamp paint, light parts stay clear.", color = Ink.muted, fontSize = 11.sp)
                BrushSlider("Spacing", b.spacing, 0.02f..2f) { b = b.copy(spacing = it) }
                BrushSlider("Flow (paint per stamp)", b.flow, 0.02f..1f) { b = b.copy(flow = it) }
                BrushSlider("Scatter", b.scatter, 0f..3f) { b = b.copy(scatter = it) }
                BrushSlider("Size jitter", b.sizeJitter, 0f..1f) { b = b.copy(sizeJitter = it) }
                BrushSlider("Opacity jitter", b.opacityJitter, 0f..1f) { b = b.copy(opacityJitter = it) }
                BrushSlider("Rotation jitter", b.rotationJitter, 0f..1f) { b = b.copy(rotationJitter = it) }
                BrushSlider("Size follows pressure", b.sizePressure, -1f..1f) { b = b.copy(sizePressure = it) }
                BrushSlider("Opacity follows pressure", b.opacityPressure, -1f..1f) { b = b.copy(opacityPressure = it) }
                BrushSlider("Default size", b.defaultSize, 1f..200f) { b = b.copy(defaultSize = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Turn stamps along the stroke", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(b.followPath, { b = b.copy(followPath = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paper grain", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(b.grain, { b = b.copy(grain = it) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = b.copy(library = false, name = b.name.ifBlank { "My brush" })
                BrushLib.upsert(saved)
                ctl.setSlotCustom(ctl.activeSlot, saved)
                ctl.refreshBrushes()
                onDismiss()
            }) { Text("Save and use") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { BrushLib.delete(b.id); ctl.refreshBrushes(); onDismiss() }) { Text("Delete", color = Ink.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun BrushSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label  ${if (range.endInclusive > 10f) value.roundToInt().toString() else "%.2f".format(value)}", fontSize = 13.sp)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Ink.text, activeTrackColor = Ink.text),
            modifier = Modifier.height(30.dp),
        )
    }
}

/** Lets the preview render a brush that isn't saved yet. */
object BrushPreviewRegistry {
    fun set(b: CustomBrush) = BrushLib.setPreview(b)
}
