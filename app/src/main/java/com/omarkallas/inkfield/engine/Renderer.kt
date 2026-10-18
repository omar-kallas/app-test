package com.omarkallas.inkfield.engine

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.Stroke
import kotlin.math.max
import kotlin.math.roundToInt

/** Processed geometry and path per stroke, rebuilt only when the stroke object changes. */
class StrokeCache {
    class Entry(val stroke: Stroke, val processed: Processed, val path: Path) {
        var blur: BlurMaskFilter? = null
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun drawStrokes(canvas: Canvas, d: Drawing, cache: StrokeCache, opt: RenderOptions) {
        val byLayer = HashMap<Long, MutableList<Stroke>>()
        for (s in d.strokes) byLayer.getOrPut(s.layerId) { ArrayList() }.add(s)
        for (layer in d.layers) {
            if (!layer.visible) continue
            val list = byLayer[layer.id] ?: continue
            val faded = layer.opacity < 0.999f
            val save = if (faded) canvas.saveLayerAlpha(null, (layer.opacity * 255).roundToInt()) else -1
            for (s in list) {
                if (opt.skip != null && s.id in opt.skip) continue
                val e = cache.get(s)
                if (opt.visible != null && !RectF.intersects(e.processed.bounds, opt.visible)) continue
                val dim = opt.highlight != null && s.id !in opt.highlight
                drawEntry(canvas, e, opt.viewScale, if (dim) 0.28f else 1f)
            }
            if (save >= 0) canvas.restoreToCount(save)
        }
    }

    fun drawEntry(canvas: Canvas, e: StrokeCache.Entry, viewScale: Float, alphaMul: Float = 1f) {
        val s = e.stroke
        paint.reset()
        paint.isAntiAlias = true
        val a = Color.alpha(s.color) / 255f * s.opacity * alphaMul
        paint.color = s.color
        paint.alpha = (a * 255f).roundToInt().coerceIn(0, 255)
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
                val blur = e.blur ?: BlurMaskFilter(max(s.size * 0.45f, 0.5f), BlurMaskFilter.Blur.NORMAL).also { e.blur = it }
                paint.maskFilter = blur
            }
            else -> paint.strokeWidth = s.size
        }
        canvas.drawPath(e.path, paint)
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
            drawEntry(canvas, cache.get(s), viewScale * ms, layer?.opacity ?: 1f)
        }
        canvas.restore()
    }

    /** Canvas-space bounds of all strokes, or null when empty. */
    fun contentBounds(d: Drawing, cache: StrokeCache, onlyVisible: Boolean = true): RectF? {
        val r = RectF()
        var any = false
        for (s in d.strokes) {
            if (onlyVisible && d.layer(s.layerId)?.visible == false) continue
            val b = cache.get(s).processed.bounds
            if (b.isEmpty) continue
            if (!any) { r.set(b); any = true } else r.union(b)
        }
        return if (any) r else null
    }
}
