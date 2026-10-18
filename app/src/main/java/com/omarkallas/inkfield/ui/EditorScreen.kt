package com.omarkallas.inkfield.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omarkallas.inkfield.data.Repository
import com.omarkallas.inkfield.editor.DrawingView
import com.omarkallas.inkfield.editor.EditorController
import com.omarkallas.inkfield.editor.PropKind
import com.omarkallas.inkfield.engine.ExportFormat
import com.omarkallas.inkfield.engine.ExportOptions
import com.omarkallas.inkfield.engine.Exporter
import com.omarkallas.inkfield.model.Layer
import com.omarkallas.inkfield.model.Paper
import com.omarkallas.inkfield.prefs.FingerMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private enum class Overlay { NONE, PROP_SIZE, PROP_OPACITY, PROP_SMOOTHING, COLOR, SWATCHES, BRUSHES }

suspend fun saveDrawing(ctl: EditorController, repo: Repository) {
    val d = ctl.drawing
    val thumb = runCatching { Exporter.thumbnail(d, ctl.cache) }.getOrNull()
    withContext(Dispatchers.IO) { repo.save(d, thumb) }
    thumb?.recycle()
}

@Composable
fun EditorScreen(ctl: EditorController, repo: Repository, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val density = LocalDensity.current

    // ---- autosave: shortly after edits stop, and when the app goes to background ----
    LaunchedEffect(ctl.version) {
        if (ctl.version > 0) {
            delay(1500)
            saveDrawing(ctl, repo)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) scope.launch { saveDrawing(ctl, repo) }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val exit: () -> Unit = { scope.launch { saveDrawing(ctl, repo); onExit() } }
    BackHandler {
        if (ctl.selection.isNotEmpty()) ctl.clearSelection() else exit()
    }

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var brushSlot by remember { mutableStateOf(0) }
    var layersOpen by remember { mutableStateOf(ctl.prefs.layersOpen) }
    var renameDrawing by remember { mutableStateOf(false) }
    var renameLayer by remember { mutableStateOf<Layer?>(null) }
    var showPaper by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(ctl.drawing.background))) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val wheelW = with(density) { (WHEEL_SIZE + 112.dp).toPx() }
        val wheelH = with(density) { WHEEL_SIZE.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        var wheelX by remember { mutableFloatStateOf(if (ctl.prefs.wheelX >= 0f) ctl.prefs.wheelX else margin) }
        var wheelY by remember { mutableFloatStateOf(if (ctl.prefs.wheelY >= 0f) ctl.prefs.wheelY else maxH - wheelH - margin * 3) }
        val wx = wheelX.coerceIn(0f, (maxW - wheelW).coerceAtLeast(0f))
        val wy = wheelY.coerceIn(0f, (maxH - wheelH).coerceAtLeast(0f))

        // ---- canvas ----
        val viewRef = remember { mutableStateOf<DrawingView?>(null) }
        AndroidView(
            factory = { c -> DrawingView(c, ctl).also { viewRef.value = it } },
            modifier = Modifier.fillMaxSize(),
            update = { v ->
                // Reading these states re-runs update when they change.
                ctl.version; ctl.selection; ctl.slot; ctl.color
                v.invalidate()
            },
        )
        // Reset-view requests from the status bar.
        LaunchedEffect(ctl.resetViewRequest) {
            if (ctl.resetViewRequest > 0) viewRef.value?.resetView()
        }

        // ---- status bar ----
        StatusBar(
            ctl = ctl,
            layersOpen = layersOpen,
            onGallery = exit,
            onRename = { renameDrawing = true },
            onToggleLayers = { layersOpen = !layersOpen; ctl.prefs.layersOpen = layersOpen },
            onPaper = { showPaper = true },
            onExport = { showExport = true },
            onSettings = { showSettings = true },
            onHelp = { showHelp = true },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )

        if (layersOpen) {
            LayersPanel(
                ctl, onRename = { renameLayer = it }, onCollapse = { layersOpen = false; ctl.prefs.layersOpen = false },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp),
            )
        }

        if (ctl.eyedropperArmed) {
            Panel(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.DROPPER, Ink.text)
                    Spacer(Modifier.width(8.dp))
                    Text("Touch the canvas to pick a color", color = Ink.text, fontSize = 14.sp)
                    GlyphButton(Glyph.CLOSE, { ctl.eyedropperArmed = false }, size = 36.dp, contentDescription = "Cancel")
                }
            }
        }

        if (ctl.selection.isNotEmpty()) {
            SelectionBar(ctl, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
        }

        // ---- tool wheel ----
        ToolWheel(
            ctl = ctl,
            onOpenProp = { k -> overlay = when (k) { PropKind.SIZE -> Overlay.PROP_SIZE; PropKind.OPACITY -> Overlay.PROP_OPACITY; PropKind.SMOOTHING -> Overlay.PROP_SMOOTHING } },
            onOpenColor = { overlay = if (ctl.prefs.colorMode == "swatch") Overlay.SWATCHES else Overlay.COLOR },
            onOpenBrushMenu = { brushSlot = it; overlay = Overlay.BRUSHES },
            onDrag = { d -> wheelX = (wx + d.x); wheelY = (wy + d.y) },
            onDragEnd = { ctl.prefs.wheelX = wx; ctl.prefs.wheelY = wy },
            modifier = Modifier.offset { IntOffset(wx.roundToInt(), wy.roundToInt()) },
        )

        // ---- popups anchored to the wheel ----
        if (overlay == Overlay.SWATCHES) {
            // Wheel center: the undo button and its spacer sit left of the wheel.
            val cx = wx + with(density) { (50.dp + WHEEL_SIZE / 2).toPx() }
            val cy = wy + wheelH / 2f
            SwatchWheel(
                ctl, androidx.compose.ui.geometry.Offset(cx, cy),
                onClose = { overlay = Overlay.NONE },
                onMode = { m -> ctl.prefs.colorMode = m; overlay = Overlay.COLOR },
                onEyedropper = { overlay = Overlay.NONE; ctl.eyedropperArmed = true },
            )
        } else if (overlay != Overlay.NONE) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { overlay = Overlay.NONE } })
            val gap = with(density) { 10.dp.toPx() }
            val above = wy + wheelH / 2 > maxH / 2
            Box(
                Modifier
                    .fillMaxSize()
                    .layout { m, c ->
                        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                        layout(c.maxWidth, c.maxHeight) {
                            val x = wx.roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0))
                            val y = (if (above) wy - p.height - gap else wy + wheelH + gap).roundToInt()
                                .coerceIn(0, (c.maxHeight - p.height).coerceAtLeast(0))
                            p.place(x, y)
                        }
                    },
            ) {
                when (overlay) {
                    Overlay.PROP_SIZE -> PropPanel(ctl, PropKind.SIZE) { overlay = Overlay.NONE }
                    Overlay.PROP_OPACITY -> PropPanel(ctl, PropKind.OPACITY) { overlay = Overlay.NONE }
                    Overlay.PROP_SMOOTHING -> PropPanel(ctl, PropKind.SMOOTHING) { overlay = Overlay.NONE }
                    Overlay.COLOR -> ColorPanel(
                        ctl,
                        onClose = { overlay = Overlay.NONE },
                        onSwatches = { overlay = Overlay.SWATCHES },
                        onEyedropper = { overlay = Overlay.NONE; ctl.eyedropperArmed = true },
                    )
                    Overlay.BRUSHES -> BrushMenu(ctl, brushSlot) { overlay = Overlay.NONE }
                    Overlay.SWATCHES, Overlay.NONE -> {}
                }
            }
        }

    }

    if (renameDrawing) {
        TextDialog("Rename drawing", ctl.drawing.name, onDismiss = { renameDrawing = false }) { ctl.rename(it) }
    }
    renameLayer?.let { l ->
        TextDialog("Rename layer", l.name, onDismiss = { renameLayer = null }) { ctl.renameLayer(l.id, it) }
    }
    if (showPaper) PaperDialog(ctl) { showPaper = false }
    if (showSettings) SettingsDialog(ctl) { showSettings = false }
    if (showHelp) HelpDialog { showHelp = false }
    if (showExport) ExportDialog(ctl) { showExport = false }
}

@Composable
private fun StatusBar(
    ctl: EditorController,
    layersOpen: Boolean,
    onGallery: () -> Unit,
    onRename: () -> Unit,
    onToggleLayers: () -> Unit,
    onPaper: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyph.GALLERY, onGallery, contentDescription = "Gallery")
            Text(
                ctl.drawing.name,
                color = Ink.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false).clickable(onClick = onRename),
            )
            Spacer(Modifier.weight(1f))
            val zoom = (ctl.zoomReadout * 100).roundToInt()
            var rot = ctl.rotationReadout.roundToInt() % 360
            if (rot < 0) rot += 360
            Text(
                "$zoom% · $rot°",
                color = Ink.muted, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ctl.resetViewRequest++ }) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            GlyphButton(Glyph.LAYERS, onToggleLayers, active = layersOpen, contentDescription = "Layers")
            GlyphButton(Glyph.PAPER, onPaper, contentDescription = "Paper")
            GlyphButton(Glyph.EXPORT, onExport, contentDescription = "Export")
            GlyphButton(Glyph.SETTINGS, onSettings, contentDescription = "Settings")
            GlyphButton(Glyph.HELP, onHelp, contentDescription = "Help")
        }
    }
}

@Composable
private fun SelectionBar(ctl: EditorController, modifier: Modifier) {
    Panel(modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ctl.selection.size} selected", color = Ink.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Chip(if (ctl.partialLasso) "Partial" else "Enclosed", ctl.partialLasso) { ctl.partialLasso = !ctl.partialLasso }
            Spacer(Modifier.width(6.dp))
            Chip(if (ctl.selectionScaleWidths) "Scale" else "Stretch", false) { ctl.selectionScaleWidths = !ctl.selectionScaleWidths }
            Spacer(Modifier.width(4.dp))
            GlyphButton(Glyph.DUPLICATE, { ctl.duplicateSelection() }, contentDescription = "Duplicate")
            GlyphButton(Glyph.FLIP_H, {
                ctl.selectionBounds()?.let { b -> ctl.flipSelection(true, b.centerX(), b.centerY()) }
            }, contentDescription = "Flip horizontally")
            GlyphButton(Glyph.FLIP_V, {
                ctl.selectionBounds()?.let { b -> ctl.flipSelection(false, b.centerX(), b.centerY()) }
            }, contentDescription = "Flip vertically")
            GlyphButton(Glyph.TRASH, { ctl.deleteSelection() }, tint = Ink.danger, contentDescription = "Delete")
            GlyphButton(Glyph.CHECK, { ctl.clearSelection() }, contentDescription = "Done")
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onDone(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PaperDialog(ctl: EditorController, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paper") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Paper.entries.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctl.setBackground(p.color) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(p.color)).border(
                            if (ctl.drawing.background == p.color) 2.dp else 1.dp,
                            if (ctl.drawing.background == p.color) Ink.text else Ink.outline, RoundedCornerShape(6.dp),
                        ))
                        Spacer(Modifier.width(12.dp))
                        Text(p.label)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctl.setBackground(ctl.color or 0xFF000000.toInt()) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Color(ctl.color)).border(1.dp, Ink.outline, RoundedCornerShape(6.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text("Use the current color")
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { ctl.prefs.defaultPaper = ctl.drawing.background; onDismiss() }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.CHECK, Ink.muted)
                    Spacer(Modifier.width(12.dp))
                    Text("Make this the default for new drawings", color = Ink.muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingsDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val prefs = ctl.prefs
    var finger by remember { mutableStateOf(prefs.fingerMode) }
    var pressure by remember { mutableStateOf(prefs.pressureEnabled) }
    var prediction by remember { mutableStateOf(prefs.predictionEnabled) }
    var highlight by remember { mutableStateOf(prefs.highlightSelection) }
    var dropAlpha by remember { mutableStateOf(prefs.eyedropperAlpha) }
    var dropBrush by remember { mutableStateOf(prefs.eyedropperBrush) }
    var hold by remember { mutableFloatStateOf(prefs.holdToSelectMs.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Finger action", fontWeight = FontWeight.SemiBold)
                FingerMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { finger = m; prefs.fingerMode = m }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .border(2.dp, if (finger == m) Ink.accent else Ink.outline, CircleShape)
                                .padding(4.dp).clip(CircleShape).background(if (finger == m) Ink.accent else Color.Transparent),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(m.label, fontSize = 14.sp)
                    }
                }
                if (prefs.stylusSeen && finger == FingerMode.AUTO) {
                    Text("A stylus was detected, so fingers now pan and zoom.", color = Ink.muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                SettingSwitch("Stylus pressure", pressure) { pressure = it; prefs.pressureEnabled = it }
                SettingSwitch("Low-latency prediction", prediction) { prediction = it; prefs.predictionEnabled = it }
                SettingSwitch("Gray out unselected strokes", highlight) { highlight = it; prefs.highlightSelection = it }
                SettingSwitch("Color picker takes a stroke's own color", dropAlpha) { dropAlpha = it; prefs.eyedropperAlpha = it }
                SettingSwitch("Color picker also copies the brush", dropBrush) { dropBrush = it; prefs.eyedropperBrush = it }
                Spacer(Modifier.height(8.dp))
                Text("Hold to select: ${hold.roundToInt()} ms", fontSize = 14.sp)
                Slider(
                    value = hold, onValueChange = { hold = it }, valueRange = 250f..1200f,
                    onValueChangeFinished = { prefs.holdToSelectMs = hold.roundToInt() },
                    colors = SliderDefaults.colors(thumbColor = Ink.text, activeTrackColor = Ink.text),
                )
                Spacer(Modifier.height(8.dp))
                Text("Inkfield works fully offline. Nothing leaves your device unless you export it.", color = Ink.muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val items = listOf(
        "Draw" to "Draw with the stylus. Fingers draw too until a stylus is used (change this in Settings).",
        "Pan, zoom, rotate" to "Two fingers. Zoom snaps at 100% and rotation at right angles. Double-tap the zoom readout to reset.",
        "Undo / redo" to "Two-finger tap undoes, three-finger tap redoes. Also the arrows beside the wheel, or Ctrl+Z / Ctrl+Shift+Z.",
        "Tool wheel" to "Tap a slot to pick it; tap the active slot again to change its brush. The middle ring sets size, opacity and smoothing. The center opens colors. Hold and drag the wheel to move it.",
        "Select" to "Hold your finger or pen still on the canvas, or use the Select slot, then draw a loop. Tap a stroke to select just it.",
        "Edit a selection" to "Drag inside to move, drag a corner to scale, drag the top handle to rotate, or pinch with two fingers. Change brush, size, opacity, smoothing or color and the selection updates.",
        "Slice" to "Cuts through strokes where you drag. The pen's eraser end slices too.",
        "Layers" to "Auto mode puts each tool family on its own layer. Tap a layer to make it active; tap it again for options.",
        "Smoothing" to "0% keeps your raw line, 50% removes most wobble, 100% turns the stroke into a straight line.",
        "Saving" to "Drawings save automatically. Deleted drawings stay in Recently Deleted for 30 days.",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inkfield help") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach { (t, d) ->
                    Text(t, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(d, fontSize = 13.sp, color = Ink.muted, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ExportDialog(ctl: EditorController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.PNG) }
    var scale by remember { mutableFloatStateOf(2f) }
    var background by remember { mutableStateOf(true) }
    val hasSelection = ctl.selection.isNotEmpty()
    var selectionOnly by remember { mutableStateOf(hasSelection) }
    val opts = ExportOptions(format, scale, background, if (selectionOnly && hasSelection) ctl.selection else null)
    val fileName = ctl.drawing.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Inkfield" } + "." + format.ext

    fun bytes(): ByteArray = Exporter.encode(ctl.drawing, ctl.cache, opts)

    val onSaved: (android.net.Uri?) -> Unit = { uri ->
        if (uri != null) {
            val data = bytes()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(data) } != null }.getOrDefault(false)
                }
                Toast.makeText(context, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
                if (ok) onDismiss()
            }
        }
    }
    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), onSaved)
    val saveJpg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg"), onSaved)
    val saveSvg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml"), onSaved)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportFormat.entries.forEach { f -> Chip(f.label, format == f) { format = f } }
                }
                if (format != ExportFormat.SVG) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(1f to "72", 2f to "150", 4f to "300", 8f to "600").forEach { (k, ppi) ->
                            Chip("$ppi ppi", scale == k) { scale = k }
                        }
                    }
                    val (w, h) = Exporter.pixelSize(ctl.drawing, ctl.cache, opts)
                    Text("$w × $h px", color = Ink.muted, fontSize = 12.sp)
                } else {
                    Text("Vector paths, one per stroke. Opens in Illustrator, Inkscape and browsers.", color = Ink.muted, fontSize = 12.sp)
                }
                SettingSwitch(
                    if (format == ExportFormat.JPG) "Include paper (otherwise white)" else "Include paper", background,
                ) { background = it }
                if (hasSelection) SettingSwitch("Selection only", selectionOnly) { selectionOnly = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (format) {
                    ExportFormat.PNG -> savePng.launch(fileName)
                    ExportFormat.JPG -> saveJpg.launch(fileName)
                    ExportFormat.SVG -> saveSvg.launch(fileName)
                }
            }) { Text("Save to device") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    val data = bytes()
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                        File(dir, fileName).also { it.writeBytes(data) }
                    }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = format.mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share drawing"))
                }
            }) { Text("Share") }
        },
    )
}
