package com.omarkallas.inkfield.engine

import android.graphics.Matrix
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.Stroke
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Pure geometry edits on strokes. */
object EditOps {

    /**
     * Which strokes an edit may touch: normally only content, but while a layer's mask is being
     * edited ([maskLayer]), only that mask's strokes.
     */
    fun eligible(s: Stroke, maskLayer: Long?): Boolean =
        if (maskLayer == null) !s.onMask else s.onMask && s.layerId == maskLayer


    /** Applies [m] to every point. In Scale mode stroke widths follow the transform. */
    fun transform(s: Stroke, m: Matrix, scaleWidth: Boolean): Stroke {
        val pts = s.pts.copyOf()
        val tmp = FloatArray(2)
        var i = 0
        while (i < pts.size) {
            tmp[0] = pts[i]; tmp[1] = pts[i + 1]
            m.mapPoints(tmp)
            pts[i] = tmp[0]; pts[i + 1] = tmp[1]
            i += Stroke.STRIDE
        }
        val v = FloatArray(9); m.getValues(v)
        val k = sqrt(abs(v[0] * v[4] - v[1] * v[3]))
        val size = if (scaleWidth && k > 0f) (s.size * k).coerceIn(0.1f, 2000f) else s.size
        return s.copy(pts = pts, size = size)
    }

    /**
     * Destructive cut along the eraser segment A→B with radius r (canvas units).
     * Hit strokes are converted to their smoothed geometry (so the cut happens
     * exactly where the line is visible), densified, and split where samples
     * fall inside the eraser. Returns null when nothing was hit.
     */
    fun slice(d: Drawing, cache: StrokeCache, ax: Float, ay: Float, bx: Float, by: Float, r: Float, maskLayer: Long? = null): Drawing? {
        val minX = minOf(ax, bx) - r; val maxX = maxOf(ax, bx) + r
        val minY = minOf(ay, by) - r; val maxY = maxOf(ay, by) + r
        var changed = false
        var nextId = d.nextId
        val out = ArrayList<Stroke>(d.strokes.size + 4)
        for (s in d.strokes) {
            val layer = d.layer(s.layerId)
            if (layer == null || !layer.visible || layer.locked || s.locked || s.brush.isItem || !eligible(s, maskLayer)) { out.add(s); continue }
            val e = cache.get(s)
            val b = e.processed.bounds
            if (b.right < minX || b.left > maxX || b.bottom < minY || b.top > maxY) { out.add(s); continue }
            val pr = e.processed
            // Count the stroke's own half-width so touching its visible edge cuts it.
            val avgHalf = if (pr.n > 0) pr.w.average().toFloat() else 0f
            val reach = r + (if (s.brush.isCenterline) s.size * 0.5f else avgHalf * 0.8f)
            // Quick hit test on the visible geometry.
            var hit = false
            if (pr.n == 1) hit = Geometry.distToSegment(pr.x[0], pr.y[0], ax, ay, bx, by) <= reach + pr.w[0]
            for (i in 1 until pr.n) {
                val hw = if (s.brush.isCenterline) 0f else max(pr.w[i], pr.w[i - 1])
                if (Geometry.segSegDist(pr.x[i - 1], pr.y[i - 1], pr.x[i], pr.y[i], ax, ay, bx, by) <= reach + hw * 0.5f) { hit = true; break }
            }
            if (!hit) { out.add(s); continue }
            // Densify processed points so the cut is clean even on straight, sparse strokes.
            val step = max(r / 3f, 0.5f)
            val dense = ArrayList<FloatArray>()
            for (i in 0 until pr.n) {
                if (i > 0) {
                    val dist = hypot(pr.x[i] - pr.x[i - 1], pr.y[i] - pr.y[i - 1])
                    val k = ceil(dist / step).toInt()
                    for (j in 1 until k) {
                        val u = j.toFloat() / k
                        dense.add(floatArrayOf(
                            pr.x[i - 1] + (pr.x[i] - pr.x[i - 1]) * u,
                            pr.y[i - 1] + (pr.y[i] - pr.y[i - 1]) * u,
                            pr.p[i - 1] + (pr.p[i] - pr.p[i - 1]) * u,
                            pr.t[i - 1] + (pr.t[i] - pr.t[i - 1]) * u,
                        ))
                    }
                }
                dense.add(floatArrayOf(pr.x[i], pr.y[i], pr.p[i], pr.t[i]))
            }
            // Only rewrite the stroke if the eraser actually removes part of it.
            if (dense.none { q -> Geometry.distToSegment(q[0], q[1], ax, ay, bx, by) <= reach }) { out.add(s); continue }
            changed = true
            var run = ArrayList<FloatArray>()
            fun flush() {
                if (run.size >= 2) {
                    val pts = FloatArray(run.size * Stroke.STRIDE)
                    run.forEachIndexed { idx, q -> System.arraycopy(q, 0, pts, idx * Stroke.STRIDE, Stroke.STRIDE) }
                    out.add(s.copy(id = nextId++, pts = pts, smoothing = 0f))
                }
                run = ArrayList()
            }
            for (q in dense) {
                val inside = Geometry.distToSegment(q[0], q[1], ax, ay, bx, by) <= reach
                if (inside) flush() else run.add(q)
            }
            flush()
        }
        return if (changed) d.copy(strokes = out, nextId = nextId) else null
    }

    /** Ids of strokes the lasso polygon selects. */
    fun lasso(d: Drawing, cache: StrokeCache, poly: FloatArray, partial: Boolean, activeLayerOnly: Boolean, includeLocked: Boolean = false, maskLayer: Long? = null): Set<Long> {
        if (poly.size < 6) return emptySet()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in poly.indices step 2) {
            minX = minOf(minX, poly[i]); maxX = maxOf(maxX, poly[i])
            minY = minOf(minY, poly[i + 1]); maxY = maxOf(maxY, poly[i + 1])
        }
        val result = HashSet<Long>()
        for (s in d.strokes) {
            val layer = d.layer(s.layerId) ?: continue
            if (!layer.visible || layer.locked) continue
            if (s.locked && !includeLocked) continue
            if (!eligible(s, maskLayer)) continue
            if (activeLayerOnly && s.layerId != d.activeLayerId) continue
            val pr = cache.get(s).processed
            val b = pr.bounds
            if (b.right < minX || b.left > maxX || b.bottom < minY || b.top > maxY) continue
            if (pr.n == 0) continue
            if (partial) {
                for (i in 0 until pr.n) if (Geometry.pointInPolygon(pr.x[i], pr.y[i], poly)) { result.add(s.id); break }
            } else {
                var all = true
                for (i in 0 until pr.n) if (!Geometry.pointInPolygon(pr.x[i], pr.y[i], poly)) { all = false; break }
                if (all) result.add(s.id)
            }
        }
        return result
    }

    /** Topmost unlocked stroke within [r] of the point, or null. */
    fun pick(d: Drawing, cache: StrokeCache, x: Float, y: Float, r: Float, includeLocked: Boolean = false, maskLayer: Long? = null): Stroke? {
        for (s in d.strokes.asReversed()) {
            val layer = d.layer(s.layerId) ?: continue
            if (!layer.visible || layer.locked) continue
            if (s.locked && !includeLocked) continue
            if (!eligible(s, maskLayer)) continue
            val pr = cache.get(s).processed
            if (s.brush == com.omarkallas.inkfield.model.BrushType.FLOOD) {
                // A fill is hit only where its mask is actually painted.
                val mask = Assets.alphaMask(s.asset) ?: continue
                val fm = Renderer.frameMatrix(s, mask.width.toFloat(), mask.height.toFloat()) ?: continue
                val inv = android.graphics.Matrix()
                if (!fm.invert(inv)) continue
                val q = floatArrayOf(x, y); inv.mapPoints(q)
                val ix = q[0].toInt(); val iy = q[1].toInt()
                if (ix in 0 until mask.width && iy in 0 until mask.height && android.graphics.Color.alpha(mask.getPixel(ix, iy)) > 40) return s
                continue
            }
            if (s.brush.isItem) {
                val poly = FloatArray(pr.n * 2) { if (it % 2 == 0) pr.x[it / 2] else pr.y[it / 2] }
                if (pr.n >= 3 && Geometry.pointInPolygon(x, y, poly)) return s
                continue
            }
            if (s.brush == com.omarkallas.inkfield.model.BrushType.FILL && pr.n >= 3) {
                val poly = FloatArray(pr.n * 2) { if (it % 2 == 0) pr.x[it / 2] else pr.y[it / 2] }
                if (Geometry.pointInPolygon(x, y, poly)) return s
            }
            val b = pr.bounds
            if (x < b.left - r || x > b.right + r || y < b.top - r || y > b.bottom + r) continue
            val hwMax = if (s.brush.isCenterline) s.size * 0.5f else 0f
            if (pr.n == 1 && hypot(pr.x[0] - x, pr.y[0] - y) <= r + pr.w[0] + hwMax) return s
            for (i in 1 until pr.n) {
                val hw = if (s.brush.isCenterline) hwMax else pr.w[i]
                if (Geometry.distToSegment(x, y, pr.x[i - 1], pr.y[i - 1], pr.x[i], pr.y[i]) <= r + hw) return s
            }
        }
        return null
    }

    /**
     * Pushes strokes near (cx, cy) by (dx, dy) with a smooth falloff over radius [r].
     * Strokes are first converted to their visible (smoothed, densified) geometry so they bend where they are seen;
     * [converted] remembers which strokes were already converted during this gesture.
     */
    fun nudge(d: Drawing, cache: StrokeCache, cx: Float, cy: Float, dx: Float, dy: Float, r: Float, converted: MutableSet<Long>, maskLayer: Long? = null): Drawing? {
        var changed = false
        val out = ArrayList<Stroke>(d.strokes.size)
        for (s in d.strokes) {
            val layer = d.layer(s.layerId)
            if (layer == null || !layer.visible || layer.locked || s.locked || s.brush.isItem || s.brush.isMask || !eligible(s, maskLayer)) { out.add(s); continue }
            val b = cache.get(s).processed.bounds
            if (cx < b.left - r || cx > b.right + r || cy < b.top - r || cy > b.bottom + r) { out.add(s); continue }
            var pts = s.pts
            var smoothing = s.smoothing
            if (s.id !in converted) {
                val pr = cache.get(s).processed
                val list = ArrayList<Float>()
                val step = max(r / 6f, 0.5f)
                for (i in 0 until pr.n) {
                    if (i > 0) {
                        val dist = hypot(pr.x[i] - pr.x[i - 1], pr.y[i] - pr.y[i - 1])
                        val k = ceil(dist / step).toInt()
                        for (j in 1 until k) {
                            val u = j.toFloat() / k
                            list.add(pr.x[i - 1] + (pr.x[i] - pr.x[i - 1]) * u); list.add(pr.y[i - 1] + (pr.y[i] - pr.y[i - 1]) * u)
                            list.add(pr.p[i - 1] + (pr.p[i] - pr.p[i - 1]) * u); list.add(pr.t[i - 1] + (pr.t[i] - pr.t[i - 1]) * u)
                        }
                    }
                    list.add(pr.x[i]); list.add(pr.y[i]); list.add(pr.p[i]); list.add(pr.t[i])
                }
                pts = list.toFloatArray()
                smoothing = 0f
            }
            var moved = false
            val np = pts.copyOf()
            var i = 0
            while (i < np.size) {
                val dist = hypot(np[i] - cx, np[i + 1] - cy)
                if (dist < r) {
                    val u = 1f - (dist / r) * (dist / r)
                    val w = u * u
                    np[i] += dx * w; np[i + 1] += dy * w
                    moved = true
                }
                i += Stroke.STRIDE
            }
            if (moved) {
                converted.add(s.id)
                out.add(s.copy(pts = np, smoothing = smoothing))
                changed = true
            } else out.add(s)
        }
        return if (changed) d.copy(strokes = out) else null
    }
}
