package com.omarkallas.inkfield.engine

import com.omarkallas.inkfield.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A recognized shape: its outline as a polyline, plus the point it scales and rotates around. */
class Shape(val name: String, val points: List<Pt>, val anchor: Pt)

/**
 * Recognizes a hand-drawn stroke as a line, arc, triangle, rectangle/square,
 * other quadrilateral, circle or ellipse. Returns null when nothing fits well.
 */
object Recognize {

    fun recognize(x: FloatArray, y: FloatArray): Shape? {
        val n = x.size
        if (n < 4) return null
        var len = 0f
        for (i in 1 until n) len += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        if (len < 8f) return null
        val gap = hypot(x[n - 1] - x[0], y[n - 1] - y[0])
        val closed = gap < len * 0.18f

        if (!closed) {
            val idx = Snap.rdp(x, y, len * 0.035f)
            if (idx.size == 2) return Shape("line", listOf(Pt(x[0], y[0]), Pt(x[n - 1], y[n - 1])), Pt(x[0], y[0]))
            if (idx.size == 3) {
                // Two straight parts: an angle / corner.
                val m = idx[1]
                if (maxDeviation(x, y, 0, m) < len * 0.03f && maxDeviation(x, y, m, n - 1) < len * 0.03f) {
                    return Shape("corner", listOf(Pt(x[0], y[0]), Pt(x[m], y[m]), Pt(x[n - 1], y[n - 1])), Pt(x[m], y[m]))
                }
            }
            return arc(x, y, len)
        }

        // Closed shapes
        val tol = len * 0.045f
        val idx = Snap.rdp(x, y, tol).toMutableList()
        // Treat the closing point as the start.
        if (idx.size >= 2 && hypot(x[idx.last()] - x[idx.first()], y[idx.last()] - y[idx.first()]) < len * 0.18f) idx.removeAt(idx.size - 1)
        val corners = idx.map { Pt(x[it], y[it]) }
        val ellipse = fitEllipse(x, y)
        val cornerCount = corners.size
        if (cornerCount == 3 && (ellipse == null || ellipse.second > 0.10f)) {
            val c = centroid(corners)
            return Shape("triangle", corners + corners[0], c)
        }
        if (cornerCount == 4 && (ellipse == null || ellipse.second > 0.07f)) {
            val c = centroid(corners)
            if (corners.indices.all { cornerAngleDeg(corners, it) in 70f..110f }) {
                return Shape("rectangle", rectangleFrom(x, y), c)
            }
            return Shape("quad", corners + corners[0], c)
        }
        if (ellipse != null && ellipse.second < 0.16f) return Shape("ellipse", ellipse.first, centroidOf(x, y))
        return null
    }

    private fun maxDeviation(x: FloatArray, y: FloatArray, a: Int, b: Int): Float {
        var m = 0f
        for (i in a..b) m = max(m, Geometry.distToSegment(x[i], y[i], x[a], y[a], x[b], y[b]))
        return m
    }

    private fun centroid(p: List<Pt>) = Pt(p.map { it.x }.average().toFloat(), p.map { it.y }.average().toFloat())
    private fun centroidOf(x: FloatArray, y: FloatArray) = Pt(x.average().toFloat(), y.average().toFloat())

    private fun cornerAngleDeg(c: List<Pt>, i: Int): Float {
        val p = c[(i - 1 + c.size) % c.size]; val q = c[i]; val r = c[(i + 1) % c.size]
        val ax = p.x - q.x; val ay = p.y - q.y; val bx = r.x - q.x; val by = r.y - q.y
        val d = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by)).coerceAtLeast(1e-6f)
        return (acos(d.coerceIn(-1f, 1f)) * 180f / PI.toFloat())
    }

    /** Principal axes of the points: (center, angle radians, half-width, half-height) of the oriented box. */
    private fun orientedBox(x: FloatArray, y: FloatArray): FloatArray {
        val c = centroidOf(x, y)
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in x.indices) {
            val dx = x[i] - c.x; val dy = y[i] - c.y
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val ang = 0.5f * atan2(2f * sxy, sxx - syy)
        val ca = cos(ang); val sa = sin(ang)
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (i in x.indices) {
            val dx = x[i] - c.x; val dy = y[i] - c.y
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            minU = minOf(minU, u); maxU = maxOf(maxU, u); minV = minOf(minV, v); maxV = maxOf(maxV, v)
        }
        val cu = (minU + maxU) / 2f; val cv = (minV + maxV) / 2f
        return floatArrayOf(c.x + cu * ca - cv * sa, c.y + cu * sa + cv * ca, ang, (maxU - minU) / 2f, (maxV - minV) / 2f)
    }

    private fun rectangleFrom(x: FloatArray, y: FloatArray): List<Pt> {
        val b = orientedBox(x, y)
        var hw = b[3]; var hh = b[4]
        if (abs(hw - hh) < 0.1f * max(hw, hh)) { val m = (hw + hh) / 2f; hw = m; hh = m } // near-square → square
        var ang = b[2]
        // Snap nearly axis-aligned rectangles straight.
        val deg = ang * 180f / PI.toFloat()
        val near = Math.round(deg / 90f) * 90f
        if (abs(deg - near) < 8f) ang = near * PI.toFloat() / 180f
        val ca = cos(ang); val sa = sin(ang)
        fun pt(u: Float, v: Float) = Pt(b[0] + u * ca - v * sa, b[1] + u * sa + v * ca)
        return listOf(pt(-hw, -hh), pt(hw, -hh), pt(hw, hh), pt(-hw, hh), pt(-hw, -hh))
    }

    /** Ellipse polyline and its mean radial error (relative), or null. */
    private fun fitEllipse(x: FloatArray, y: FloatArray): Pair<List<Pt>, Float>? {
        val b = orientedBox(x, y)
        var rx = b[3]; var ry = b[4]
        if (rx < 2f || ry < 2f) return null
        val ca = cos(b[2]); val sa = sin(b[2])
        var err = 0f
        for (i in x.indices) {
            val dx = x[i] - b[0]; val dy = y[i] - b[1]
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            err += abs(sqrt((u / rx) * (u / rx) + (v / ry) * (v / ry)) - 1f)
        }
        err /= x.size
        if (abs(rx - ry) < 0.12f * max(rx, ry)) { val m = (rx + ry) / 2f; rx = m; ry = m } // near-circle → circle
        var ang = b[2]
        val deg = ang * 180f / PI.toFloat()
        val near = Math.round(deg / 90f) * 90f
        if (abs(deg - near) < 8f) ang = near * PI.toFloat() / 180f
        val c2 = cos(ang); val s2 = sin(ang)
        val pts = List(97) { i ->
            val t = 2f * PI.toFloat() * i / 96f
            val u = rx * cos(t); val v = ry * sin(t)
            Pt(b[0] + u * c2 - v * s2, b[1] + u * s2 + v * c2)
        }
        return pts to err
    }

    /** Circular arc through start, middle and end, when the stroke follows it closely. */
    private fun arc(x: FloatArray, y: FloatArray, len: Float): Shape? {
        val n = x.size
        val m = n / 2
        val ax = x[0]; val ay = y[0]; val bx = x[m]; val by = y[m]; val cx = x[n - 1]; val cy = y[n - 1]
        val d = 2f * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-3f) return null
        val ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
        val uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
        val r = hypot(ax - ux, ay - uy)
        if (r > len * 3f) return null
        var err = 0f
        for (i in 0 until n) err += abs(hypot(x[i] - ux, y[i] - uy) - r)
        err /= n
        if (err > r * 0.06f) return null
        val a0 = atan2(ay - uy, ax - ux)
        val am = atan2(by - uy, bx - ux)
        val a1 = atan2(cy - uy, cx - ux)
        // Choose the sweep direction that passes through the middle point.
        fun norm(a: Float): Float { var v = a; while (v < 0) v += 2f * PI.toFloat(); while (v >= 2f * PI.toFloat()) v -= 2f * PI.toFloat(); return v }
        var sweep = norm(a1 - a0)
        if (norm(am - a0) > sweep) sweep -= 2f * PI.toFloat()
        val steps = 64
        val pts = List(steps + 1) { i ->
            val t = a0 + sweep * i / steps
            Pt(ux + r * cos(t), uy + r * sin(t))
        }
        return Shape("arc", pts, Pt(ax, ay))
    }
}
