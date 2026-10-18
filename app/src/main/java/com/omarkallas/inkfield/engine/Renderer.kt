package com.omarkallas.inkfield.engine

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.Stroke
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** Processed geometry and path per stroke, rebuilt only when the stroke object changes. */
class StrokeCache {
    class Entry(val stroke: Stroke, val processed: Processed, val path: Path) {
        var blur: BlurMaskFilter? = null
        /** Recorded stamps for STAMP strokes, in canvas space offset by [pictureOrigin]. */
        var picture: Picture? = null
        var pictureLeft = 0f
        var pictureTop = 0f
    }

    private val map = HashMap<Long, Entry>()

    fun get(s: Stroke): Entry {
        val e = map[s.id]
        if (e != null && e.stroke === s) return e
        val pr = Geometry.process(s)
        val ne = Entry(s, pr, Geometry.buildPath(s, pr))
        map[s.id] = ne
        return ne
    }

    /** Drop entries for strokes no longer in the drawing. */
    fun retain(ids: Set<Long>) {
        map.keys.retainAll(ids)
    }

    fun clear() = map.clear()
}

class RenderOptions(
    /** Current canvas-to-screen scale; Wire strokes divide by it to stay screen-constant. */
    val viewScale: Float = 1f,
    /** Canvas-space rectangle to cull against, or null to draw everything. */
    val visible: RectF? = null,
    /** When non-null, strokes outside this set are drawn faded. */
    val highlight: Set<Long>? = null,
    /** Strokes to skip (e.g. a selection drawn separately with a live transform). */
    val skip: Set<Long>? = null,
)

object Renderer {
    // Paints are per thread so exports can render in the background while the screen draws.
    private val paintTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val textPaintTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG) }
    private val paint: Paint get() = paintTL.get()!!
    private val textPaint: Paint get() = textPaintTL.get()!!
    private val dstOut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    private var grainShaderSoft: BitmapShader? = null
    private var grainShaderHard: BitmapShader? = null

    @Synchronized
    private fun grainShader(scale: Float, hard: Boolean): BitmapShader {
        val existing = if (hard) grainShaderHard else grainShaderSoft
        if (existing != null) return existing
        val sh = BitmapShader(BrushLib.grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        sh.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        if (hard) grainShaderHard = sh else grainShaderSoft = sh
        return sh
    }

    fun drawStrokes(canvas: Canvas, d: Drawing, cache: StrokeCache, opt: RenderOptions) {
        val byLayer = HashMap<Long, MutableList<Stroke>>()
        for (s in d.strokes) byLayer.getOrPut(s.layerId) { ArrayList() }.add(s)
        for (layer in d.layers) {
            if (!layer.visible) continue
            val list = byLayer[layer.id] ?: continue
            val faded = layer.opacity < 0.999f
            // Masks erase only within their own layer, so a masked layer renders offscreen.
            val hasMask = list.any { it.brush.isMask }
            val save = if (faded || hasMask) canvas.saveLayerAlpha(null, (layer.opacity * 255).roundToInt()) else -1
            for (s in list) {
                if (opt.skip != null && s.id in opt.skip) continue
                val e = cache.get(s)
                if (opt.visible != null && !s.brush.isMask && !RectF.intersects(e.processed.bounds, opt.visible)) continue
                val dim = opt.highlight != null && s.id !in opt.highlight
                drawEntry(canvas, e, opt.viewScale, if (dim) 0.28f else 1f)
            }
            if (save >= 0) canvas.restoreToCount(save)
        }
    }

    /**
     * Draws one stroke. [live] marks the in-progress preview, where masks are shown as a
     * translucent wash instead of erasing (they need their layer's offscreen buffer to erase).
     */
    fun drawEntry(canvas: Canvas, e: StrokeCache.Entry, viewScale: Float, alphaMul: Float = 1f, live: Boolean = false) {
        val paint = this.paint
        val s = e.stroke
        paint.reset()
        paint.isAntiAlias = true
        val a = Color.alpha(s.color) / 255f * s.opacity * alphaMul
        paint.color = s.color
        paint.alpha = (a * 255f).roundToInt().coerceIn(0, 255)
        when (s.brush) {
            BrushType.TEXT -> { drawText(canvas, s, a); return }
            BrushType.IMAGE -> { drawImage(canvas, s, e, a); return }
            BrushType.STAMP -> { drawStamps(canvas, e, alphaMul); return }
            BrushType.MASK_HARD, BrushType.MASK_SOFT -> {
                paint.style = Paint.Style.FILL
                if (live) {
                    paint.color = 0x55FFFFFF
                    paint.alpha = 140
                } else {
                    paint.alpha = 255
                    paint.xfermode = dstOut
                    if (s.brush == BrushType.MASK_SOFT) {
                        paint.maskFilter = e.blur ?: BlurMaskFilter(max(s.size * 0.35f, 0.5f), BlurMaskFilter.Blur.NORMAL).also { e.blur = it }
                    }
                }
                canvas.drawPath(e.path, paint)
                return
            }
            BrushType.SOFT_PENCIL, BrushType.HARD_PENCIL -> {
                paint.style = Paint.Style.FILL
                paint.shader = grainShader(if (s.brush == BrushType.SOFT_PENCIL) 0.5f else 0.32f, s.brush == BrushType.HARD_PENCIL)
                if (s.brush == BrushType.HARD_PENCIL) paint.alpha = (paint.alpha * 0.75f).roundToInt()
                canvas.drawPath(e.path, paint)
                return
            }
            BrushType.WATERCOLOR -> {
                paint.style = Paint.Style.FILL
                paint.alpha = (paint.alpha * 0.45f).roundToInt()
                paint.maskFilter = e.blur ?: BlurMaskFilter(max(s.size * 0.12f, 0.5f), BlurMaskFilter.Blur.NORMAL).also { e.blur = it }
                canvas.drawPath(e.path, paint)
                // Pigment gathers at the edges.
                paint.maskFilter = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(s.size * 0.06f, 0.4f)
                paint.alpha = (a * 0.35f * 255).roundToInt().coerceIn(0, 255)
                canvas.drawPath(e.path, paint)
                return
            }
            BrushType.FILL, BrushType.DOTTED -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(e.path, paint)
                return
            }
            else -> {}
        }
        if (!s.brush.isCenterline) {
            paint.style = Paint.Style.FILL
            canvas.drawPath(e.path, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        when (s.brush) {
            BrushType.WIRE -> paint.strokeWidth = max(s.size / viewScale, 0.5f / viewScale)
            BrushType.MARKER -> {
                paint.strokeWidth = s.size
                paint.strokeCap = Paint.Cap.SQUARE
                paint.strokeJoin = Paint.Join.BEVEL
            }
            BrushType.AIRBRUSH -> {
                paint.strokeWidth = s.size * 0.6f
                paint.maskFilter = e.blur ?: BlurMaskFilter(max(s.size * 0.45f, 0.5f), BlurMaskFilter.Blur.NORMAL).also { e.blur = it }
            }
            else -> paint.strokeWidth = s.size
        }
        canvas.drawPath(e.path, paint)
    }

    // ---- text ----

    /** Width and height of a text block at [size] canvas units. */
    fun textBox(text: String, size: Float): Pair<Float, Float> {
        textPaint.textSize = max(size, 1f)
        val lines = text.split('\n')
        val w = lines.maxOf { textPaint.measureText(it) }.coerceAtLeast(size * 0.5f)
        return w to lines.size * size * 1.25f
    }

    /** Matrix from the local box (0,0)-(w,h) to the item's frame. */
    fun frameMatrix(s: Stroke, w: Float, h: Float): Matrix? {
        val p = s.pts
        val st = Stroke.STRIDE
        if (p.size < 3 * st) return null
        val m = Matrix()
        val ok = m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, 0f, h), 0, floatArrayOf(p[0], p[1], p[st], p[st + 1], p[2 * st], p[2 * st + 1]), 0, 3)
        return if (ok) m else null
    }

    private fun drawText(canvas: Canvas, s: Stroke, alpha: Float) {
        val text = s.text ?: return
        val (w, h) = textBox(text, s.size)
        val m = frameMatrix(s, w, h) ?: return
        textPaint.color = s.color
        textPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        textPaint.textSize = max(s.size, 1f)
        canvas.save()
        canvas.concat(m)
        val lines = text.split('\n')
        lines.forEachIndexed { i, line -> canvas.drawText(line, 0f, s.size * (1.25f * i + 1f), textPaint) }
        canvas.restore()
    }

    // ---- images ----
    private fun drawImage(canvas: Canvas, s: Stroke, e: StrokeCache.Entry, alpha: Float) {
        val bmp = Assets.get(s.asset)
        if (bmp == null) {
            paint.style = Paint.Style.STROKE
            paint.color = 0x66000000
            paint.strokeWidth = 1f
            canvas.drawPath(e.path, paint)
            return
        }
        val m = frameMatrix(s, bmp.width.toFloat(), bmp.height.toFloat()) ?: return
        paint.isFilterBitmap = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, m, paint)
    }

    // ---- stamp brushes ----
    private fun drawStamps(canvas: Canvas, e: StrokeCache.Entry, alphaMul: Float) {
        val pic = e.picture ?: recordStamps(e).also { e.picture = it }
        val save = if (alphaMul < 0.999f) canvas.saveLayerAlpha(null, (alphaMul * 255).roundToInt()) else canvas.save()
        canvas.translate(e.pictureLeft, e.pictureTop)
        canvas.drawPicture(pic)
        canvas.restoreToCount(save)
    }

    private fun recordStamps(e: StrokeCache.Entry): Picture {
        val s = e.stroke
        val pr = e.processed
        val b = pr.bounds
        val pic = Picture()
        e.pictureLeft = b.left; e.pictureTop = b.top
        val c = pic.beginRecording(ceil(b.width()).toInt().coerceAtLeast(1), ceil(b.height()).toInt().coerceAtLeast(1))
        c.translate(-b.left, -b.top)
        stampInto(c, s, pr, b)
        pic.endRecording()
        return pic
    }

    /** Stamps the stroke's brush along its smoothed path (deterministic per stroke). */
    fun stampInto(c: Canvas, s: Stroke, pr: Processed, bounds: RectF) {
        if (pr.n == 0) return
        val brush = BrushLib.find(s.customBrush) ?: CustomBrush("default", "Round", StampShape.ROUND_SOFT)
        val bmp = BrushLib.stamp(brush)
        val half = bmp.width / 2f
        val rnd = Random(s.id)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val baseAlpha = Color.alpha(s.color) / 255f * s.opacity
        val m = Matrix()
        val grainLayer = if (brush.grain) c.saveLayer(bounds, null) else -1
        fun stampAt(x: Float, y: Float, pressure: Float, angle: Float) {
            val fp = pressure - 0.6f
            val size = (s.size * (1f + brush.sizePressure * fp * 1.6f)).coerceAtLeast(0.05f) * (1f - brush.sizeJitter * rnd.nextFloat())
            val alpha = (baseAlpha * brush.flow * (1f + brush.opacityPressure * fp * 1.6f) * (1f - brush.opacityJitter * rnd.nextFloat())).coerceIn(0f, 1f)
            val rot = (if (brush.followPath) angle else 0f) + brush.rotationJitter * rnd.nextFloat() * 360f
            val off = brush.scatter * size * (rnd.nextFloat() * 2f - 1f)
            val nx = -kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
            val ny = kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()
            m.setTranslate(-half, -half)
            m.postScale(size / bmp.width, size / bmp.height)
            m.postRotate(rot)
            m.postTranslate(x + nx * off, y + ny * off)
            p.color = s.color
            p.alpha = (alpha * 255).roundToInt()
            c.drawBitmap(bmp, m, p)
        }
        if (pr.n == 1) {
            stampAt(pr.x[0], pr.y[0], pr.p[0], 0f)
        } else {
            val step = max(s.size * brush.spacing, 0.35f)
            var carry = 0f
            var count = 0
            for (i in 1 until pr.n) {
                val dx = pr.x[i] - pr.x[i - 1]; val dy = pr.y[i] - pr.y[i - 1]
                val len = hypot(dx, dy)
                if (len <= 0f) continue
                val ang = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                var d = if (i == 1) 0f else step - carry
                while (d <= len && count < 20000) {
                    val u = d / len
                    stampAt(pr.x[i - 1] + dx * u, pr.y[i - 1] + dy * u, pr.p[i - 1] + (pr.p[i] - pr.p[i - 1]) * u, ang)
                    d += step
                    count++
                }
                carry = len - (d - step)
            }
        }
        if (grainLayer >= 0) {
            val gp = Paint().apply {
                shader = grainShader(0.6f, false)
                xfermode = dstIn
            }
            c.drawRect(bounds, gp)
            c.restoreToCount(grainLayer)
        }
    }

    /** Draws a set of strokes with an extra canvas-space transform (live selection preview). */
    fun drawTransformed(canvas: Canvas, strokes: List<Stroke>, cache: StrokeCache, m: Matrix, viewScale: Float, d: Drawing) {
        canvas.save()
        canvas.concat(m)
        val v = FloatArray(9); m.getValues(v)
        val ms = kotlin.math.sqrt(kotlin.math.abs(v[0] * v[4] - v[1] * v[3])).coerceAtLeast(0.01f)
        for (s in strokes) {
            val layer = d.layer(s.layerId)
            if (layer != null && !layer.visible) continue
            if (s.brush.isMask) continue // masks can't preview outside their layer
            drawEntry(canvas, cache.get(s), viewScale * ms, layer?.opacity ?: 1f)
        }
        canvas.restore()
    }

    /** Canvas-space bounds of all strokes, or null when empty. */
    fun contentBounds(d: Drawing, cache: StrokeCache, onlyVisible: Boolean = true): RectF? {
        val r = RectF()
        var any = false
        for (s in d.strokes) {
            if (s.brush.isMask) continue
            if (onlyVisible && d.layer(s.layerId)?.visible == false) continue
            val b = cache.get(s).processed.bounds
            if (b.isEmpty) continue
            if (!any) { r.set(b); any = true } else r.union(b)
        }
        return if (any) r else null
    }
}
