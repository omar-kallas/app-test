package com.omarkallas.inkfield.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.input.motionprediction.MotionEventPredictor
import com.omarkallas.inkfield.engine.EditOps
import com.omarkallas.inkfield.engine.Geometry
import com.omarkallas.inkfield.engine.Grid
import com.omarkallas.inkfield.engine.GuideHandle
import com.omarkallas.inkfield.engine.Guides
import com.omarkallas.inkfield.engine.Recognize
import com.omarkallas.inkfield.engine.Snap
import com.omarkallas.inkfield.engine.Units
import com.omarkallas.inkfield.model.Guide
import com.omarkallas.inkfield.model.Pt
import com.omarkallas.inkfield.engine.RenderOptions
import com.omarkallas.inkfield.engine.Renderer
import com.omarkallas.inkfield.engine.StrokeCache
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Stroke
import com.omarkallas.inkfield.model.ToolType
import com.omarkallas.inkfield.model.Viewport
import com.omarkallas.inkfield.prefs.FingerMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The infinite canvas. Owns the view transform and turns pointer input into
 * strokes, cuts, selections and transforms through [EditorController].
 *
 * Rules: a gesture that starts with a stylus never pans; one that starts with
 * two fingers never draws; touches are ignored while a stylus is near or down.
 */
@SuppressLint("ViewConstructor")
class DrawingView(context: Context, private val ctl: EditorController) : View(context) {

    private enum class Mode { NONE, DRAW, NAVIGATE, LASSO, SLICE, SEL_MOVE, SEL_SCALE, SEL_ROTATE, SEL_PINCH, EYEDROP, GUIDE_EDIT, GRID_EDIT, IGNORE }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** canvas → screen */
    private val view = Matrix()
    private val inverse = Matrix()
    private var viewInitialized = false

    private var mode = Mode.NONE
    private var activePointerId = -1
    private var gestureFromStylus = false

    // live stroke
    private var live = FloatArray(512)
    private var liveCount = 0
    private var liveBrush = BrushType.PEN
    private var downTime = 0L
    private val livePredicted = FloatArray(Stroke.STRIDE * 8)
    private var livePredictedCount = 0
    private val predictor: MotionEventPredictor = MotionEventPredictor.newInstance(this)

    // navigation
    private val navStart = Matrix()
    private var navStartScale = 1f
    private var navStartRot = 0f
    private val navC0 = PointF()
    private var navD0 = 1f
    private var navA0 = 0f

    // two/three-finger taps
    private var gestureStartTime = 0L
    private var gestureMaxPointers = 0
    private var gestureMoved = false
    private val downX = FloatArray(10)
    private val downY = FloatArray(10)

    // lasso (screen points)
    private var lasso = FloatArray(256)
    private var lassoCount = 0

    // slice
    private val lastCanvasPt = PointF()
    private val cursor = PointF(-1f, -1f)

    // selection transform preview (canvas space)
    private val selMatrix = Matrix()
    private var selHasTransform = false
    private val selStartCanvas = PointF()
    private val selAnchor = PointF()
    private val selCorner = PointF()
    private val selCenter = PointF()
    private var selStartAngle = 0f
    private val pinchStartPts = FloatArray(4)

    // stylus proximity for palm rejection
    private var lastStylusTime = 0L
    private var stylusDown = false

    private var holdPending: Runnable? = null

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpPts = FloatArray(8)
    private val tmpRect = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ------------------------------------------------------------------
    // View transform
    // ------------------------------------------------------------------

    fun applyViewport(v: Viewport) {
        view.reset()
        view.setScale(v.scale, v.scale)
        view.postRotate(v.rotation)
        view.postTranslate(v.tx, v.ty)
        view.invert(inverse)
        viewInitialized = true
        publishReadout()
        invalidate()
    }

    private fun currentViewport(): Viewport {
        val v = FloatArray(9); view.getValues(v)
        return Viewport(tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y], scale = scaleOf(view), rotation = rotationOf(view))
    }

    private fun scaleOf(m: Matrix): Float {
        val v = FloatArray(9); m.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    private fun rotationOf(m: Matrix): Float {
        val v = FloatArray(9); m.getValues(v)
        return Math.toDegrees(atan2(v[Matrix.MSKEW_Y], v[Matrix.MSCALE_X]).toDouble()).toFloat()
    }

    private fun publishReadout() {
        ctl.zoomReadout = scaleOf(view)
        ctl.rotationReadout = rotationOf(view)
    }

    /** Canvas point at the view center and the canvas length of 100 dp, for placing new guides. */
    fun viewCenterCanvas(): FloatArray {
        val p = PointF()
        toCanvas(width / 2f, height / 2f, p)
        return floatArrayOf(p.x, p.y, 100f * density / scaleOf(view))
    }

    /** Return to 100% and 0° with the content (or the origin) centered. */
    fun resetView() {
        val b = Renderer.contentBounds(ctl.drawing, ctl.cache)
        val cx = b?.centerX() ?: 0f
        val cy = b?.centerY() ?: 0f
        view.reset()
        view.postTranslate(width / 2f - cx, height / 2f - cy)
        view.invert(inverse)
        publishReadout()
        ctl.setViewport(currentViewport())
        invalidate()
    }

    private fun toCanvas(x: Float, y: Float, out: PointF) {
        tmpPts[0] = x; tmpPts[1] = y
        inverse.mapPoints(tmpPts, 0, tmpPts, 0, 1)
        out.set(tmpPts[0], tmpPts[1])
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val d = ctl.drawing
        if (!viewInitialized) applyViewport(d.viewport)
        canvas.drawColor(d.background)

        val scale = scaleOf(view)
        tmpRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val visible = RectF()
        inverse.mapRect(visible, tmpRect)

        val sel = ctl.selection
        val highlight = if (sel.isNotEmpty() && ctl.prefs.highlightSelection) sel else null
        val skip = if (sel.isNotEmpty() && selHasTransform) sel else null

        val pre = d.precision
        canvas.save()
        canvas.concat(view)
        if (pre.gridOn) Grid.draw(canvas, pre.grid, visible, scale, isDarkPaper(), density)
        Renderer.drawStrokes(canvas, d, ctl.cache, RenderOptions(scale, visible, highlight, skip))
        if (skip != null) Renderer.drawTransformed(canvas, ctl.selectedStrokes(), ctl.cache, selMatrix, scale, d)
        activeGuide()?.let { drawGuideTemplate(canvas, it, scale, visible) }
        if (mode == Mode.DRAW && liveCount > 0) drawLive(canvas, scale)
        canvas.restore()

        if (pre.measureOn) drawMeasureLabels(canvas, visible)
        activeGuide()?.let { drawGuideHandles(canvas, it) }
        if (ctl.gridEditing && pre.gridOn) drawGridHandles(canvas)

        if (sel.isNotEmpty()) drawSelectionOverlay(canvas)
        if (mode == Mode.LASSO && lassoCount >= 2) drawLasso(canvas)
        if (mode == Mode.EYEDROP) drawLoupe(canvas)
        if (mode == Mode.SLICE && cursor.x >= 0f) {
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = 1.5f * density
            overlayPaint.color = 0x99000000.toInt()
            overlayPaint.pathEffect = null
            canvas.drawCircle(cursor.x, cursor.y, ctl.slot.size / 2f, overlayPaint)
        }
    }

    private fun drawLive(canvas: Canvas, scale: Float) {
        val (pts, sm) = finalLivePoints(includePredicted = true)
        if (pts.isEmpty()) return
        val slot = ctl.slot
        val s = Stroke(-1, liveBrush, slot.size, ctl.color, slot.opacity, sm ?: slot.smoothing, -1, pts)
        val pr = Geometry.process(s)
        val entry = StrokeCache.Entry(s, pr, Geometry.buildPath(s, pr))
        Renderer.drawEntry(canvas, entry, scale)
    }

    private fun drawLasso(canvas: Canvas) {
        val p = Path()
        p.moveTo(lasso[0], lasso[1])
        for (i in 1 until lassoCount) p.lineTo(lasso[i * 2], lasso[i * 2 + 1])
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = 1.5f * density
        overlayPaint.color = 0xFF2E86C1.toInt()
        overlayPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
        canvas.drawPath(p, overlayPaint)
        overlayPaint.pathEffect = null
    }

    /** Screen-space corners of the selection box: TL, TR, BR, BL (8 floats), including the live transform. */
    private fun selectionCorners(): FloatArray? {
        val b = ctl.selectionBounds() ?: return null
        val pad = 6f / scaleOf(view)
        val c = floatArrayOf(b.left - pad, b.top - pad, b.right + pad, b.top - pad, b.right + pad, b.bottom + pad, b.left - pad, b.bottom + pad)
        if (selHasTransform) selMatrix.mapPoints(c)
        view.mapPoints(c)
        return c
    }

    private fun rotateHandle(c: FloatArray): PointF {
        // Above the midpoint of the top edge, along the box's "up" direction.
        val mx = (c[0] + c[2]) / 2f; val my = (c[1] + c[3]) / 2f
        val bx = (c[4] + c[6]) / 2f; val by = (c[5] + c[7]) / 2f
        val len = hypot(mx - bx, my - by).coerceAtLeast(1f)
        val off = 34f * density
        return PointF(mx + (mx - bx) / len * off, my + (my - by) / len * off)
    }

    private fun drawSelectionOverlay(canvas: Canvas) {
        val c = selectionCorners() ?: return
        val p = Path().apply { moveTo(c[0], c[1]); lineTo(c[2], c[3]); lineTo(c[4], c[5]); lineTo(c[6], c[7]); close() }
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = 1.2f * density
        overlayPaint.color = 0xFF2E86C1.toInt()
        overlayPaint.pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
        canvas.drawPath(p, overlayPaint)
        overlayPaint.pathEffect = null
        val r = 7f * density
        val h = rotateHandle(c)
        canvas.drawLine((c[0] + c[2]) / 2f, (c[1] + c[3]) / 2f, h.x, h.y, overlayPaint)
        for (i in 0 until 4) drawHandle(canvas, c[i * 2], c[i * 2 + 1], r)
        drawHandle(canvas, h.x, h.y, r)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float, r: Float) {
        overlayPaint.style = Paint.Style.FILL
        overlayPaint.color = Color.WHITE
        canvas.drawCircle(x, y, r, overlayPaint)
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.color = 0xFF2E86C1.toInt()
        canvas.drawCircle(x, y, r, overlayPaint)
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private fun isStylus(e: MotionEvent, idx: Int): Boolean {
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun stylusNear(): Boolean = stylusDown || SystemClock.uptimeMillis() - lastStylusTime < 350

    private fun fingersDraw(): Boolean = when (ctl.prefs.fingerMode) {
        FingerMode.DRAW -> true
        FingerMode.NAVIGATE -> false
        FingerMode.AUTO -> !ctl.prefs.stylusSeen
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (isStylus(e, 0)) lastStylusTime = SystemClock.uptimeMillis()
        return super.onHoverEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v != 0f) {
                val k = Math.pow(1.12, v.toDouble()).toFloat()
                val cur = scaleOf(view)
                val target = (cur * k).coerceIn(0.02f, 64f)
                view.postScale(target / cur, target / cur, e.x, e.y)
                view.invert(inverse)
                publishReadout()
                ctl.setViewport(currentViewport())
                invalidate()
                return true
            }
        }
        if (isStylus(e, 0)) lastStylusTime = SystemClock.uptimeMillis()
        return super.onGenericMotionEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (ctl.prefs.predictionEnabled) runCatching { predictor.record(e) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    private fun onDown(e: MotionEvent) {
        cancelHold()
        gestureStartTime = e.eventTime
        gestureMaxPointers = 1
        gestureMoved = false
        downX[0] = e.x; downY[0] = e.y
        activePointerId = e.getPointerId(0)
        val stylus = isStylus(e, 0)
        gestureFromStylus = stylus
        if (stylus) {
            stylusDown = true
            lastStylusTime = SystemClock.uptimeMillis()
            if (!ctl.prefs.stylusSeen) ctl.prefs.stylusSeen = true
        } else if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && stylusNear()) {
            mode = Mode.IGNORE // palm rejection
            return
        }
        val mouse = e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        if (mouse && (e.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY)) != 0) {
            startNavigate(e); return
        }

        if (ctl.eyedropperArmed || (ctl.activeTool == ToolType.EYEDROPPER && (stylus || mouse || fingersDraw()))) {
            startEyedrop(e.x, e.y); return
        }

        if (ctl.gridEditing) {
            if (startGridEdit(e.x, e.y)) return
            ctl.gridEditing = false // tapping elsewhere leaves grid editing
        }
        if (startGuideEdit(e.x, e.y, e.eventTime)) return

        // A drag starting on the selection box moves, scales or rotates it.
        if (ctl.selection.isNotEmpty() && startSelectionDrag(e.x, e.y)) return

        val canAct = stylus || mouse || fingersDraw()
        if (!canAct) {
            // Fingers navigate; holding still starts a lasso.
            startNavigate(e)
            scheduleHold(e.x, e.y)
            return
        }
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) { startSlice(e); return }
        when (ctl.activeTool) {
            ToolType.BRUSH -> { startDraw(e); scheduleHold(e.x, e.y) }
            ToolType.SLICE -> startSlice(e)
            ToolType.SELECT -> startLasso(e.x, e.y)
            ToolType.PAN -> startNavigate(e)
            ToolType.EYEDROPPER -> startEyedrop(e.x, e.y)
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        gestureMaxPointers = maxOf(gestureMaxPointers, e.pointerCount)
        val pid = e.getPointerId(idx)
        if (pid < downX.size) { downX[pid] = e.getX(idx); downY[pid] = e.getY(idx) }
        if (isStylus(e, idx)) {
            // Pen lands while a palm/finger is down: the pen wins.
            cancelGesture()
            stylusDown = true
            lastStylusTime = SystemClock.uptimeMillis()
            activePointerId = pid
            gestureFromStylus = true
            mode = Mode.NONE
            when {
                e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER || ctl.activeTool == ToolType.SLICE -> {
                    mode = Mode.SLICE; ctl.sliceBegin(); toCanvas(e.getX(idx), e.getY(idx), lastCanvasPt)
                }
                ctl.activeTool == ToolType.BRUSH -> { mode = Mode.DRAW; beginLive(e, idx) }
                ctl.activeTool == ToolType.SELECT -> startLasso(e.getX(idx), e.getY(idx))
                else -> {}
            }
            return
        }
        if (gestureFromStylus) return // palm while drawing with a pen
        cancelHold()
        when (mode) {
            Mode.DRAW -> { discardLive(); startNavigate(e) }
            Mode.LASSO -> { lassoCount = 0; startNavigate(e) }
            Mode.SLICE -> { ctl.sliceEnd(); startNavigate(e) }
            Mode.SEL_MOVE, Mode.SEL_SCALE, Mode.SEL_ROTATE, Mode.SEL_PINCH -> startSelPinch(e)
            Mode.NAVIGATE -> startNavigate(e)
            else -> {}
        }
    }

    private fun onMove(e: MotionEvent) {
        if (!gestureMoved) {
            for (i in 0 until e.pointerCount) {
                val pid = e.getPointerId(i)
                if (pid < downX.size && hypot(e.getX(i) - downX[pid], e.getY(i) - downY[pid]) > touchSlop) {
                    gestureMoved = true; cancelHold(); break
                }
            }
        }
        val idx = e.findPointerIndex(activePointerId).let { if (it < 0) 0 else it }
        when (mode) {
            Mode.DRAW -> { addLive(e, idx); invalidate() }
            Mode.NAVIGATE -> navigate(e)
            Mode.LASSO -> { addLasso(e.getX(idx), e.getY(idx)); invalidate() }
            Mode.SLICE -> sliceMove(e, idx)
            Mode.SEL_MOVE, Mode.SEL_SCALE, Mode.SEL_ROTATE -> selDrag(e.getX(idx), e.getY(idx))
            Mode.SEL_PINCH -> selPinch(e)
            Mode.EYEDROP -> { sampleAt(e.getX(idx), e.getY(idx)); invalidate() }
            Mode.GUIDE_EDIT -> guideDrag(e.getX(idx), e.getY(idx))
            Mode.GRID_EDIT -> gridDrag(e.getX(idx), e.getY(idx))
            else -> {}
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        val pid = e.getPointerId(e.actionIndex)
        when (mode) {
            Mode.NAVIGATE -> startNavigate(e, excludeIndex = e.actionIndex)
            Mode.SEL_PINCH -> {
                if (e.pointerCount - 1 >= 2) startSelPinch(e, excludeIndex = e.actionIndex)
                else { commitSelectionTransform(); mode = Mode.IGNORE }
            }
            Mode.DRAW, Mode.SLICE, Mode.LASSO -> if (pid == activePointerId) finishPrimary(e, e.actionIndex)
            else -> {}
        }
    }

    private fun onUp(e: MotionEvent) {
        cancelHold()
        when (mode) {
            Mode.DRAW, Mode.SLICE, Mode.LASSO -> finishPrimary(e, 0)
            Mode.NAVIGATE -> {
                ctl.setViewport(currentViewport())
                val quick = e.eventTime - gestureStartTime < 350 && !gestureMoved
                if (quick && gestureMaxPointers == 2) ctl.undo()
                else if (quick && gestureMaxPointers == 3) ctl.redo()
                else if (quick && gestureMaxPointers == 1 && ctl.selection.isNotEmpty()) ctl.clearSelection()
            }
            Mode.SEL_MOVE, Mode.SEL_SCALE, Mode.SEL_ROTATE, Mode.SEL_PINCH -> commitSelectionTransform()
            Mode.EYEDROP -> finishEyedrop()
            Mode.GUIDE_EDIT, Mode.GRID_EDIT -> { editGuideStart = null; editGridStart = null }
            Mode.IGNORE -> {
                val quick = e.eventTime - gestureStartTime < 350 && !gestureMoved
                if (!gestureFromStylus && quick && gestureMaxPointers == 2) ctl.undo()
                else if (!gestureFromStylus && quick && gestureMaxPointers == 3) ctl.redo()
            }
            else -> {}
        }
        if (gestureFromStylus) { stylusDown = false; lastStylusTime = SystemClock.uptimeMillis() }
        mode = Mode.NONE
        activePointerId = -1
        cursor.set(-1f, -1f)
        invalidate()
    }

    private fun cancelGesture() {
        cancelHold()
        when (mode) {
            Mode.DRAW -> { cancelRecognition(); recognized = null; discardLive() }
            Mode.SLICE -> ctl.sliceEnd()
            Mode.SEL_MOVE, Mode.SEL_SCALE, Mode.SEL_ROTATE, Mode.SEL_PINCH -> { selMatrix.reset(); selHasTransform = false }
            Mode.NAVIGATE -> ctl.setViewport(currentViewport())
            Mode.EYEDROP -> { dropSnapshot?.recycle(); dropSnapshot = null }
            else -> {}
        }
        lassoCount = 0
        mode = Mode.NONE
        stylusDown = false
        invalidate()
    }

    private fun finishPrimary(e: MotionEvent, idx: Int) {
        when (mode) {
            Mode.DRAW -> {
                addLive(e, idx)
                livePredictedCount = 0
                cancelRecognition()
                if (liveCount > 0) {
                    var (pts, sm) = finalLivePoints(includePredicted = false)
                    val pre = ctl.drawing.precision
                    if (pre.snapOn && pre.snap.autoComplete && recognized == null) {
                        pts = Snap.autoComplete(pts, nearbyEnds(), 14f * density / scaleOf(view))
                    }
                    ctl.addStroke(liveBrush, pts, sm, measured = pre.measureOn)
                }
                liveCount = 0
                recognized = null
            }
            Mode.SLICE -> ctl.sliceEnd()
            Mode.LASSO -> finishLasso()
            else -> {}
        }
        mode = Mode.IGNORE
        invalidate()
    }

    // ---- hold to select ----
    private fun scheduleHold(x: Float, y: Float) {
        cancelHold()
        val r = Runnable {
            holdPending = null
            if (gestureMoved) return@Runnable
            if (mode == Mode.DRAW || mode == Mode.NAVIGATE) {
                if (mode == Mode.DRAW) discardLive()
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                startLasso(x, y)
                invalidate()
            }
        }
        holdPending = r
        postDelayed(r, ctl.prefs.holdToSelectMs.toLong())
    }

    private fun cancelHold() {
        holdPending?.let { removeCallbacks(it) }
        holdPending = null
    }

    // ---- drawing ----
    private fun startDraw(e: MotionEvent) {
        mode = Mode.DRAW
        beginLive(e, 0)
    }

    private fun beginLive(e: MotionEvent, idx: Int) {
        if (ctl.selection.isNotEmpty()) ctl.clearSelection()
        liveBrush = ctl.slot.brush
        liveCount = 0
        livePredictedCount = 0
        downTime = e.eventTime
        appendSample(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1), e.eventTime)
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, idx: Int, hist: Int): Float {
        if (!isStylus(e, idx) || !ctl.prefs.pressureEnabled) return 0.6f
        val p = if (hist >= 0) e.getHistoricalPressure(idx, hist) else e.getPressure(idx)
        return p.coerceIn(0f, 1f)
    }

    private fun addLive(e: MotionEvent, idx: Int) {
        if (idx < 0 || idx >= e.pointerCount) return
        if (recognized != null) {
            // After recognition, keeping the pen down scales and rotates the shape.
            val p = PointF(); toCanvas(e.getX(idx), e.getY(idx), p)
            transformRecognized(p.x, p.y)
            return
        }
        armRecognition(e.getX(idx), e.getY(idx))
        for (h in 0 until e.historySize) {
            appendSample(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), pressureOf(e, idx, h), e.getHistoricalEventTime(h))
        }
        appendSample(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1), e.eventTime)
        livePredictedCount = 0
        if (ctl.prefs.predictionEnabled) {
            val pe = runCatching { predictor.predict() }.getOrNull()
            if (pe != null) {
                val pi = pe.findPointerIndex(activePointerId).let { if (it < 0) 0 else it }
                if (pi < pe.pointerCount) {
                    val pt = PointF()
                    toCanvas(pe.getX(pi), pe.getY(pi), pt)
                    val base = 0
                    livePredicted[base] = pt.x; livePredicted[base + 1] = pt.y
                    livePredicted[base + 2] = pressureOf(e, idx, -1)
                    livePredicted[base + 3] = (pe.eventTime - downTime).toFloat()
                    livePredictedCount = 1
                }
                pe.recycle()
            }
        }
    }

    private val sampleTmp = PointF()
    private fun appendSample(sx: Float, sy: Float, p: Float, time: Long) {
        toCanvas(sx, sy, sampleTmp)
        activeGuide()?.let { g -> val q = Guides.project(g, sampleTmp.x, sampleTmp.y); sampleTmp.set(q.x, q.y) }
        val st = Stroke.STRIDE
        if (liveCount > 0) {
            val lx = live[(liveCount - 1) * st]; val ly = live[(liveCount - 1) * st + 1]
            if (abs(lx - sampleTmp.x) < 0.01f && abs(ly - sampleTmp.y) < 0.01f) return
        }
        if ((liveCount + 1) * st > live.size) live = live.copyOf(live.size * 2)
        val o = liveCount * st
        live[o] = sampleTmp.x; live[o + 1] = sampleTmp.y; live[o + 2] = p; live[o + 3] = (time - downTime).toFloat()
        liveCount++
    }

    private fun discardLive() {
        cancelRecognition()
        recognized = null
        liveCount = 0
        livePredictedCount = 0
        if (mode == Mode.DRAW) mode = Mode.NONE
        invalidate()
    }

    // ---- navigation ----
    private fun startNavigate(e: MotionEvent, excludeIndex: Int = -1) {
        mode = Mode.NAVIGATE
        navStart.set(view)
        navStartScale = scaleOf(view)
        navStartRot = rotationOf(view)
        centroid(e, excludeIndex, navC0)
        val (d, a) = spread(e, excludeIndex)
        navD0 = d; navA0 = a
    }

    private fun centroid(e: MotionEvent, exclude: Int, out: PointF) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == exclude) continue
            if (gestureFromStylus.not() && isStylus(e, i)) continue
            sx += e.getX(i); sy += e.getY(i); n++
        }
        if (n == 0) { out.set(e.x, e.y); return }
        out.set(sx / n, sy / n)
    }

    /** Distance and angle (degrees) between the first two counted pointers, or (0,0). */
    private fun spread(e: MotionEvent, exclude: Int): Pair<Float, Float> {
        var a = -1; var b = -1
        for (i in 0 until e.pointerCount) {
            if (i == exclude) continue
            if (a < 0) a = i else if (b < 0) { b = i; break }
        }
        if (a < 0 || b < 0) return 0f to 0f
        val dx = e.getX(b) - e.getX(a); val dy = e.getY(b) - e.getY(a)
        return hypot(dx, dy) to Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun navigate(e: MotionEvent) {
        val c = PointF()
        centroid(e, -1, c)
        val (d, a) = spread(e, -1)
        val m = Matrix(navStart)
        m.postTranslate(-navC0.x, -navC0.y)
        if (d > 0f && navD0 > 0f) {
            var ds = d / navD0
            val abs0 = navStartScale * ds
            val clamped = abs0.coerceIn(0.02f, 64f)
            ds = clamped / navStartScale
            if (abs(clamped - 1f) < 0.06f) ds = 1f / navStartScale // snap to 100%
            var da = a - navA0
            while (da > 180f) da -= 360f
            while (da < -180f) da += 360f
            val absRot = navStartRot + da
            val nearest = (absRot / 90f).roundToInt() * 90f
            if (abs(absRot - nearest) < 5f) da = nearest - navStartRot // snap to right angles
            m.postScale(ds, ds)
            m.postRotate(da)
        }
        m.postTranslate(c.x, c.y)
        view.set(m)
        view.invert(inverse)
        publishReadout()
        invalidate()
    }

    // ---- slice ----
    private fun startSlice(e: MotionEvent) {
        mode = Mode.SLICE
        ctl.sliceBegin()
        toCanvas(e.x, e.y, lastCanvasPt)
        cursor.set(e.x, e.y)
        sliceTo(e.x, e.y)
    }

    private fun sliceMove(e: MotionEvent, idx: Int) {
        for (h in 0 until e.historySize) sliceTo(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
        sliceTo(e.getX(idx), e.getY(idx))
        cursor.set(e.getX(idx), e.getY(idx))
        invalidate()
    }

    private fun sliceTo(x: Float, y: Float) {
        val p = PointF()
        toCanvas(x, y, p)
        // Eraser size is in screen pixels, so it feels the same at any zoom.
        val r = (ctl.slot.size / 2f) / scaleOf(view)
        ctl.sliceSegment(lastCanvasPt.x, lastCanvasPt.y, p.x, p.y, r)
        lastCanvasPt.set(p)
    }

    // ---- lasso ----
    private fun startLasso(x: Float, y: Float) {
        mode = Mode.LASSO
        lassoCount = 0
        addLasso(x, y)
    }

    private fun addLasso(x: Float, y: Float) {
        if (lassoCount > 0) {
            val lx = lasso[(lassoCount - 1) * 2]; val ly = lasso[(lassoCount - 1) * 2 + 1]
            if (hypot(lx - x, ly - y) < 2f) return
        }
        if ((lassoCount + 1) * 2 > lasso.size) lasso = lasso.copyOf(lasso.size * 2)
        lasso[lassoCount * 2] = x; lasso[lassoCount * 2 + 1] = y
        lassoCount++
    }

    private fun finishLasso() {
        var length = 0f
        for (i in 1 until lassoCount) length += hypot(lasso[i * 2] - lasso[i * 2 - 2], lasso[i * 2 + 1] - lasso[i * 2 - 1])
        if (lassoCount < 3 || length < 12f * density) {
            // A tap: pick the stroke under it, or clear the selection.
            if (lassoCount >= 1) {
                val p = PointF(); toCanvas(lasso[0], lasso[1], p)
                val hit = EditOps.pick(ctl.drawing, ctl.cache, p.x, p.y, 10f * density / scaleOf(view))
                if (hit != null) ctl.selectIds(setOf(hit.id)) else ctl.clearSelection()
            }
        } else {
            val poly = FloatArray(lassoCount * 2)
            val p = PointF()
            for (i in 0 until lassoCount) {
                toCanvas(lasso[i * 2], lasso[i * 2 + 1], p)
                poly[i * 2] = p.x; poly[i * 2 + 1] = p.y
            }
            ctl.selectLasso(poly)
        }
        lassoCount = 0
    }

    // ---- selection transforms ----
    private fun startSelectionDrag(x: Float, y: Float): Boolean {
        val c = selectionCorners() ?: return false
        val hitR = 22f * density
        val b = ctl.selectionBounds() ?: return false
        val h = rotateHandle(c)
        selMatrix.reset(); selHasTransform = false
        toCanvas(x, y, selStartCanvas)
        if (hypot(h.x - x, h.y - y) < hitR) {
            mode = Mode.SEL_ROTATE
            selCenter.set(b.centerX(), b.centerY())
            selStartAngle = atan2(selStartCanvas.y - selCenter.y, selStartCanvas.x - selCenter.x)
            return true
        }
        val canvasCorners = floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)
        for (i in 0 until 4) {
            if (hypot(c[i * 2] - x, c[i * 2 + 1] - y) < hitR) {
                mode = Mode.SEL_SCALE
                val opp = (i + 2) % 4
                selAnchor.set(canvasCorners[opp * 2], canvasCorners[opp * 2 + 1])
                selCorner.set(canvasCorners[i * 2], canvasCorners[i * 2 + 1])
                return true
            }
        }
        val quad = floatArrayOf(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
        if (Geometry.pointInPolygon(x, y, quad)) {
            mode = Mode.SEL_MOVE
            return true
        }
        return false
    }

    private fun selDrag(x: Float, y: Float) {
        val p = PointF()
        toCanvas(x, y, p)
        selMatrix.reset()
        when (mode) {
            Mode.SEL_MOVE -> {
                val (sx, sy) = snapSelectionMove(p.x - selStartCanvas.x, p.y - selStartCanvas.y)
                selMatrix.setTranslate(sx, sy)
            }
            Mode.SEL_SCALE -> {
                val dx = selCorner.x - selAnchor.x; val dy = selCorner.y - selAnchor.y
                val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-4f)
                val k = (((p.x - selAnchor.x) * dx + (p.y - selAnchor.y) * dy) / l2).coerceIn(0.03f, 50f)
                selMatrix.setScale(k, k, selAnchor.x, selAnchor.y)
            }
            Mode.SEL_ROTATE -> {
                val ang = atan2(p.y - selCenter.y, p.x - selCenter.x)
                var deg = Math.toDegrees((ang - selStartAngle).toDouble()).toFloat()
                val nearest = (deg / 45f).roundToInt() * 45f
                if (abs(deg - nearest) < 4f) deg = nearest
                selMatrix.setRotate(deg, selCenter.x, selCenter.y)
            }
            else -> {}
        }
        selHasTransform = true
        invalidate()
    }

    private fun startSelPinch(e: MotionEvent, excludeIndex: Int = -1) {
        // Keep whatever single-finger transform happened so far by committing it first.
        if (selHasTransform) commitSelectionTransform()
        var n = 0
        val p = PointF()
        for (i in 0 until e.pointerCount) {
            if (i == excludeIndex || n >= 2) continue
            toCanvas(e.getX(i), e.getY(i), p)
            pinchStartPts[n * 2] = p.x; pinchStartPts[n * 2 + 1] = p.y
            n++
        }
        mode = if (n >= 2) Mode.SEL_PINCH else Mode.SEL_MOVE
        if (mode == Mode.SEL_MOVE) toCanvas(e.x, e.y, selStartCanvas)
    }

    private fun selPinch(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val a = PointF(); val b = PointF()
        toCanvas(e.getX(0), e.getY(0), a)
        toCanvas(e.getX(1), e.getY(1), b)
        val src = pinchStartPts
        val d0 = hypot(src[2] - src[0], src[3] - src[1]).coerceAtLeast(1e-3f)
        val d1 = hypot(b.x - a.x, b.y - a.y)
        val ang0 = atan2(src[3] - src[1], src[2] - src[0])
        val ang1 = atan2(b.y - a.y, b.x - a.x)
        val c0x = (src[0] + src[2]) / 2f; val c0y = (src[1] + src[3]) / 2f
        val c1x = (a.x + b.x) / 2f; val c1y = (a.y + b.y) / 2f
        selMatrix.reset()
        selMatrix.postTranslate(-c0x, -c0y)
        val k = (d1 / d0).coerceIn(0.03f, 50f)
        selMatrix.postScale(k, k)
        selMatrix.postRotate(Math.toDegrees((ang1 - ang0).toDouble()).toFloat())
        selMatrix.postTranslate(c1x, c1y)
        selHasTransform = true
        invalidate()
    }

    private fun commitSelectionTransform() {
        if (selHasTransform && !selMatrix.isIdentity) ctl.transformSelection(Matrix(selMatrix))
        selMatrix.reset()
        selHasTransform = false
        invalidate()
    }

    // ---- eyedropper ----
    private var dropSnapshot: android.graphics.Bitmap? = null
    private var dropColor = 0
    private var dropStroke: Stroke? = null
    private val dropPoint = PointF()

    private fun startEyedrop(x: Float, y: Float) {
        cancelHold()
        mode = Mode.EYEDROP
        // Render the canvas alone (no overlays) so blended pixels can be sampled.
        dropSnapshot?.recycle()
        dropSnapshot = runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(ctl.drawing.background)
            c.concat(view)
            Renderer.drawStrokes(c, ctl.drawing, ctl.cache, RenderOptions(scaleOf(view)))
            bmp
        }.getOrNull()
        sampleAt(x, y)
        invalidate()
    }

    private fun sampleAt(x: Float, y: Float) {
        dropPoint.set(x, y)
        val p = PointF()
        toCanvas(x, y, p)
        val hit = EditOps.pick(ctl.drawing, ctl.cache, p.x, p.y, 3f * density / scaleOf(view))
        dropStroke = hit
        val pixel = dropSnapshot?.let { b ->
            val px = x.roundToInt().coerceIn(0, b.width - 1)
            val py = y.roundToInt().coerceIn(0, b.height - 1)
            b.getPixel(px, py) or 0xFF000000.toInt()
        } ?: ctl.drawing.background
        dropColor = if (ctl.prefs.eyedropperAlpha && hit != null) hit.color else pixel
    }

    private fun finishEyedrop() {
        ctl.applyEyedrop(dropColor, dropStroke)
        dropSnapshot?.recycle()
        dropSnapshot = null
        dropStroke = null
    }

    /** Ring above the finger: top half is the sampled color, bottom half the current one. */
    private fun drawLoupe(canvas: Canvas) {
        val cx = dropPoint.x
        val cy = (dropPoint.y - 70f * density).coerceAtLeast(50f * density)
        val rOut = 44f * density
        val rIn = 28f * density
        val oval = RectF(cx - (rOut + rIn) / 2f, cy - (rOut + rIn) / 2f, cx + (rOut + rIn) / 2f, cy + (rOut + rIn) / 2f)
        overlayPaint.pathEffect = null
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = rOut - rIn
        overlayPaint.color = dropColor or 0xFF000000.toInt()
        canvas.drawArc(oval, 180f, 180f, false, overlayPaint)
        overlayPaint.color = ctl.color or 0xFF000000.toInt()
        canvas.drawArc(oval, 0f, 180f, false, overlayPaint)
        overlayPaint.strokeWidth = 1.2f * density
        overlayPaint.color = 0x66000000
        canvas.drawCircle(cx, cy, rOut, overlayPaint)
        canvas.drawCircle(cx, cy, rIn, overlayPaint)
        // crosshair at the sample point
        val r = 9f * density
        overlayPaint.color = Color.WHITE
        overlayPaint.strokeWidth = 3f * density
        canvas.drawLine(dropPoint.x - r, dropPoint.y, dropPoint.x + r, dropPoint.y, overlayPaint)
        canvas.drawLine(dropPoint.x, dropPoint.y - r, dropPoint.x, dropPoint.y + r, overlayPaint)
        overlayPaint.color = Color.BLACK
        overlayPaint.strokeWidth = 1.2f * density
        canvas.drawLine(dropPoint.x - r, dropPoint.y, dropPoint.x + r, dropPoint.y, overlayPaint)
        canvas.drawLine(dropPoint.x, dropPoint.y - r, dropPoint.x, dropPoint.y + r, overlayPaint)
    }

    // ==================================================================
    // Precision: grid, snapping, guides, recognition, measurement
    // ==================================================================

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * resources.displayMetrics.scaledDensity }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun isDarkPaper(): Boolean = androidx.core.graphics.ColorUtils.calculateLuminance(ctl.drawing.background) < 0.4

    private fun activeGuide(): Guide? {
        val p = ctl.drawing.precision
        return if (p.guideOn) p.guide else null
    }

    /** Live points after guide projection (already applied), snapping or recognition, plus a smoothing override. */
    private fun finalLivePoints(includePredicted: Boolean): Pair<FloatArray, Float?> {
        recognized?.let { return it to 0f }
        val total = liveCount + if (includePredicted) livePredictedCount else 0
        val raw = FloatArray(total * Stroke.STRIDE)
        System.arraycopy(live, 0, raw, 0, liveCount * Stroke.STRIDE)
        if (includePredicted && livePredictedCount > 0) {
            System.arraycopy(livePredicted, 0, raw, liveCount * Stroke.STRIDE, livePredictedCount * Stroke.STRIDE)
        }
        val pre = ctl.drawing.precision
        if (activeGuide() != null) return raw to 0f
        if (pre.snapOn && pre.gridOn && (pre.snap.toGrid || pre.snap.align) && total >= 2) {
            return Snap.toGrid(raw, pre.grid, pre.snap, 12f * density / scaleOf(view)) to 0f
        }
        return raw to null
    }

    /** First and last points of strokes near the view, for auto-complete. */
    private fun nearbyEnds(): List<Pt> {
        val d = ctl.drawing
        val out = ArrayList<Pt>()
        val activeOnly = d.precision.snap.activeLayerOnly
        tmpRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val vis = RectF(); inverse.mapRect(vis, tmpRect)
        for (s in d.strokes) {
            if (activeOnly && s.layerId != d.activeLayerId) continue
            if (d.layer(s.layerId)?.visible == false) continue
            val b = ctl.cache.get(s).processed.bounds
            if (!RectF.intersects(b, vis) || s.count < 2) continue
            out.add(Pt(s.pts[0], s.pts[1]))
            val l = (s.count - 1) * Stroke.STRIDE
            out.add(Pt(s.pts[l], s.pts[l + 1]))
            if (out.size > 4000) break
        }
        return out
    }

    // ---- shape recognition (draw, then hold) ----
    private var recognized: FloatArray? = null
    private var recogBase: List<Pt> = emptyList()
    private var recogAnchor = Pt(0f, 0f)
    private var recogRef = Pt(0f, 0f)
    private var recogRunnable: Runnable? = null
    private var recogLastX = 0f
    private var recogLastY = 0f

    private fun armRecognition(sx: Float, sy: Float) {
        val pre = ctl.drawing.precision
        if (!pre.recognitionOn || activeGuide() != null || liveCount < 3) return
        if (recogRunnable != null && hypot(sx - recogLastX, sy - recogLastY) < 2.5f * density) return
        recogLastX = sx; recogLastY = sy
        cancelRecognition()
        val r = Runnable {
            recogRunnable = null
            if (mode != Mode.DRAW || recognized != null || liveCount < 4) return@Runnable
            val xs = FloatArray(liveCount) { live[it * Stroke.STRIDE] }
            val ys = FloatArray(liveCount) { live[it * Stroke.STRIDE + 1] }
            val shape = Recognize.recognize(xs, ys) ?: return@Runnable
            recogBase = shape.points
            recogAnchor = shape.anchor
            recogRef = Pt(xs[liveCount - 1], ys[liveCount - 1])
            recognized = Snap.samplesAlong(shape.points, 0.6f, live[(liveCount - 1) * Stroke.STRIDE + 3])
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            invalidate()
        }
        recogRunnable = r
        postDelayed(r, pre.recognitionMs.toLong())
    }

    private fun cancelRecognition() {
        recogRunnable?.let { removeCallbacks(it) }
        recogRunnable = null
    }

    private fun transformRecognized(x: Float, y: Float) {
        val rx = recogRef.x - recogAnchor.x; val ry = recogRef.y - recogAnchor.y
        val cx = x - recogAnchor.x; val cy = y - recogAnchor.y
        val r0 = hypot(rx, ry); val r1 = hypot(cx, cy)
        if (r0 < 1e-3f) return
        val k = (r1 / r0).coerceIn(0.05f, 20f)
        val a = atan2(cy, cx) - atan2(ry, rx)
        val ca = kotlin.math.cos(a) * k; val sa = kotlin.math.sin(a) * k
        val pts = recogBase.map { p ->
            val dx = p.x - recogAnchor.x; val dy = p.y - recogAnchor.y
            Pt(recogAnchor.x + dx * ca - dy * sa, recogAnchor.y + dx * sa + dy * ca)
        }
        recognized = Snap.samplesAlong(pts, 0.6f, 500f)
        invalidate()
    }

    // ---- guides ----
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var editHandle: GuideHandle? = null
    private var editGuideStart: Guide? = null
    private val editStartCanvas = PointF()
    private var lastCenterTap = 0L

    private fun drawGuideTemplate(canvas: Canvas, g: Guide, scale: Float, visible: RectF) {
        val extent = hypot(visible.width(), visible.height()) * 2f
        val path = Guides.path(g, extent)
        guidePaint.reset(); guidePaint.isAntiAlias = true
        guidePaint.style = Paint.Style.STROKE
        guidePaint.strokeCap = Paint.Cap.ROUND
        guidePaint.strokeJoin = Paint.Join.ROUND
        val dark = isDarkPaper()
        guidePaint.color = if (dark) 0x33FFFFFF else 0x22000000
        guidePaint.strokeWidth = maxOf(ctl.slot.size, 6f * density / scale)
        canvas.drawPath(path, guidePaint)
        guidePaint.color = if (dark) 0x88FFFFFF.toInt() else 0x66000000
        guidePaint.strokeWidth = 1f * density / scale
        canvas.drawPath(path, guidePaint)
    }

    private fun drawGuideHandles(canvas: Canvas, g: Guide) {
        val pts = FloatArray(2)
        for ((h, p) in Guides.handles(g)) {
            pts[0] = p.x; pts[1] = p.y
            view.mapPoints(pts)
            if (h == GuideHandle.CENTER) {
                overlayPaint.style = Paint.Style.STROKE
                overlayPaint.color = 0xFF2E86C1.toInt()
                overlayPaint.strokeWidth = 1.5f * density
                val r = 10f * density
                canvas.drawLine(pts[0] - r, pts[1], pts[0] + r, pts[1], overlayPaint)
                canvas.drawLine(pts[0], pts[1] - r, pts[0], pts[1] + r, overlayPaint)
                canvas.drawCircle(pts[0], pts[1], r * 0.55f, overlayPaint)
            } else drawHandle(canvas, pts[0], pts[1], 7f * density)
        }
        val pre = ctl.drawing.precision
        if (pre.measureOn) {
            val c = Guides.center(g)
            pts[0] = c.x; pts[1] = c.y; view.mapPoints(pts)
            drawLabel(canvas, Guides.measureText(g) { Units.length(it, pre.measure) }, pts[0], pts[1] + 26f * density)
        }
    }

    private fun startGuideEdit(x: Float, y: Float, time: Long): Boolean {
        val g = activeGuide() ?: return false
        val pts = FloatArray(2)
        var best: GuideHandle? = null; var bestD = 24f * density
        for ((h, p) in Guides.handles(g)) {
            pts[0] = p.x; pts[1] = p.y; view.mapPoints(pts)
            val d = hypot(pts[0] - x, pts[1] - y)
            if (d < bestD) { bestD = d; best = h }
        }
        val h = best ?: return false
        if (h == GuideHandle.CENTER && time - lastCenterTap < 320) {
            ctl.updatePrecision { it.copy(guide = Guides.perfect(g)) }
            lastCenterTap = 0
            mode = Mode.IGNORE
            invalidate()
            return true
        }
        if (h == GuideHandle.CENTER) lastCenterTap = time
        mode = Mode.GUIDE_EDIT
        editHandle = h
        editGuideStart = g
        toCanvas(x, y, editStartCanvas)
        return true
    }

    private fun guideDrag(x: Float, y: Float) {
        val h = editHandle ?: return
        val start = editGuideStart ?: return
        val g = activeGuide() ?: return
        val p = PointF(); toCanvas(x, y, p)
        var target = Pt(p.x, p.y)
        // Handles snap to grid points when snapping is on.
        val pre = ctl.drawing.precision
        if (pre.gridOn && pre.snapOn && pre.snap.toGrid) target = Grid.snapPoint(pre.grid, target.x, target.y)
        val ng = Guides.moveHandle(g, h, target, start, if (h == GuideHandle.CENTER) Pt(editStartCanvas.x, editStartCanvas.y) else target)
        ctl.updatePrecision { it.copy(guide = ng) }
        invalidate()
    }

    // ---- on-canvas grid editing ----
    private var gridHandle = -1 // 0..3 vanishing point index, 10 = origin, 11 = rotation
    private var editGridStart: com.omarkallas.inkfield.model.GridSettings? = null

    private fun gridHandlePoints(): List<Pair<Int, Pt>> {
        val g = ctl.drawing.precision.grid
        if (g.type.perspective) return Grid.activeVpIndices(g.type).map { it to g.vps[it] }
        val r = 90f * density / scaleOf(view)
        val a = Math.toRadians(g.angle.toDouble())
        return listOf(
            10 to Pt(g.originX, g.originY),
            11 to Pt(g.originX + (r * kotlin.math.cos(a)).toFloat(), g.originY + (r * kotlin.math.sin(a)).toFloat()),
        )
    }

    private fun drawGridHandles(canvas: Canvas) {
        val pts = FloatArray(2)
        val list = gridHandlePoints()
        val origin = list.firstOrNull { it.first == 10 }?.second
        val rot = list.firstOrNull { it.first == 11 }?.second
        if (origin != null && rot != null) {
            val a = floatArrayOf(origin.x, origin.y, rot.x, rot.y); view.mapPoints(a)
            overlayPaint.style = Paint.Style.STROKE; overlayPaint.color = 0xFF2E86C1.toInt(); overlayPaint.strokeWidth = 1.5f * density
            canvas.drawLine(a[0], a[1], a[2], a[3], overlayPaint)
        }
        for ((_, p) in list) {
            pts[0] = p.x; pts[1] = p.y; view.mapPoints(pts)
            drawHandle(canvas, pts[0], pts[1], 9f * density)
        }
    }

    private fun startGridEdit(x: Float, y: Float): Boolean {
        val pts = FloatArray(2)
        var best = -1; var bestD = 28f * density
        for ((id, p) in gridHandlePoints()) {
            pts[0] = p.x; pts[1] = p.y; view.mapPoints(pts)
            val d = hypot(pts[0] - x, pts[1] - y)
            if (d < bestD) { bestD = d; best = id }
        }
        if (best < 0) return false
        mode = Mode.GRID_EDIT
        gridHandle = best
        editGridStart = ctl.drawing.precision.grid
        return true
    }

    private fun gridDrag(x: Float, y: Float) {
        val p = PointF(); toCanvas(x, y, p)
        val g = ctl.drawing.precision.grid
        val ng = when (gridHandle) {
            10 -> g.copy(originX = p.x, originY = p.y)
            11 -> {
                var deg = Math.toDegrees(atan2(p.y - g.originY, p.x - g.originX).toDouble()).toFloat()
                val near = (deg / 45f).roundToInt() * 45f
                if (abs(deg - near) < 3f) deg = near // snap every 45°
                g.copy(angle = deg)
            }
            in 0..3 -> {
                val v = g.vps.toMutableList()
                v[gridHandle] = Pt(p.x, p.y)
                // 2-point perspective keeps both points on one horizon.
                if (g.type == com.omarkallas.inkfield.model.GridType.PERSP2) {
                    val other = if (gridHandle == 1) 2 else 1
                    v[other] = Pt(v[other].x, p.y)
                }
                g.copy(vps = v)
            }
            else -> g
        }
        ctl.updatePrecision { it.copy(grid = ng) }
        invalidate()
    }

    // ---- selection snapping to key points and the grid ----
    private fun snapSelectionMove(dx: Float, dy: Float): Pair<Float, Float> {
        val pre = ctl.drawing.precision
        if (!pre.snapOn) return dx to dy
        val b = ctl.selectionBounds() ?: return dx to dy
        val keys = listOf(
            Pt(b.left, b.top), Pt(b.right, b.top), Pt(b.right, b.bottom), Pt(b.left, b.bottom), Pt(b.centerX(), b.centerY()),
        )
        val radius = 12f * density / scaleOf(view)
        var bestD = radius; var best: Pair<Float, Float>? = null
        fun consider(k: Pt, t: Pt) {
            val ox = t.x - (k.x + dx); val oy = t.y - (k.y + dy)
            val d = hypot(ox, oy)
            if (d < bestD) { bestD = d; best = (dx + ox) to (dy + oy) }
        }
        if (pre.snap.keyPoints) {
            val sel = ctl.selection
            val d = ctl.drawing
            tmpRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val vis = RectF(); inverse.mapRect(vis, tmpRect)
            var checked = 0
            for (s in d.strokes) {
                if (s.id in sel || checked > 3000) continue
                val ob = ctl.cache.get(s).processed.bounds
                if (!RectF.intersects(ob, vis)) continue
                checked++
                val targets = mutableListOf(Pt(ob.left, ob.top), Pt(ob.right, ob.top), Pt(ob.right, ob.bottom), Pt(ob.left, ob.bottom), Pt(ob.centerX(), ob.centerY()))
                if (s.count >= 2) {
                    targets.add(Pt(s.pts[0], s.pts[1]))
                    val l = (s.count - 1) * Stroke.STRIDE
                    targets.add(Pt(s.pts[l], s.pts[l + 1]))
                }
                for (k in keys) for (t in targets) consider(k, t)
            }
        }
        if (pre.gridOn && pre.snap.toGrid && !pre.grid.type.perspective) {
            for (k in keys) consider(k, Grid.snapPoint(pre.grid, k.x + dx, k.y + dy))
        }
        return best ?: (dx to dy)
    }

    // ---- measurement labels ----
    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val w = textPaint.measureText(text)
        val h = textPaint.textSize
        labelBg.color = 0xE6FFFFFF.toInt()
        val pad = 5f * density
        canvas.drawRoundRect(x - w / 2 - pad, y - h - pad * 0.6f, x + w / 2 + pad, y + pad * 0.8f, 6f * density, 6f * density, labelBg)
        textPaint.color = 0xFF2A2A2E.toInt()
        canvas.drawText(text, x - w / 2, y - 1f * density, textPaint)
    }

    private fun drawMeasureLabels(canvas: Canvas, visible: RectF) {
        val d = ctl.drawing
        val m = d.precision.measure
        val pts = FloatArray(2)
        var shown = 0
        for (s in d.strokes) {
            if (!s.measured || shown > 300) continue
            if (d.layer(s.layerId)?.visible == false) continue
            val pr = ctl.cache.get(s).processed
            if (!RectF.intersects(pr.bounds, visible) || pr.n < 2) continue
            var len = 0f
            for (i in 1 until pr.n) len += hypot(pr.x[i] - pr.x[i - 1], pr.y[i] - pr.y[i - 1])
            // Label at the middle of the path.
            var acc = 0f; var mx = pr.x[0]; var my = pr.y[0]
            for (i in 1 until pr.n) {
                val seg = hypot(pr.x[i] - pr.x[i - 1], pr.y[i] - pr.y[i - 1])
                if (acc + seg >= len / 2f) {
                    val u = if (seg > 0f) (len / 2f - acc) / seg else 0f
                    mx = pr.x[i - 1] + (pr.x[i] - pr.x[i - 1]) * u; my = pr.y[i - 1] + (pr.y[i] - pr.y[i - 1]) * u
                    break
                }
                acc += seg
            }
            pts[0] = mx; pts[1] = my; view.mapPoints(pts)
            drawLabel(canvas, Units.length(len, m), pts[0], pts[1] - 10f * density)
            shown++
        }
        // Live length while drawing.
        if (mode == Mode.DRAW && liveCount > 1) {
            val (lp, _) = finalLivePoints(includePredicted = false)
            var len = 0f
            val n = lp.size / Stroke.STRIDE
            for (i in 1 until n) len += hypot(lp[i * Stroke.STRIDE] - lp[(i - 1) * Stroke.STRIDE], lp[i * Stroke.STRIDE + 1] - lp[(i - 1) * Stroke.STRIDE + 1])
            pts[0] = lp[(n - 1) * Stroke.STRIDE]; pts[1] = lp[(n - 1) * Stroke.STRIDE + 1]; view.mapPoints(pts)
            drawLabel(canvas, Units.length(len, m), pts[0] + 34f * density, pts[1] - 24f * density)
        }
    }
}
