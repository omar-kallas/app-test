package com.omarkallas.inkfield.engine

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.omarkallas.inkfield.model.GridSettings
import com.omarkallas.inkfield.model.GridType
import com.omarkallas.inkfield.model.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A unit direction (undirected for snapping purposes). */
class Dir(val x: Float, val y: Float)

object Grid {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private const val SQRT3 = 1.7320508f

    private fun rad(deg: Float) = deg * PI.toFloat() / 180f

    /** Base angle of the grid's own line families, in degrees, before the grid's rotation. */
    private fun families(g: GridSettings): List<Float> {
        val turn = if (g.portrait) 90f else 0f
        return when (g.type) {
            GridType.DOT, GridType.GRAPH -> listOf(0f, 90f)
            GridType.LINED -> listOf(0f + turn)
            GridType.ISOMETRIC -> listOf(30f + turn, 90f + turn, 150f + turn)
            GridType.TRIANGLE -> listOf(0f + turn, 60f + turn, 120f + turn)
            else -> emptyList()
        }.map { it + g.angle }
    }

    /** Distance between neighboring parallel lines of a family. */
    private fun familySpacing(g: GridSettings): Float = when (g.type) {
        GridType.ISOMETRIC, GridType.TRIANGLE -> g.spacing * SQRT3 / 2f
        else -> g.spacing
    }

    fun lineColor(g: GridSettings, darkPaper: Boolean): Int {
        val base = g.color ?: if (darkPaper) 0xFFFFFFFF.toInt() else 0xFF3A3F47.toInt()
        val a = (g.opacity.coerceIn(0f, 1f) * 255).roundToInt()
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * Draws the grid in canvas space; the caller has already concatenated the view matrix.
     * [visible] is the visible canvas rectangle and [scale] the view scale (line widths stay screen-constant).
     */
    fun draw(canvas: Canvas, g: GridSettings, visible: RectF, scale: Float, darkPaper: Boolean, density: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        val color = lineColor(g, darkPaper)
        paint.color = color
        val thin = g.lineWeight * density * 0.6f / scale
        if (g.type.perspective) { drawPerspective(canvas, g, visible, scale, thin, color, density); return }

        if (g.type == GridType.DOT) {
            drawDots(canvas, g, visible, scale, color, density)
            return
        }
        val sp = familySpacing(g)
        val minor = if (g.type == GridType.GRAPH) g.divisions.coerceAtLeast(1) else 1
        val baseAlpha = paint.alpha
        for (ang in families(g)) {
            // Lines run along `ang`; their normal is ang + 90°.
            val dx = cos(rad(ang)); val dy = sin(rad(ang))
            val nx = -dy; val ny = dx
            var step = sp / minor
            var perMajor = minor
            // Thin out when lines would be closer than ~6 px on screen: drop minors first, then skip majors.
            if (step * scale < 6f * density) { step = sp; perMajor = 1 }
            while (step * scale < 6f * density) step *= 2
            val corners = floatArrayOf(visible.left, visible.top, visible.right, visible.top, visible.right, visible.bottom, visible.left, visible.bottom)
            var kMin = Float.MAX_VALUE; var kMax = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val d = (corners[i * 2] - g.originX) * nx + (corners[i * 2 + 1] - g.originY) * ny
                kMin = minOf(kMin, d); kMax = maxOf(kMax, d)
            }
            val diag = hypot(visible.width(), visible.height())
            val cx = visible.centerX(); val cy = visible.centerY()
            val tc = (cx - g.originX) * dx + (cy - g.originY) * dy
            val k0 = floor(kMin / step).toInt(); val k1 = ceil(kMax / step).toInt()
            if (k1 - k0 > 2000) continue
            for (k in k0..k1) {
                val majorLine = perMajor == 1 || Math.floorMod(k, perMajor) == 0
                paint.strokeWidth = if (majorLine) thin * 1.6f else thin
                paint.alpha = if (majorLine) baseAlpha else (baseAlpha * 0.55f).roundToInt()
                val px = g.originX + nx * k * step + dx * tc
                val py = g.originY + ny * k * step + dy * tc
                canvas.drawLine(px - dx * diag, py - dy * diag, px + dx * diag, py + dy * diag, paint)
            }
        }
    }

    private fun drawDots(canvas: Canvas, g: GridSettings, visible: RectF, scale: Float, color: Int, density: Float) {
        var step = g.spacing
        while (step * scale < 8f * density) step *= 2
        paint.style = Paint.Style.FILL
        paint.color = color
        val r = (1.4f * g.lineWeight * density / scale)
        val a = rad(g.angle)
        val ux = cos(a); val uy = sin(a); val vx = -uy; val vy = ux
        // Range of lattice indices covering the visible rect.
        val corners = floatArrayOf(visible.left, visible.top, visible.right, visible.top, visible.right, visible.bottom, visible.left, visible.bottom)
        var iMin = Float.MAX_VALUE; var iMax = -Float.MAX_VALUE; var jMin = Float.MAX_VALUE; var jMax = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val px = corners[k * 2] - g.originX; val py = corners[k * 2 + 1] - g.originY
            val i = (px * ux + py * uy) / step; val j = (px * vx + py * vy) / step
            iMin = minOf(iMin, i); iMax = maxOf(iMax, i); jMin = minOf(jMin, j); jMax = maxOf(jMax, j)
        }
        val i0 = floor(iMin).toInt(); val i1 = ceil(iMax).toInt(); val j0 = floor(jMin).toInt(); val j1 = ceil(jMax).toInt()
        if ((i1 - i0).toLong() * (j1 - j0) > 40000) return
        for (i in i0..i1) for (j in j0..j1) {
            canvas.drawCircle(g.originX + (ux * i + vx * j) * step, g.originY + (uy * i + vy * j) * step, r, paint)
        }
        paint.style = Paint.Style.STROKE
    }

    fun vpCount(t: GridType) = when (t) { GridType.PERSP1 -> 1; GridType.PERSP2 -> 2; GridType.PERSP3 -> 3; else -> 0 }

    /** Active vanishing points: 1-pt uses vps[0]; 2-pt uses vps[1], vps[2]; 3-pt adds vps[3]. */
    fun activeVps(g: GridSettings): List<Pt> = when (g.type) {
        GridType.PERSP1 -> listOf(g.vps[0])
        GridType.PERSP2 -> listOf(g.vps[1], g.vps[2])
        GridType.PERSP3 -> listOf(g.vps[1], g.vps[2], g.vps[3])
        else -> emptyList()
    }

    /** Index into [GridSettings.vps] for each active point, matching [activeVps]. */
    fun activeVpIndices(t: GridType): List<Int> = when (t) {
        GridType.PERSP1 -> listOf(0)
        GridType.PERSP2 -> listOf(1, 2)
        GridType.PERSP3 -> listOf(1, 2, 3)
        else -> emptyList()
    }

    private fun drawPerspective(canvas: Canvas, g: GridSettings, visible: RectF, scale: Float, thin: Float, color: Int, density: Float) {
        val vps = activeVps(g)
        val far = hypot(visible.width(), visible.height()) * 4f +
            vps.maxOf { hypot(it.x - visible.centerX(), it.y - visible.centerY()) }
        paint.strokeWidth = thin
        val n = g.density.coerceIn(8, 180)
        for (vp in vps) {
            for (i in 0 until n) {
                val a = 2f * PI.toFloat() * i / n
                canvas.drawLine(vp.x, vp.y, vp.x + cos(a) * far, vp.y + sin(a) * far, paint)
            }
        }
        // Horizon
        paint.strokeWidth = thin * 2f
        val hy = if (g.type == GridType.PERSP1) vps[0].y else (vps[0].y + vps[1].y) / 2f
        if (g.type == GridType.PERSP1) {
            canvas.drawLine(visible.left - far, hy, visible.right + far, hy, paint)
        } else {
            val a = vps[0]; val b = vps[1]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-3f)
            canvas.drawLine(a.x - dx / len * far, a.y - dy / len * far, b.x + dx / len * far, b.y + dy / len * far, paint)
        }
        paint.strokeWidth = thin
        // 1-point: also horizontals and verticals; 2-point: verticals.
        var step = g.spacing * 2
        while (step * scale < 10f * density) step *= 2
        if (g.type == GridType.PERSP1 || g.type == GridType.PERSP2) {
            val x0 = floor(visible.left / step) * step
            var x = x0
            var guard = 0
            while (x <= visible.right && guard++ < 1500) { canvas.drawLine(x, visible.top, x, visible.bottom, paint); x += step }
        }
        if (g.type == GridType.PERSP1) {
            val y0 = floor(visible.top / step) * step
            var y = y0
            var guard = 0
            while (y <= visible.bottom && guard++ < 1500) { canvas.drawLine(visible.left, y, visible.right, y, paint); y += step }
        }
        // Mark vanishing points.
        paint.style = Paint.Style.FILL
        paint.color = color or 0xFF000000.toInt()
        for (vp in vps) canvas.drawCircle(vp.x, vp.y, 3.5f * density / scale, paint)
        paint.style = Paint.Style.STROKE
    }

    // ------------------------------------------------------------------
    // Directions and lattice for snapping
    // ------------------------------------------------------------------

    /** Allowed stroke directions at point (x, y). [align] adds the 45° diagonals on square grids. */
    fun directions(g: GridSettings, x: Float, y: Float, align: Boolean): List<Dir> {
        if (g.type.perspective) {
            val out = ArrayList<Dir>()
            for (vp in activeVps(g)) {
                val dx = vp.x - x; val dy = vp.y - y
                val l = hypot(dx, dy)
                if (l > 1e-3f) out.add(Dir(dx / l, dy / l))
            }
            when (g.type) {
                GridType.PERSP1 -> { out.add(Dir(1f, 0f)); out.add(Dir(0f, 1f)) }
                GridType.PERSP2 -> out.add(Dir(0f, 1f))
                else -> {}
            }
            return out
        }
        val angles = families(g).toMutableList()
        if (g.type == GridType.LINED) angles.add(angles[0] + 90f)
        if (align && (g.type == GridType.GRAPH || g.type == GridType.DOT || g.type == GridType.LINED)) {
            angles.add(g.angle + 45f); angles.add(g.angle + 135f)
        }
        return angles.map { Dir(cos(rad(it)), sin(rad(it))) }
    }

    /** Spacing between lattice points along a direction; 0 when the direction has no lattice (perspective). */
    fun stepAlong(g: GridSettings, d: Dir): Float {
        if (g.type.perspective) return 0f
        val base = when (g.type) {
            GridType.GRAPH -> g.spacing / g.divisions.coerceAtLeast(1)
            else -> g.spacing
        }
        // On square grids a 45° move between lattice points is √2 longer.
        val a = rad(g.angle)
        val local = abs(d.x * cos(a) + d.y * sin(a))
        val isDiagonal = (g.type == GridType.GRAPH || g.type == GridType.DOT || g.type == GridType.LINED) && local in 0.3f..0.95f
        return if (isDiagonal) base * sqrt(2f) else base
    }

    /** Nearest lattice point to (x, y), or the point itself for perspective grids. */
    fun snapPoint(g: GridSettings, x: Float, y: Float): Pt {
        if (g.type.perspective) return Pt(x, y)
        val a = rad(g.angle + if (g.portrait && g.type != GridType.GRAPH && g.type != GridType.DOT) 90f else 0f)
        val ux = cos(a); val uy = sin(a)
        val px = x - g.originX; val py = y - g.originY
        val lx = px * ux + py * uy
        val ly = -px * uy + py * ux
        val (sx, sy) = when (g.type) {
            GridType.GRAPH -> {
                val s = g.spacing / g.divisions.coerceAtLeast(1)
                (lx / s).roundToInt() * s to (ly / s).roundToInt() * s
            }
            GridType.DOT, GridType.LINED -> (lx / g.spacing).roundToInt() * g.spacing to (ly / g.spacing).roundToInt() * g.spacing
            GridType.ISOMETRIC, GridType.TRIANGLE -> {
                // Triangular lattice: basis e1 = (s, 0), e2 = (s/2, s·√3/2) for triangle;
                // isometric is the same lattice turned by 30° (handled by its basis).
                val s = g.spacing
                val (e1x, e1y, e2x, e2y) = if (g.type == GridType.TRIANGLE) listOf(s, 0f, s / 2f, s * SQRT3 / 2f)
                else listOf(0f, s, s * SQRT3 / 2f, s / 2f)
                val det = e1x * e2y - e1y * e2x
                val u = (lx * e2y - ly * e2x) / det
                val v = (e1x * ly - e1y * lx) / det
                var best = 0f to 0f; var bestD = Float.MAX_VALUE
                for (du in 0..1) for (dv in 0..1) {
                    val iu = floor(u) + du; val iv = floor(v) + dv
                    val qx = iu * e1x + iv * e2x; val qy = iu * e1y + iv * e2y
                    val dd = hypot(qx - lx, qy - ly)
                    if (dd < bestD) { bestD = dd; best = qx to qy }
                }
                best
            }
            else -> lx to ly
        }
        return Pt(g.originX + sx * ux - sy * uy, g.originY + sx * uy + sy * ux)
    }
}
