package com.omarkallas.inkfield.engine

import com.omarkallas.inkfield.model.GridSettings
import com.omarkallas.inkfield.model.Pt
import com.omarkallas.inkfield.model.SnapSettings
import com.omarkallas.inkfield.model.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object Snap {

    /** Ramer–Douglas–Peucker simplification; returns indices of kept points. */
    fun rdp(x: FloatArray, y: FloatArray, tol: Float): List<Int> {
        val n = x.size
        if (n <= 2) return (0 until n).toList()
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0f; var idx = -1
            for (i in a + 1 until b) {
                val d = Geometry.distToSegment(x[i], y[i], x[a], y[a], x[b], y[b])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol) {
                keep[idx] = true
                stack.addLast(a to idx); stack.addLast(idx to b)
            }
        }
        return (0 until n).filter { keep[it] }
    }

    /** Builds stroke samples along a polyline, every [step] canvas units, with constant pressure. */
    fun samplesAlong(poly: List<Pt>, pressure: Float, duration: Float, step: Float = 3f): FloatArray {
        if (poly.isEmpty()) return FloatArray(0)
        var total = 0f
        for (i in 1 until poly.size) total += hypot(poly[i].x - poly[i - 1].x, poly[i].y - poly[i - 1].y)
        val out = ArrayList<Float>()
        fun add(x: Float, y: Float, d: Float) {
            out.add(x); out.add(y); out.add(pressure)
            out.add(if (total > 0f) d / total * duration else 0f)
        }
        var dist = 0f
        add(poly[0].x, poly[0].y, 0f)
        for (i in 1 until poly.size) {
            val ax = poly[i - 1].x; val ay = poly[i - 1].y
            val bx = poly[i].x; val by = poly[i].y
            val len = hypot(bx - ax, by - ay)
            val k = max(1, (len / step).roundToInt())
            for (j in 1..k) {
                val u = j.toFloat() / k
                add(ax + (bx - ax) * u, ay + (by - ay) * u, dist + len * u)
            }
            dist += len
        }
        return out.toFloatArray()
    }

    /**
     * Turns raw stroke samples into straight segments along the grid's directions.
     * [tol] is the corner-detection tolerance in canvas units.
     */
    fun toGrid(pts: FloatArray, g: GridSettings, s: SnapSettings, tol: Float): FloatArray {
        val n = pts.size / Stroke.STRIDE
        if (n < 2) return pts
        val x = FloatArray(n) { pts[it * Stroke.STRIDE] }
        val y = FloatArray(n) { pts[it * Stroke.STRIDE + 1] }
        var pSum = 0f
        for (i in 0 until n) pSum += pts[i * Stroke.STRIDE + 2]
        val duration = pts[(n - 1) * Stroke.STRIDE + 3]
        val corners = if (s.allowTurns) rdp(x, y, tol) else listOf(0, n - 1)
        val lattice = s.toGrid && !g.type.perspective
        val out = ArrayList<Pt>()
        var cur = if (lattice) Grid.snapPoint(g, x[0], y[0]) else Pt(x[0], y[0])
        out.add(cur)
        for (ci in 1 until corners.size) {
            val target = corners[ci]
            // Aim from the current (snapped) point at the original corner.
            val vx = x[target] - cur.x; val vy = y[target] - cur.y
            val vl = hypot(vx, vy)
            if (vl < tol * 0.3f) continue
            val dirs = Grid.directions(g, cur.x, cur.y, align = s.align || !lattice)
            var best: Dir? = null; var bestDot = -1f
            for (d in dirs) {
                val dot = abs((vx * d.x + vy * d.y) / vl)
                if (dot > bestDot) { bestDot = dot; best = d }
            }
            val d = best ?: continue
            var len = vx * d.x + vy * d.y
            if (lattice) {
                val st = Grid.stepAlong(g, d)
                if (st > 0f) len = (len / st).roundToInt() * st
            }
            if (abs(len) < 1e-3f) continue
            cur = Pt(cur.x + d.x * len, cur.y + d.y * len)
            out.add(cur)
        }
        if (out.size < 2) {
            // Too short to snap: keep a dot at the snapped start.
            out.add(Pt(out[0].x + 0.01f, out[0].y))
        }
        return samplesAlong(out, (pSum / n).coerceIn(0.05f, 1f), duration)
    }

    /**
     * Moves the stroke's last point onto the nearest stroke end (or its own start)
     * within [radius]. Returns the adjusted samples, or the input when nothing is near.
     */
    fun autoComplete(pts: FloatArray, ends: List<Pt>, radius: Float): FloatArray {
        val n = pts.size / Stroke.STRIDE
        if (n < 3) return pts
        val lx = pts[(n - 1) * Stroke.STRIDE]; val ly = pts[(n - 1) * Stroke.STRIDE + 1]
        val candidates = ends + Pt(pts[0], pts[1])
        var best: Pt? = null; var bestD = radius
        for (c in candidates) {
            val d = hypot(c.x - lx, c.y - ly)
            if (d < bestD && d > 0.01f) { bestD = d; best = c }
        }
        val b = best ?: return pts
        // Pull the tail toward the target, fading the correction over the last few samples.
        val out = pts.copyOf()
        val dx = b.x - lx; val dy = b.y - ly
        val tail = minOf(n - 1, 8)
        for (k in 0..tail) {
            val i = n - 1 - k
            val w = 1f - k.toFloat() / (tail + 1)
            out[i * Stroke.STRIDE] += dx * w
            out[i * Stroke.STRIDE + 1] += dy * w
        }
        return out
    }
}
