package com.omarkallas.inkfield.engine

import android.graphics.Path
import android.graphics.RectF
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Stroke
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A stroke after smoothing and width calculation, in canvas units.
 * [w] is the half-width at each point for variable-width brushes.
 */
class Processed(
    val n: Int,
    val x: FloatArray,
    val y: FloatArray,
    val p: FloatArray,
    val t: FloatArray,
    val w: FloatArray,
) {
    val bounds = RectF()

    fun computeBounds(pad: Float) {
        if (n == 0) { bounds.setEmpty(); return }
        var minX = x[0]; var maxX = x[0]; var minY = y[0]; var maxY = y[0]
        for (i in 1 until n) {
            if (x[i] < minX) minX = x[i]; if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]; if (y[i] > maxY) maxY = y[i]
        }
        bounds.set(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }
}

object Geometry {

    fun process(s: Stroke): Processed {
        val n0 = s.count
        if (n0 == 0) return Processed(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
        val st = Stroke.STRIDE
        var x = FloatArray(n0) { s.pts[it * st] }
        var y = FloatArray(n0) { s.pts[it * st + 1] }
        var p = FloatArray(n0) { s.pts[it * st + 2] }
        var t = FloatArray(n0) { s.pts[it * st + 3] }

        // Remove consecutive duplicates so normals stay defined.
        run {
            val keep = BooleanArray(n0) { true }
            var last = 0
            for (i in 1 until n0) {
                if (abs(x[i] - x[last]) < 0.05f && abs(y[i] - y[last]) < 0.05f) keep[i] = false else last = i
            }
            if (n0 > 1 && !keep[n0 - 1]) { keep[n0 - 1] = true; if (last != 0) keep[last] = false }
            val m = keep.count { it }
            if (m != n0) {
                val nx = FloatArray(m); val ny = FloatArray(m); val np = FloatArray(m); val nt = FloatArray(m)
                var j = 0
                for (i in 0 until n0) if (keep[i]) { nx[j] = x[i]; ny[j] = y[i]; np[j] = p[i]; nt[j] = t[i]; j++ }
                x = nx; y = ny; p = np; t = nt
            }
        }
        var n = x.size

        // Raw width factors from velocity / pressure, computed before smoothing.
        val factor = widthFactors(s.brush, x, y, p, t)

        val sm = s.smoothing.coerceIn(0f, 1f)
        if (n >= 2 && sm >= 0.995f) {
            // 100% smoothing: a perfectly straight line between the endpoints.
            val avgF = factor.average().toFloat()
            val avgP = p.average().toFloat()
            x = floatArrayOf(x[0], x[n - 1]); y = floatArrayOf(y[0], y[n - 1])
            p = floatArrayOf(avgP, avgP); t = floatArrayOf(t[0], t[n - 1])
            val w = FloatArray(2) { s.size * 0.5f * avgF }
            return Processed(2, x, y, p, t, w).also { it.computeBounds(maxHalfWidth(s)) }
        }

        var f = factor
        if (n >= 3 && sm > 0f) {
            val passes = (sm * sm * 48f).toInt().coerceAtLeast(1)
            x = laplacian(x, passes); y = laplacian(y, passes)
            f = laplacian(f, passes); p = laplacian(p, passes)
            // Past 50%, blend toward the straight line between the endpoints.
            val k = max(0f, (sm - 0.5f) / 0.5f).let { it * it * it }
            if (k > 0f) {
                val ax = x[0]; val ay = y[0]; val bx = x[n - 1]; val by = y[n - 1]
                for (i in 0 until n) {
                    val u = i.toFloat() / (n - 1)
                    x[i] += (ax + (bx - ax) * u - x[i]) * k
                    y[i] += (ay + (by - ay) * u - y[i]) * k
                }
            }
        }

        // Light decimation of points closer than 0.35 units keeps paths small.
        run {
            if (n > 2) {
                val keep = BooleanArray(n) { true }
                var last = 0
                for (i in 1 until n - 1) {
                    if (hypot(x[i] - x[last], y[i] - y[last]) < 0.35f) keep[i] = false else last = i
                }
                val m = keep.count { it }
                if (m != n) {
                    val nx = FloatArray(m); val ny = FloatArray(m); val np = FloatArray(m); val nt = FloatArray(m); val nf = FloatArray(m)
                    var j = 0
                    for (i in 0 until n) if (keep[i]) { nx[j] = x[i]; ny[j] = y[i]; np[j] = p[i]; nt[j] = t[i]; nf[j] = f[i]; j++ }
                    x = nx; y = ny; p = np; t = nt; f = nf; n = m
                }
            }
        }

        val w = FloatArray(n) { s.size * 0.5f * f[it] }
        applyTaper(s.brush, x, y, w)
        return Processed(n, x, y, p, t, w).also { it.computeBounds(maxHalfWidth(s)) }
    }

    fun maxHalfWidth(s: Stroke): Float = when (s.brush) {
        BrushType.AIRBRUSH -> s.size
        BrushType.WIRE -> s.size * 8f + 4f // wire is screen-constant; pad generously for culling
        else -> s.size * 0.75f + 1f
    }

    private fun laplacian(a: FloatArray, passes: Int): FloatArray {
        val n = a.size
        var cur = a.copyOf()
        var nxt = FloatArray(n)
        repeat(passes) {
            nxt[0] = cur[0]; nxt[n - 1] = cur[n - 1]
            for (i in 1 until n - 1) nxt[i] = (cur[i - 1] + 2f * cur[i] + cur[i + 1]) * 0.25f
            val tmp = cur; cur = nxt; nxt = tmp
        }
        return cur
    }

    private fun widthFactors(b: BrushType, x: FloatArray, y: FloatArray, p: FloatArray, t: FloatArray): FloatArray {
        val n = x.size
        val out = FloatArray(n) { 1f }
        when (b) {
            BrushType.PEN, BrushType.FOUNTAIN -> {
                var v = 0f
                for (i in 0 until n) {
                    if (i > 0) {
                        val dt = max(1f, t[i] - t[i - 1])
                        val inst = hypot(x[i] - x[i - 1], y[i] - y[i - 1]) / dt
                        v = if (i == 1) inst else v * 0.75f + inst * 0.25f
                    }
                    out[i] = if (b == BrushType.PEN) (1.15f - 0.30f * v).coerceIn(0.45f, 1.15f)
                    else (0.55f + 0.35f * v).coerceIn(0.55f, 1.45f)
                }
                if (n > 1) out[0] = out[1]
            }
            BrushType.DYNAMIC -> for (i in 0 until n) out[i] = 0.12f + 1.25f * p[i].coerceIn(0f, 1f)
            else -> {}
        }
        return out
    }

    /** Soft taper over the first and last few units for the expressive pens. */
    private fun applyTaper(b: BrushType, x: FloatArray, y: FloatArray, w: FloatArray) {
        if (b != BrushType.PEN && b != BrushType.FOUNTAIN && b != BrushType.DYNAMIC) return
        val n = x.size
        if (n < 4) return
        var total = 0f
        for (i in 1 until n) total += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val taper = min(total * 0.12f, 18f)
        if (taper <= 0f) return
        var d = 0f
        for (i in 0 until n) {
            if (i > 0) d += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            val fromEnd = total - d
            val k = min(min(d, fromEnd) / taper, 1f)
            w[i] *= (0.35f + 0.65f * sqrt(k))
        }
    }

    /**
     * Builds the cached path. Variable-width brushes become a filled outline;
     * centerline brushes become an open path stroked at draw time.
     */
    fun buildPath(s: Stroke, pr: Processed): Path {
        val path = Path()
        val n = pr.n
        if (n == 0) return path
        if (s.brush.isCenterline) {
            path.moveTo(pr.x[0], pr.y[0])
            if (n == 1) { path.lineTo(pr.x[0] + 0.01f, pr.y[0]); return path }
            if (n == 2) { path.lineTo(pr.x[1], pr.y[1]); return path }
            for (i in 1 until n - 1) {
                val mx = (pr.x[i] + pr.x[i + 1]) / 2f
                val my = (pr.y[i] + pr.y[i + 1]) / 2f
                path.quadTo(pr.x[i], pr.y[i], mx, my)
            }
            path.lineTo(pr.x[n - 1], pr.y[n - 1])
            return path
        }
        // Outline: left edge forward, right edge back, round caps at both ends.
        if (n == 1) { path.addCircle(pr.x[0], pr.y[0], max(pr.w[0], 0.3f), Path.Direction.CW); return path }
        val lx = FloatArray(n); val ly = FloatArray(n); val rx = FloatArray(n); val ry = FloatArray(n)
        for (i in 0 until n) {
            val a = max(i - 1, 0); val b = min(i + 1, n - 1)
            var dx = pr.x[b] - pr.x[a]; var dy = pr.y[b] - pr.y[a]
            val len = hypot(dx, dy).let { if (it < 1e-4f) 1f else it }
            dx /= len; dy /= len
            val hw = max(pr.w[i], 0.2f)
            lx[i] = pr.x[i] - dy * hw; ly[i] = pr.y[i] + dx * hw
            rx[i] = pr.x[i] + dy * hw; ry[i] = pr.y[i] - dx * hw
        }
        path.moveTo(lx[0], ly[0])
        for (i in 1 until n) {
            val mx = (lx[i - 1] + lx[i]) / 2f; val my = (ly[i - 1] + ly[i]) / 2f
            path.quadTo(lx[i - 1], ly[i - 1], mx, my)
        }
        path.lineTo(lx[n - 1], ly[n - 1])
        // end cap
        addCapArc(path, pr.x[n - 1], pr.y[n - 1], lx[n - 1], ly[n - 1], rx[n - 1], ry[n - 1])
        for (i in n - 2 downTo 0) {
            val mx = (rx[i + 1] + rx[i]) / 2f; val my = (ry[i + 1] + ry[i]) / 2f
            path.quadTo(rx[i + 1], ry[i + 1], mx, my)
        }
        path.lineTo(rx[0], ry[0])
        addCapArc(path, pr.x[0], pr.y[0], rx[0], ry[0], lx[0], ly[0])
        path.close()
        return path
    }

    /** Semicircle from (fx,fy) to (tx,ty) around (cx,cy), bulging outward. */
    private fun addCapArc(path: Path, cx: Float, cy: Float, fx: Float, fy: Float, tx: Float, ty: Float) {
        val r = hypot(fx - cx, fy - cy)
        if (r < 0.05f) { path.lineTo(tx, ty); return }
        val a0 = atan2(fy - cy, fx - cx)
        // Go the "outer" way: rotate by -PI (clockwise in screen coords for our winding).
        val steps = 6
        for (k in 1..steps) {
            val a = a0 - Math.PI.toFloat() * k / steps
            path.lineTo(cx + r * cos(a), cy + r * sin(a))
        }
    }

    // ---------- hit testing ----------

    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-8f) return hypot(px - ax, py - ay)
        val u = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + u * dx), py - (ay + u * dy))
    }

    /** Minimum distance between segment PQ and segment AB. */
    fun segSegDist(px: Float, py: Float, qx: Float, qy: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        if (segmentsIntersect(px, py, qx, qy, ax, ay, bx, by)) return 0f
        return min(
            min(distToSegment(px, py, ax, ay, bx, by), distToSegment(qx, qy, ax, ay, bx, by)),
            min(distToSegment(ax, ay, px, py, qx, qy), distToSegment(bx, by, px, py, qx, qy)),
        )
    }

    private fun cross(ox: Float, oy: Float, ax: Float, ay: Float, bx: Float, by: Float) =
        (ax - ox) * (by - oy) - (ay - oy) * (bx - ox)

    fun segmentsIntersect(px: Float, py: Float, qx: Float, qy: Float, ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val d1 = cross(ax, ay, bx, by, px, py)
        val d2 = cross(ax, ay, bx, by, qx, qy)
        val d3 = cross(px, py, qx, qy, ax, ay)
        val d4 = cross(px, py, qx, qy, bx, by)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    fun pointInPolygon(px: Float, py: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
