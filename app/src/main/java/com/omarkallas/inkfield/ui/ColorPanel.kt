package com.omarkallas.inkfield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omarkallas.inkfield.editor.EditorController

private fun hsvToArgb(h: Float, s: Float, v: Float): Int =
    android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

private fun argbToHsv(c: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }

private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColorPanel(ctl: EditorController, onClose: () -> Unit) {
    val start = remember { argbToHsv(ctl.color) }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var rgbMode by remember { mutableStateOf(false) }
    var hexText by remember { mutableStateOf(hex(ctl.color)) }

    fun push(final: Boolean) {
        val c = hsvToArgb(h, s, v)
        hexText = hex(c)
        ctl.setColor(c, applyToSelection = final)
    }

    fun setFromArgb(c: Int, final: Boolean) {
        val hsv = argbToHsv(c)
        // Keep hue when the color is gray so the square doesn't jump.
        if (hsv[1] > 0.001f) h = hsv[0]
        s = hsv[1]; v = hsv[2]
        hexText = hex(c)
        ctl.setColor(c or 0xFF000000.toInt(), applyToSelection = final)
    }

    Panel(Modifier.width(300.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Color", fontWeight = FontWeight.SemiBold, color = Ink.text)
                Spacer(Modifier.width(10.dp))
                Chip("HSV", !rgbMode) { rgbMode = false }
                Spacer(Modifier.width(6.dp))
                Chip("RGB", rgbMode) { rgbMode = true }
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.CLOSE, onClose, size = 32.dp)
            }
            Spacer(Modifier.height(10.dp))
            if (!rgbMode) {
                // Saturation / value square
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            fun upd(p: Offset) {
                                s = (p.x / size.width).coerceIn(0f, 1f)
                                v = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            }
                            detectDragGestures(
                                onDragStart = { upd(it); push(false) },
                                onDragEnd = { push(true) },
                                onDrag = { ch, _ -> ch.consume(); upd(ch.position); push(false) },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { p ->
                                s = (p.x / size.width).coerceIn(0f, 1f)
                                v = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                push(true)
                            }
                        },
                ) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(h, 1f, 1f)))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val p = Offset(s * size.width, (1f - v) * size.height)
                    drawCircle(Color.White, 9.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                    drawCircle(Color.Black.copy(alpha = 0.4f), 10.5.dp.toPx(), p, style = Stroke(1.dp.toPx()))
                }
                Spacer(Modifier.height(10.dp))
                // Hue bar
                val hues = remember { List(13) { Color(hsvToArgb(it * 30f, 1f, 1f)) } }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { h = (it.x / size.width * 360f).coerceIn(0f, 360f); push(false) },
                                onDragEnd = { push(true) },
                                onDrag = { ch, _ -> ch.consume(); h = (ch.position.x / size.width * 360f).coerceIn(0f, 360f); push(false) },
                            )
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { p -> h = (p.x / size.width * 360f).coerceIn(0f, 360f); push(true) }
                        },
                ) {
                    drawRoundRect(Brush.horizontalGradient(hues), cornerRadius = CornerRadius(size.height / 2))
                    val x = h / 360f * size.width
                    drawCircle(Color.White, size.height / 2 - 1.dp.toPx(), Offset(x.coerceIn(size.height / 2, size.width - size.height / 2), size.height / 2), style = Stroke(3.dp.toPx()))
                }
            } else {
                val c = hsvToArgb(h, s, v)
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                RgbSlider("R", r, Color(0xFFC0392B)) { setFromArgb((0xFF shl 24) or (it shl 16) or (g shl 8) or b, false) }
                RgbSlider("G", g, Color(0xFF27AE60)) { setFromArgb((0xFF shl 24) or (r shl 16) or (it shl 8) or b, false) }
                RgbSlider("B", b, Color(0xFF2E86C1)) { setFromArgb((0xFF shl 24) or (r shl 16) or (g shl 8) or it, false) }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(hsvToArgb(h, s, v))).border(1.dp, Ink.outline, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text("#", color = Ink.muted, fontFamily = FontFamily.Monospace)
                BasicTextField(
                    value = hexText,
                    onValueChange = { t ->
                        val clean = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        hexText = clean
                        if (clean.length == 6) setFromArgb(clean.toLong(16).toInt() or 0xFF000000.toInt(), true)
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Ink.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { }),
                    modifier = Modifier
                        .width(90.dp)
                        .background(Ink.activeFill, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                GlyphButton(Glyph.CHECK, { push(true); onClose() }, size = 36.dp, contentDescription = "Done")
            }
            Spacer(Modifier.height(12.dp))
            Text("Palette", color = Ink.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ctl.palette.forEachIndexed { i, pc ->
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(pc))
                            .border(if (pc == ctl.color) 2.5.dp else 1.dp, if (pc == ctl.color) Ink.text else Ink.outline, CircleShape)
                            .combinedClickable(
                                onClick = { setFromArgb(pc, true) },
                                onLongClick = { ctl.setPaletteColor(i, hsvToArgb(h, s, v)) },
                            ),
                    )
                }
            }
            Text("Hold a swatch to store the current color in it.", color = Ink.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun RgbSlider(label: String, value: Int, tint: Color, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink.muted, modifier = Modifier.width(18.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
            modifier = Modifier.weight(1f),
        )
        Text(value.toString(), color = Ink.text, fontSize = 13.sp, modifier = Modifier.width(34.dp).padding(start = 6.dp))
    }
}

@Suppress("unused")
private fun Color.argb() = toArgb()
