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

    private enum class Mode { NONE, DRAW, NAVIGATE, LASSO, SLICE, SEL_MOVE, SEL_SCALE, SEL_ROTATE, SEL_PINCH, EYEDROP, IGNORE }

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

        canvas.save()
        canvas.concat(view)
        Renderer.drawStrokes(canvas, d, ctl.cache, RenderOptions(scale, visible, highlight, skip))
        if (skip != null) Renderer.drawTransformed(canvas, ctl.selectedStrokes(), ctl.cache, selMatrix, scale, d)
        if (mode == Mode.DRAW && liveCount > 0) drawLive(canvas, scale)
        canvas.restore()

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
        val total = liveCount + livePredictedCount
        val pts = FloatArray(total * Stroke.STRIDE)
        System.arraycopy(live, 0, pts, 0, liveCount * Stroke.STRIDE)
        if (livePredictedCount > 0) System.arraycopy(livePredicted, 0, pts, liveCount * Stroke.STRIDE, livePredictedCount * Stroke.STRIDE)
        val slot = ctl.slot
        val s = Stroke(-1, liveBrush, slot.size, ctl.color, slot.opacity, slot.smoothing, -1, pts)
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
            Mode.DRAW -> discardLive()
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
                if (liveCount > 0) ctl.addStroke(liveBrush, live.copyOf(liveCount * Stroke.STRIDE))
                liveCount = 0
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
            Mode.SEL_MOVE -> selMatrix.setTranslate(p.x - selStartCanvas.x, p.y - selStartCanvas.y)
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
}
