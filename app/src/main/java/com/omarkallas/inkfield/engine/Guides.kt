package com.omarkallas.inkfield.engine

import android.graphics.Path
import com.omarkallas.inkfield.model.Guide
import com.omarkallas.inkfield.model.GuideType
import com.omarkallas.inkfield.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class GuideHandle { CENTER, A, B, RX, RY }

object Guides {
    private fun rad(d: Float) = d * PI.toFloat() / 180f
    private fun deg(r: Float) = r * 180f / PI.toFloat()

    /** A new guide of [type] centered at (cx, cy), sized for a view with [unit] canvas units per ~100 dp. */
    fun create(type: GuideType, cx: Float, cy: Float, unit: Float): Guide = when (type) {
        GuideType.LINE -> Guide(type, Pt(cx, cy), Pt(cx - 1.5f * unit, cy), Pt(cx + 1.5f * unit, cy))
        GuideType.ARC -> Guide(type, Pt(cx, cy), Pt(cx + 1.2f * unit, cy), Pt(cx - 1.2f * unit, cy), sweep = 180f)
        GuideType.ANGLE -> Guide(type, Pt(cx, cy), Pt(cx + 1.5f * unit, cy), Pt(cx + 1.06f * unit, cy - 1.06f * unit))
        GuideType.ELLIPSE -> Guide(type, Pt(cx, cy), Pt(cx, cy), Pt(cx, cy), rx = 1.3f * unit, ry = 0.85f * unit)
        GuideType.RECT -> Guide(type, Pt(cx, cy), Pt(cx, cy), Pt(cx, cy), rx = 1.3f * unit, ry = 0.85f * unit)
    }

    fun center(g: Guide): Pt = when (g.type) {
        GuideType.LINE -> Pt((g.a.x + g.b.x) / 2f, (g.a.y + g.b.y) / 2f)
        else -> g.c
    }

    private fun arcEnd(g: Guide): Pt {
        val r = hypot(g.a.x - g.c.x, g.a.y - g.c.y)
        val a0 = atan2(g.a.y - g.c.y, g.a.x - g.c.x)
        val a1 = a0 + rad(g.sweep)
        return Pt(g.c.x + r * cos(a1), g.c.y + r * sin(a1))
    }

    fun handles(g: Guide): Map<GuideHandle, Pt> {
        val c = center(g)
        return when (g.type) {
            GuideType.LINE, GuideType.ANGLE -> mapOf(GuideHandle.CENTER to c, GuideHandle.A to g.a, GuideHandle.B to g.b)
            GuideType.ARC -> mapOf(GuideHandle.CENTER to c, GuideHandle.A to g.a, GuideHandle.B to arcEnd(g))
            GuideType.ELLIPSE, GuideType.RECT -> {
                val r = rad(g.rot)
                mapOf(
                    GuideHandle.CENTER to c,
                    GuideHandle.RX to Pt(c.x + g.rx * cos(r), c.y + g.rx * sin(r)),
                    GuideHandle.RY to Pt(c.x - g.ry * sin(r), c.y + g.ry * cos(r)),
                )
            }
        }
    }

    fun moveHandle(g: Guide, h: GuideHandle, p: Pt, start: Guide, startPt: Pt): Guide {
        val dx = p.x - startPt.x; val dy = p.y - startPt.y
        return when (h) {
            GuideHandle.CENTER -> start.copy(
                c = Pt(start.c.x + dx, start.c.y + dy),
                a = Pt(start.a.x + dx, start.a.y + dy),
                b = Pt(start.b.x + dx, start.b.y + dy),
            )
            GuideHandle.A -> g.copy(a = p)
            GuideHandle.B -> if (g.type == GuideType.ARC) {
                val a0 = atan2(g.a.y - g.c.y, g.a.x - g.c.x)
                val a1 = atan2(p.y - g.c.y, p.x - g.c.x)
                var sw = deg(a1 - a0)
                while (sw <= 0f) sw += 360f
                while (sw > 360f) sw -= 360f
                g.copy(sweep = sw.coerceIn(5f, 359f))
            } else g.copy(b = p)
            GuideHandle.RX -> g.copy(rx = max(hypot(p.x - g.c.x, p.y - g.c.y), 2f), rot = deg(atan2(p.y - g.c.y, p.x - g.c.x)))
            GuideHandle.RY -> {
                // Distance along the guide's local y axis.
                val r = rad(g.rot)
                val ly = -(p.x - g.c.x) * sin(r) + (p.y - g.c.y) * cos(r)
                g.copy(ry = max(abs(ly), 2f))
            }
        }
    }

    /** Double-tapping the center makes the "perfect" form of the guide. */
    fun perfect(g: Guide): Guide = when (g.type) {
        GuideType.ARC -> g.copy(sweep = 180f)
        GuideType.ANGLE -> {
            val ax = g.a.x - g.c.x; val ay = g.a.y - g.c.y
            val lb = hypot(g.b.x - g.c.x, g.b.y - g.c.y)
            val la = hypot(ax, ay).coerceAtLeast(1e-3f)
            // Keep b on the same side, at 90° from a.
            val cross = ax * (g.b.y - g.c.y) - ay * (g.b.x - g.c.x)
            val s = if (cross >= 0f) 1f else -1f
            g.copy(b = Pt(g.c.x - ay / la * lb * s, g.c.y + ax / la * lb * s))
        }
        GuideType.ELLIPSE, GuideType.RECT -> { val r = (g.rx + g.ry) / 2f; g.copy(rx = r, ry = r) }
        GuideType.LINE -> g
    }

    /** Closest point on the guide to (x, y). */
    fun project(g: Guide, x: Float, y: Float): Pt = when (g.type) {
        GuideType.LINE -> {
            val dx = g.b.x - g.a.x; val dy = g.b.y - g.a.y
            val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
            val u = ((x - g.a.x) * dx + (y - g.a.y) * dy) / l2
            Pt(g.a.x + dx * u, g.a.y + dy * u)
        }
        GuideType.ARC -> {
            val r = hypot(g.a.x - g.c.x, g.a.y - g.c.y)
            val a0 = atan2(g.a.y - g.c.y, g.a.x - g.c.x)
            var t = atan2(y - g.c.y, x - g.c.x) - a0
            while (t < 0f) t += 2f * PI.toFloat()
            val sw = rad(g.sweep)
            if (t > sw) t = if (t - sw < 2f * PI.toFloat() - t) sw else 0f
            Pt(g.c.x + r * cos(a0 + t), g.c.y + r * sin(a0 + t))
        }
        GuideType.ANGLE -> {
            fun onRay(px: Float, py: Float): Pt {
                val dx = px - g.c.x; val dy = py - g.c.y
                val l2 = (dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val u = max(0f, ((x - g.c.x) * dx + (y - g.c.y) * dy) / l2)
                return Pt(g.c.x + dx * u, g.c.y + dy * u)
            }
            val p1 = onRay(g.a.x, g.a.y); val p2 = onRay(g.b.x, g.b.y)
            if (hypot(p1.x - x, p1.y - y) <= hypot(p2.x - x, p2.y - y)) p1 else p2
        }
        GuideType.ELLIPSE -> {
            val r = rad(g.rot); val cr = cos(r); val sr = sin(r)
            val lx = (x - g.c.x) * cr + (y - g.c.y) * sr
            val ly = -(x - g.c.x) * sr + (y - g.c.y) * cr
            val t = atan2(ly / g.ry.coerceAtLeast(1e-3f), lx / g.rx.coerceAtLeast(1e-3f))
            val ex = g.rx * cos(t); val ey = g.ry * sin(t)
            Pt(g.c.x + ex * cr - ey * sr, g.c.y + ex * sr + ey * cr)
        }
        GuideType.RECT -> {
            val r = rad(g.rot); val cr = cos(r); val sr = sin(r)
            var lx = (x - g.c.x) * cr + (y - g.c.y) * sr
            var ly = -(x - g.c.x) * sr + (y - g.c.y) * cr
            lx = lx.coerceIn(-g.rx, g.rx); ly = ly.coerceIn(-g.ry, g.ry)
            // Push to the nearest edge.
            val dl = lx + g.rx; val dr = g.rx - lx; val dt = ly + g.ry; val db = g.ry - ly
            when (min(min(dl, dr), min(dt, db))) {
                dl -> lx = -g.rx
                dr -> lx = g.rx
                dt -> ly = -g.ry
                else -> ly = g.ry
            }
            Pt(g.c.x + lx * cr - ly * sr, g.c.y + lx * sr + ly * cr)
        }
    }

    /** The template shape in canvas space. [extent] is how far a line guide reaches beyond its handles. */
    fun path(g: Guide, extent: Float): Path {
        val p = Path()
        when (g.type) {
            GuideType.LINE -> {
                val dx = g.b.x - g.a.x; val dy = g.b.y - g.a.y
                val l = hypot(dx, dy).coerceAtLeast(1e-3f)
                p.moveTo(g.a.x - dx / l * extent, g.a.y - dy / l * extent)
                p.lineTo(g.b.x + dx / l * extent, g.b.y + dy / l * extent)
            }
            GuideType.ARC -> {
                val r = hypot(g.a.x - g.c.x, g.a.y - g.c.y)
                val a0 = deg(atan2(g.a.y - g.c.y, g.a.x - g.c.x))
                p.addArc(g.c.x - r, g.c.y - r, g.c.x + r, g.c.y + r, a0, g.sweep)
            }
            GuideType.ANGLE -> {
                fun far(px: Float, py: Float): Pt {
                    val dx = px - g.c.x; val dy = py - g.c.y
                    val l = hypot(dx, dy).coerceAtLeast(1e-3f)
                    return Pt(g.c.x + dx / l * (l + extent), g.c.y + dy / l * (l + extent))
                }
                val fa = far(g.a.x, g.a.y); val fb = far(g.b.x, g.b.y)
                p.moveTo(fa.x, fa.y); p.lineTo(g.c.x, g.c.y); p.lineTo(fb.x, fb.y)
            }
            GuideType.ELLIPSE, GuideType.RECT -> {
                val m = android.graphics.Matrix().apply { setRotate(g.rot, g.c.x, g.c.y) }
                if (g.type == GuideType.ELLIPSE) p.addOval(g.c.x - g.rx, g.c.y - g.ry, g.c.x + g.rx, g.c.y + g.ry, Path.Direction.CW)
                else p.addRect(g.c.x - g.rx, g.c.y - g.ry, g.c.x + g.rx, g.c.y + g.ry, Path.Direction.CW)
                p.transform(m)
            }
        }
        return p
    }

    /** Text for the guide's size when Measure is on. */
    fun measureText(g: Guide, fmt: (Float) -> String): String = when (g.type) {
        GuideType.LINE -> fmt(hypot(g.b.x - g.a.x, g.b.y - g.a.y))
        GuideType.ARC -> "r " + fmt(hypot(g.a.x - g.c.x, g.a.y - g.c.y)) + " · ${g.sweep.toInt()}°"
        GuideType.ANGLE -> {
            val a1 = atan2(g.a.y - g.c.y, g.a.x - g.c.x); val a2 = atan2(g.b.y - g.c.y, g.b.x - g.c.x)
            var d = abs(deg(a2 - a1)); if (d > 180f) d = 360f - d
            "${"%.1f".format(d)}°"
        }
        GuideType.ELLIPSE -> if (abs(g.rx - g.ry) < 0.01f) "⌀ " + fmt(g.rx * 2) else fmt(g.rx * 2) + " × " + fmt(g.ry * 2)
        GuideType.RECT -> fmt(g.rx * 2) + " × " + fmt(g.ry * 2)
    }
}
