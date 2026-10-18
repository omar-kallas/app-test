package com.omarkallas.inkfield.editor

import android.graphics.Matrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omarkallas.inkfield.engine.EditOps
import com.omarkallas.inkfield.engine.StrokeCache
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.Layer
import com.omarkallas.inkfield.model.Stroke
import com.omarkallas.inkfield.model.ToolType
import com.omarkallas.inkfield.model.Viewport
import com.omarkallas.inkfield.prefs.Prefs
import com.omarkallas.inkfield.prefs.ToolSlot

enum class PropKind { SIZE, OPACITY, SMOOTHING }

/**
 * All editor state. The canvas view and the Compose chrome both read and
 * change the drawing only through this class; every change to the document
 * goes through [commit], which records one undo step.
 */
class EditorController(initial: Drawing, val prefs: Prefs) {

    val cache = StrokeCache()

    var drawing by mutableStateOf(initial)
        private set

    /** Bumped on every document change; drives autosave and canvas redraws. */
    var version by mutableIntStateOf(0)
        private set

    private val undoStack = ArrayDeque<Drawing>()
    private val redoStack = ArrayDeque<Drawing>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // ---- tools ----
    var slots by mutableStateOf(prefs.slots)
        private set
    var activeSlot by mutableIntStateOf(prefs.activeSlot.coerceIn(0, prefs.slots.size - 1))
        private set
    val slot: ToolSlot get() = slots[activeSlot]

    var color by mutableIntStateOf(prefs.color)

        private set
    var palette by mutableStateOf(prefs.palette)
        private set

    // ---- selection ----
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set
    var partialLasso by mutableStateOf(prefs.partialLasso)
    var selectionScaleWidths by mutableStateOf(true)

    /** The next canvas touch picks a color instead of drawing. */
    var eyedropperArmed by mutableStateOf(false)

    /** Applies an eyedropper result: the color, plus the stroke's brush settings when that option is on. */
    fun applyEyedrop(color: Int, stroke: Stroke?) {
        setColor(color, applyToSelection = false)
        if (stroke != null && prefs.eyedropperBrush) {
            val i = if (slot.tool == ToolType.BRUSH) activeSlot else lastBrushSlot
            if (i in slots.indices && slots[i].tool == ToolType.BRUSH) {
                updateSlot(i, slots[i].copy(brush = stroke.brush, size = stroke.size, opacity = stroke.opacity, smoothing = stroke.smoothing))
            }
        }
        eyedropperArmed = false
        if (slot.tool == ToolType.EYEDROPPER && lastBrushSlot in slots.indices) selectSlot(lastBrushSlot)
    }

    /** Most recently used brush slot, so the eyedropper can hand back to it. */
    private var lastBrushSlot = 0

    // ---- view readouts (written by the canvas each frame) ----
    var zoomReadout by mutableFloatStateOf(initial.viewport.scale)
    var rotationReadout by mutableFloatStateOf(initial.viewport.rotation)
    /** Incremented to ask the canvas to reset its view (double-tap on the zoom readout). */
    var resetViewRequest by mutableIntStateOf(0)

    /** Tool the canvas uses right now; Slice/Select slots or a temporary override. */
    val activeTool: ToolType get() = slot.tool

    // ------------------------------------------------------------------
    // Document changes
    // ------------------------------------------------------------------

    private fun commit(next: Drawing, recordUndo: Boolean = true) {
        if (next === drawing) return
        if (recordUndo) {
            undoStack.addLast(drawing)
            if (undoStack.size > 400) undoStack.removeFirst()
            redoStack.clear()
        }
        drawing = next.copy(updated = System.currentTimeMillis())
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        version++
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(drawing)
        drawing = prev.copy(viewport = drawing.viewport, updated = System.currentTimeMillis())
        pruneSelection()
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        version++
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(drawing)
        drawing = next.copy(viewport = drawing.viewport, updated = System.currentTimeMillis())
        pruneSelection()
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
        version++
    }

    private fun pruneSelection() {
        if (selection.isEmpty()) return
        val ids = drawing.strokes.mapTo(HashSet()) { it.id }
        selection = selection.filterTo(HashSet()) { it in ids }
    }

    /** Saves the viewport without an undo step. */
    fun setViewport(v: Viewport) {
        if (v == drawing.viewport) return
        drawing = drawing.copy(viewport = v)
        version++
    }

    fun rename(name: String) {
        val n = name.trim().ifEmpty { return }
        commit(drawing.copy(name = n))
    }

    fun setBackground(color: Int) = commit(drawing.copy(background = color))

    /** Layer that a new stroke with [brush] goes to; creates a family layer in Automatic mode. */
    private fun targetLayer(d: Drawing, brush: BrushType): Pair<Drawing, Long> {
        if (!d.autoLayers) return d to d.activeLayer.id
        val existing = d.layers.firstOrNull { it.family == brush.family }
        if (existing != null) return d.copy(activeLayerId = existing.id) to existing.id
        // Reuse a still-empty default layer before creating a new one.
        val emptyDefault = d.layers.firstOrNull { l -> l.family == null && d.strokes.none { it.layerId == l.id } }
        if (emptyDefault != null && d.layers.size == 1) {
            val renamed = emptyDefault.copy(name = brush.family, family = brush.family)
            return d.copy(layers = d.layers.map { if (it.id == renamed.id) renamed else it }, activeLayerId = renamed.id) to renamed.id
        }
        val id = d.nextId
        val layer = Layer(id = id, name = brush.family, family = brush.family)
        return d.copy(layers = d.layers + layer, activeLayerId = id, nextId = id + 1) to id
    }

    // ---- precision ----
    /** On-canvas grid editing (move/rotate the grid, drag vanishing points). */
    var gridEditing by mutableStateOf(false)

    /** Precision settings change without an undo step. */
    fun setPrecision(p: com.omarkallas.inkfield.model.Precision) {
        if (p == drawing.precision) return
        drawing = drawing.copy(precision = p)
        version++
    }

    fun updatePrecision(f: (com.omarkallas.inkfield.model.Precision) -> com.omarkallas.inkfield.model.Precision) =
        setPrecision(f(drawing.precision))

    /** Scales the selection so its bounds become [w]×[h] canvas units (about the top-left corner). */
    fun resizeSelection(w: Float, h: Float) {
        val b = selectionBounds() ?: return
        if (b.width() < 1e-3f || b.height() < 1e-3f) return
        val m = Matrix().apply { setScale(w / b.width(), h / b.height(), b.left, b.top) }
        val uniform = kotlin.math.abs(w / b.width() - h / b.height()) < 1e-3f
        val keep = selectionScaleWidths
        if (!uniform) selectionScaleWidths = false
        transformSelection(m)
        selectionScaleWidths = keep
    }

    fun rotateSelection(deg: Float) {
        val b = selectionBounds() ?: return
        transformSelection(Matrix().apply { setRotate(deg, b.centerX(), b.centerY()) })
    }

    /** Sets the drawing scale so that the selected stroke's length equals [realPoints] real points. */
    fun setScaleFromSelection(realPoints: Double): Boolean {
        val s = selectedStrokes().singleOrNull() ?: return false
        val pr = cache.get(s).processed
        var len = 0f
        for (i in 1 until pr.n) len += kotlin.math.hypot(pr.x[i] - pr.x[i - 1], pr.y[i] - pr.y[i - 1])
        if (len < 1e-3f || realPoints <= 0.0) return false
        updatePrecision { it.copy(measure = it.measure.copy(scale = realPoints / len)) }
        return true
    }

    fun addStroke(brush: BrushType, pts: FloatArray, smoothing: Float? = null, measured: Boolean = false) {
        if (pts.size < Stroke.STRIDE) return
        val (d, layerId) = targetLayer(drawing, brush)
        val layer = d.layer(layerId)
        if (layer != null && layer.locked) return
        val s = Stroke(
            id = d.nextId, brush = brush, size = slot.size, color = color,
            opacity = slot.opacity, smoothing = smoothing ?: slot.smoothing, layerId = layerId, pts = pts,
            measured = measured,
        )
        // Show a hidden target layer rather than drawing invisibly.
        val layers = d.layers.map { if (it.id == layerId && !it.visible) it.copy(visible = true) else it }
        commit(d.copy(strokes = d.strokes + s, layers = layers, nextId = d.nextId + 1))
    }

    // ---- slicing: one undo step per gesture ----
    private var sliceBase: Drawing? = null

    fun sliceBegin() { sliceBase = drawing }

    fun sliceSegment(ax: Float, ay: Float, bx: Float, by: Float, r: Float) {
        val next = EditOps.slice(drawing, cache, ax, ay, bx, by, r) ?: return
        drawing = next
        version++
    }

    fun sliceEnd() {
        val base = sliceBase ?: return
        sliceBase = null
        if (drawing !== base) {
            undoStack.addLast(base)
            redoStack.clear()
            drawing = drawing.copy(updated = System.currentTimeMillis())
            canUndo = true; canRedo = false
            pruneSelection()
            version++
        }
    }

    // ---- selection ----
    fun selectLasso(poly: FloatArray) {
        val ids = EditOps.lasso(drawing, cache, poly, partialLasso, activeLayerOnly = false)
        selection = ids
        prefs.partialLasso = partialLasso
    }

    fun selectIds(ids: Set<Long>) { selection = ids }

    fun clearSelection() { selection = emptySet() }

    fun selectedStrokes(): List<Stroke> = drawing.strokes.filter { it.id in selection }

    /** Canvas-space bounds of the selection, or null when nothing is selected. */
    fun selectionBounds(): android.graphics.RectF? {
        if (selection.isEmpty()) return null
        val r = android.graphics.RectF()
        var any = false
        for (s in drawing.strokes) {
            if (s.id !in selection) continue
            val b = cache.get(s).processed.bounds
            if (b.isEmpty) continue
            if (!any) { r.set(b); any = true } else r.union(b)
        }
        return if (any) r else null
    }

    fun transformSelection(m: Matrix) {
        if (selection.isEmpty()) return
        val next = drawing.strokes.map {
            if (it.id in selection) EditOps.transform(it, m, selectionScaleWidths) else it
        }
        commit(drawing.copy(strokes = next))
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        commit(drawing.copy(strokes = drawing.strokes.filter { it.id !in selection }))
        selection = emptySet()
    }

    fun duplicateSelection() {
        if (selection.isEmpty()) return
        var id = drawing.nextId
        val m = Matrix().apply { setTranslate(24f / zoomReadout, 24f / zoomReadout) }
        val copies = selectedStrokes().map { EditOps.transform(it, m, false).copy(id = id++) }
        commit(drawing.copy(strokes = drawing.strokes + copies, nextId = id))
        selection = copies.mapTo(HashSet()) { it.id }
    }

    fun flipSelection(horizontal: Boolean, cx: Float, cy: Float) {
        val m = Matrix().apply { setScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f, cx, cy) }
        if (selection.isEmpty()) return
        commit(drawing.copy(strokes = drawing.strokes.map { if (it.id in selection) EditOps.transform(it, m, false) else it }))
    }

    private fun restyleSelection(f: (Stroke) -> Stroke) {
        if (selection.isEmpty()) return
        commit(drawing.copy(strokes = drawing.strokes.map { if (it.id in selection) f(it) else it }))
    }

    // ---- tool wheel ----
    fun selectSlot(i: Int) {
        if (i !in slots.indices) return
        if (slots[i].tool != ToolType.SELECT) clearSelection()
        if (slots[activeSlot].tool == ToolType.BRUSH) lastBrushSlot = activeSlot
        if (slots[i].tool == ToolType.BRUSH) lastBrushSlot = i
        activeSlot = i
        prefs.activeSlot = i
    }

    private fun updateSlot(i: Int, s: ToolSlot) {
        slots = slots.toMutableList().also { it[i] = s }
        prefs.slots = slots
    }

    fun setSlotBrush(i: Int, tool: ToolType, brush: BrushType) {
        val old = slots[i]
        val ns = if (tool == ToolType.BRUSH && (old.tool != ToolType.BRUSH || old.brush != brush)) {
            ToolSlot.defaultFor(brush)
        } else old.copy(tool = tool, brush = brush)
        updateSlot(i, ns)
        if (selection.isNotEmpty() && tool == ToolType.BRUSH && i == activeSlot) {
            restyleSelection { it.copy(brush = brush) }
        }
    }

    fun setSlotTool(i: Int, tool: ToolType) {
        if (i !in slots.indices) return
        updateSlot(i, if (tool == ToolType.BRUSH) ToolSlot.defaultFor(BrushType.PEN) else ToolSlot.tool(tool))
        if (i == activeSlot && tool != ToolType.SELECT) clearSelection()
    }

    fun setProp(kind: PropKind, value: Float, applyToSelection: Boolean = true) {
        val s = slot
        val ns = when (kind) {
            PropKind.SIZE -> s.copy(size = value.coerceIn(0.2f, 400f))
            PropKind.OPACITY -> s.copy(opacity = value.coerceIn(0.02f, 1f))
            PropKind.SMOOTHING -> s.copy(smoothing = value.coerceIn(0f, 1f))
        }
        updateSlot(activeSlot, ns)
        if (applyToSelection && selection.isNotEmpty()) {
            restyleSelection {
                when (kind) {
                    PropKind.SIZE -> it.copy(size = ns.size)
                    PropKind.OPACITY -> it.copy(opacity = ns.opacity)
                    PropKind.SMOOTHING -> it.copy(smoothing = ns.smoothing)
                }
            }
        }
    }

    fun setPreset(kind: PropKind, index: Int, value: Float) {
        val s = slot
        fun List<Float>.with(v: Float) = toMutableList().also { if (index in it.indices) it[index] = v }
        val ns = when (kind) {
            PropKind.SIZE -> s.copy(sizePresets = s.sizePresets.with(value))
            PropKind.OPACITY -> s.copy(opacityPresets = s.opacityPresets.with(value))
            PropKind.SMOOTHING -> s.copy(smoothingPresets = s.smoothingPresets.with(value))
        }
        updateSlot(activeSlot, ns)
    }

    fun setColor(c: Int, applyToSelection: Boolean = true) {
        color = c
        prefs.color = c
        if (applyToSelection && selection.isNotEmpty()) restyleSelection { it.copy(color = c) }
    }

    fun setPaletteColor(index: Int, c: Int) {
        palette = palette.toMutableList().also { if (index in it.indices) it[index] = c }
        prefs.palette = palette
    }

    /** Copies the brush, size and color of the stroke under the point into the active slot. */
    fun pickFrom(s: Stroke) {
        setColor(s.color, applyToSelection = false)
        if (slot.tool == ToolType.BRUSH) {
            updateSlot(activeSlot, slot.copy(brush = s.brush, size = s.size, opacity = s.opacity, smoothing = s.smoothing))
        }
    }

    // ---- layers ----
    fun setActiveLayer(id: Long) {
        if (drawing.layer(id) == null) return
        // Picking a layer by hand switches sorting to Manual.
        commit(drawing.copy(activeLayerId = id, autoLayers = false), recordUndo = false)
    }

    fun setAutoLayers(on: Boolean) = commit(drawing.copy(autoLayers = on), recordUndo = false)

    fun addLayer() {
        val d = drawing
        val id = d.nextId
        val idx = d.layers.indexOfFirst { it.id == d.activeLayerId }.let { if (it < 0) d.layers.size - 1 else it }
        val name = "Layer ${d.layers.count { it.family == null } + 1}"
        val list = d.layers.toMutableList().also { it.add(idx + 1, Layer(id = id, name = name)) }
        commit(d.copy(layers = list, activeLayerId = id, nextId = id + 1, autoLayers = false))
    }

    private fun mapLayer(id: Long, f: (Layer) -> Layer) =
        commit(drawing.copy(layers = drawing.layers.map { if (it.id == id) f(it) else it }))

    fun toggleLayerVisible(id: Long) = mapLayer(id) { it.copy(visible = !it.visible) }
    fun toggleLayerLock(id: Long) = mapLayer(id) { it.copy(locked = !it.locked) }
    fun renameLayer(id: Long, name: String) { val n = name.trim(); if (n.isNotEmpty()) mapLayer(id) { it.copy(name = n) } }

    /** Live opacity while dragging; [final] records the undo step. */
    private var opacityBase: Drawing? = null
    fun setLayerOpacity(id: Long, value: Float, final: Boolean) {
        if (opacityBase == null) opacityBase = drawing
        val next = drawing.copy(layers = drawing.layers.map { if (it.id == id) it.copy(opacity = value.coerceIn(0f, 1f)) else it })
        if (final) {
            val base = opacityBase!!
            opacityBase = null
            drawing = base
            commit(next)
        } else {
            drawing = next
            version++
        }
    }

    fun deleteLayer(id: Long) {
        val d = drawing
        if (d.layers.size <= 1) {
            commit(d.copy(strokes = d.strokes.filter { it.layerId != id }))
            return
        }
        val idx = d.layers.indexOfFirst { it.id == id }
        val layers = d.layers.filter { it.id != id }
        val active = if (d.activeLayerId == id) layers[(idx - 1).coerceIn(0, layers.size - 1)].id else d.activeLayerId
        commit(d.copy(layers = layers, strokes = d.strokes.filter { it.layerId != id }, activeLayerId = active))
        pruneSelection()
    }

    fun duplicateLayer(id: Long) {
        val d = drawing
        val src = d.layer(id) ?: return
        var next = d.nextId
        val nl = src.copy(id = next++, name = "${src.name} copy", family = null)
        val copies = d.strokes.filter { it.layerId == id }.map { it.copy(id = next++, layerId = nl.id) }
        val idx = d.layers.indexOf(src)
        val layers = d.layers.toMutableList().also { it.add(idx + 1, nl) }
        commit(d.copy(layers = layers, strokes = d.strokes + copies, nextId = next, activeLayerId = nl.id, autoLayers = false))
    }

    fun mergeDown(id: Long) {
        val d = drawing
        val idx = d.layers.indexOfFirst { it.id == id }
        if (idx <= 0) return
        val below = d.layers[idx - 1]
        // Keep paint order: the upper layer's strokes draw after the lower layer's.
        val rest = d.strokes.filter { it.layerId != id && it.layerId != below.id }
        val lower = d.strokes.filter { it.layerId == below.id }
        val upper = d.strokes.filter { it.layerId == id }.map { it.copy(layerId = below.id) }
        commit(d.copy(layers = d.layers.filter { it.id != id }, strokes = rest + lower + upper, activeLayerId = below.id))
    }

    /** Moves a layer one step up (toward the top) or down. */
    fun moveLayer(id: Long, up: Boolean) {
        val list = drawing.layers.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        commit(drawing.copy(layers = list, autoLayers = false))
    }

    fun selectAllInLayer(id: Long) {
        selection = drawing.strokes.filter { it.layerId == id }.mapTo(HashSet()) { it.id }
        val i = slots.indexOfFirst { it.tool == ToolType.SELECT }
        if (i >= 0) { activeSlot = i; prefs.activeSlot = i }
    }

    fun clearLayer(id: Long) = commit(drawing.copy(strokes = drawing.strokes.filter { it.layerId != id }))
}
