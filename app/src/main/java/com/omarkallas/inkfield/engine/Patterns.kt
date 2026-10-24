package com.omarkallas.inkfield.engine

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Seamless textures a brush can reveal. [gradient] textures hold a tone ramp (for halftone
 * thresholding: dot or line size follows the stroke's opacity); the others are drawn patterns.
 * All textures are generated in code and are alpha-only; they take the stroke's color.
 */
enum class PatternKind(val label: String, val group: String, val gradient: Boolean = false) {
    PAPER("Paper grain", "Texture"),
    GRANULATE("Watercolor granulation", "Texture"),
    CANVAS("Canvas weave", "Texture"),

    DOTS("Halftone dots", "Halftone", true),
    LINES_45("Hatch 45°", "Halftone", true),
    LINES_135("Hatch 135°", "Halftone", true),
    LINES_0("Hatch horizontal", "Halftone", true),
    LINES_90("Hatch vertical", "Halftone", true),
    CROSS("Crosshatch", "Halftone", true),
    CROSS_SQUARE("Grid crosshatch", "Halftone", true),

    CONFETTI("Memphis confetti", "Memphis"),
    ZEBRA("Memphis squiggles", "Memphis"),
    SHAPES("Memphis shapes", "Memphis"),
    POLKA("Memphis polka", "Memphis"),
    ZIGZAG("Memphis zigzag", "Memphis"),
    WAVES("Memphis waves", "Memphis"),
    STRIPES("Memphis stripes", "Memphis"),

    PANEL("Panel wall", "Walls"),
    SLAT("Slat wall", "Walls"),
    BOARD("Board wall", "Walls"),
    BRICK("Brick wall", "Walls"),
    STONE("Stone wall", "Walls"),
    CORRUGATED("Corrugated metal", "Walls"),
    SHINGLES("Shingles", "Walls"),
    CONCRETE("Concrete", "Walls"),
    LAVA("Lava stone", "Walls"),

    BASKET("Wood parquet 1 (basket)", "Tiling"),
    STRIP("Wood parquet 2 (strip)", "Tiling"),
    LADDER("Wood parquet 3 (ladder)", "Tiling"),
    HERRINGBONE("Herringbone parquet", "Tiling"),
    CHEVRON("Chevron parquet", "Tiling"),
    SQUARE_TILE("Square tiles", "Tiling"),
    SUBWAY("Subway tiles", "Tiling"),
    HEX_TILE("Hexagon tiles", "Tiling"),
    CHECKER("Checkerboard", "Tiling"),
    TERRAZZO("Terrazzo", "Tiling"),
}

object Patterns {
    private val cache = HashMap<PatternKind, Bitmap>()

    @Synchronized
    fun bitmap(k: PatternKind): Bitmap = cache.getOrPut(k) { if (k == PatternKind.PAPER) BrushLib.grain() else make(k) }

    /** A repeating shader for [k] where one texel covers [scale] canvas units. */
    fun shader(k: PatternKind, scale: Float): BitmapShader =
        BitmapShader(bitmap(k), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(scale, scale) })
        }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun fromBytes(w: Int, h: Int, f: (Int, Int) -> Float): Bitmap {
        val bytes = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) bytes[y * w + x] = (f(x, y).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bmp
    }

    private class Draw(val w: Int, val h: Int) {
        val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

        /** Draws [block] at all 9 wrap offsets so shapes crossing an edge reappear on the other side. */
        fun wrap(block: Canvas.() -> Unit) {
            for (ox in intArrayOf(-w, 0, w)) for (oy in intArrayOf(-h, 0, h)) {
                c.save(); c.translate(ox.toFloat(), oy.toFloat()); c.block(); c.restore()
            }
        }

        fun stroke(width: Float, alpha: Int = 255): Paint = line.apply { strokeWidth = width; this.alpha = alpha }
        fun solid(alpha: Int = 255): Paint = fill.apply { this.alpha = alpha }
    }

    /** Seamless value noise in 0..1 with [cells] lattice cells per side. */
    private fun noise(n: Int, cells: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val lat = Array(cells) { FloatArray(cells) { rnd.nextFloat() } }
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x * cells / n.toFloat(); val fy = y * cells / n.toFloat()
            val x0 = fx.toInt() % cells; val y0 = fy.toInt() % cells
            val x1 = (x0 + 1) % cells; val y1 = (y0 + 1) % cells
            val tx = fx - floor(fx); val ty = fy - floor(fy)
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            out[y * n + x] = (lat[y0][x0] * (1 - sx) + lat[y0][x1] * sx) * (1 - sy) + (lat[y1][x0] * (1 - sx) + lat[y1][x1] * sx) * sy
        }
        return out
    }

    private fun tri(m: Float): Float = 1f - 2f * min(m, 1f - m) // 1 on the line, 0 halfway between

    private fun frac(v: Float) = v - floor(v)

    // ------------------------------------------------------------------
    // generators
    // ------------------------------------------------------------------

    private fun make(k: PatternKind): Bitmap = when (k) {
        PatternKind.PAPER -> BrushLib.grain()
        PatternKind.GRANULATE -> {
            val n = 256
            val a = noise(n, 16, 11); val b = noise(n, 64, 12); val c = noise(n, 8, 13)
            fromBytes(n, n) { x, y ->
                val i = y * n + x
                val v = 0.5f * a[i] + 0.3f * b[i] + 0.2f * c[i]
                // Pigment settles in the dips: mostly light, with dark speckled pools.
                (0.45f + 0.75f * v * v).coerceIn(0f, 1f)
            }
        }
        PatternKind.CANVAS -> fromBytes(64, 64) { x, y ->
            val wx = sin(x * 2 * PI.toFloat() / 8f); val wy = sin(y * 2 * PI.toFloat() / 8f)
            0.68f + 0.3f * (if ((x / 8 + y / 8) % 2 == 0) wx * wx else wy * wy)
        }

        // ---- halftone ramps (value = how early a pixel turns on) ----
        PatternKind.DOTS -> {
            val c = 16f
            fromBytes(256, 256) { x, y ->
                val px = x + 0.5f; val py = y + 0.5f
                val a0 = floor(px / c).toInt(); val b0 = floor(py / c).toInt()
                var d = Float.MAX_VALUE
                for (a in a0 - 1..a0 + 2) for (b in b0 - 1..b0 + 2) {
                    if (Math.floorMod(a + b, 2) != 0) continue
                    d = min(d, hypot(px - a * c, py - b * c))
                }
                1f - d / c
            }
        }
        PatternKind.LINES_45 -> fromBytes(256, 256) { x, y -> tri(frac((x + y + 1f) / 16f)) }
        PatternKind.LINES_135 -> fromBytes(256, 256) { x, y -> tri(frac((x - y + 256f) / 16f)) }
        PatternKind.LINES_0 -> fromBytes(64, 64) { _, y -> tri(frac((y + 0.5f) / 12f)) }
        PatternKind.LINES_90 -> fromBytes(64, 64) { x, _ -> tri(frac((x + 0.5f) / 12f)) }
        PatternKind.CROSS -> fromBytes(256, 256) { x, y -> max(tri(frac((x + y + 1f) / 16f)), tri(frac((x - y + 256f) / 16f))) }
        PatternKind.CROSS_SQUARE -> fromBytes(64, 64) { x, y -> max(tri(frac((x + 0.5f) / 12f)), tri(frac((y + 0.5f) / 12f))) }

        // ---- Memphis ----
        PatternKind.CONFETTI -> Draw(256, 256).run {
            val rnd = Random(21)
            repeat(46) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
                val s = 7f + rnd.nextFloat() * 9f
                val rot = rnd.nextFloat() * 360f
                val kind = rnd.nextInt(4)
                wrap {
                    save(); translate(x, y); rotate(rot)
                    when (kind) {
                        0 -> drawPath(Path().apply { moveTo(0f, -s); lineTo(s * 0.9f, s * 0.6f); lineTo(-s * 0.9f, s * 0.6f); close() }, solid())
                        1 -> drawCircle(0f, 0f, s * 0.55f, solid())
                        2 -> drawRect(-s, -s * 0.28f, s, s * 0.28f, solid())
                        else -> drawPath(Path().apply { moveTo(-s, 0f); quadTo(-s / 2, -s, 0f, 0f); quadTo(s / 2, s, s, 0f) }, stroke(s * 0.32f))
                    }
                    restore()
                }
            }
            bmp
        }
        PatternKind.ZEBRA -> Draw(256, 256).run {
            val rnd = Random(22)
            for (row in 0 until 8) {
                val y0 = row * 32f + rnd.nextFloat() * 8f
                val amp = 6f + rnd.nextFloat() * 8f
                val phase = rnd.nextFloat() * 2 * PI.toFloat()
                val waves = 1 + rnd.nextInt(3)
                val width = 6f + rnd.nextFloat() * 8f
                val p = Path()
                for (i in 0..64) {
                    val x = i * 4f
                    val y = y0 + amp * sin(phase + x / w * 2 * PI.toFloat() * waves)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                wrap { drawPath(p, stroke(width)) }
            }
            bmp
        }
        PatternKind.SHAPES -> Draw(256, 256).run {
            val rnd = Random(23)
            repeat(26) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
                val s = 9f + rnd.nextFloat() * 10f
                val rot = rnd.nextFloat() * 360f
                val kind = rnd.nextInt(5)
                wrap {
                    save(); translate(x, y); rotate(rot)
                    val sp = stroke(3f)
                    when (kind) {
                        0 -> drawCircle(0f, 0f, s * 0.6f, sp)
                        1 -> drawPath(Path().apply { moveTo(0f, -s); lineTo(s * 0.9f, s * 0.6f); lineTo(-s * 0.9f, s * 0.6f); close() }, sp)
                        2 -> drawPath(Path().apply { moveTo(-s, 0f); lineTo(-s / 2, -s / 2); lineTo(0f, 0f); lineTo(s / 2, -s / 2); lineTo(s, 0f) }, sp)
                        3 -> { drawLine(-s * 0.6f, 0f, s * 0.6f, 0f, sp); drawLine(0f, -s * 0.6f, 0f, s * 0.6f, sp) }
                        else -> drawRect(-s * 0.5f, -s * 0.5f, s * 0.5f, s * 0.5f, sp)
                    }
                    restore()
                }
            }
            bmp
        }
        PatternKind.POLKA -> Draw(64, 64).run {
            wrap { drawCircle(16f, 16f, 6f, solid()); drawCircle(48f, 48f, 6f, solid()) }
            bmp
        }
        PatternKind.ZIGZAG -> Draw(64, 64).run {
            for (row in 0 until 4) {
                val y = row * 16f + 8f
                val p = Path().apply { moveTo(-8f, y); var x = -8f; var up = true; while (x < 72f) { x += 8f; lineTo(x, if (up) y - 5f else y + 5f); up = !up } }
                wrap { drawPath(p, stroke(2.5f)) }
            }
            bmp
        }
        PatternKind.WAVES -> Draw(128, 64).run {
            for (row in 0 until 4) {
                val y0 = row * 16f + 8f
                val p = Path()
                for (i in 0..64) { val x = i * 2f; val y = y0 + 4f * sin(x / 128f * 4 * PI.toFloat()); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                wrap { drawPath(p, stroke(2.5f)) }
            }
            bmp
        }
        PatternKind.STRIPES -> fromBytes(64, 64) { x, y -> if (frac((x + y) / 32f) < 0.5f) 1f else 0f }

        // ---- exterior walls ----
        PatternKind.PANEL -> Draw(64, 64).run {
            wrap {
                drawLine(0f, 2f, 64f, 2f, stroke(2f).apply { strokeCap = Paint.Cap.BUTT })
                drawLine(0f, 6f, 64f, 6f, stroke(1.2f, 90).apply { strokeCap = Paint.Cap.BUTT })
                drawLine(0f, 34f, 64f, 34f, stroke(2f).apply { strokeCap = Paint.Cap.BUTT })
                drawLine(0f, 38f, 64f, 38f, stroke(1.2f, 90).apply { strokeCap = Paint.Cap.BUTT })
            }
            bmp
        }
        PatternKind.SLAT -> {
            val n = noise(256, 32, 31)
            val streak = noise(256, 64, 32)
            fromBytes(120, 256) { x, y ->
                val gap = if (x % 12 < 2) 1f else 0f
                val grain = streak[(y * 256 + (x * 256 / 120)) % (256 * 256)]
                val g = if (frac(grain * 6f + n[y * 256 + x * 2 % 256] * 2f) < 0.12f) 0.45f else 0.08f
                max(gap, g)
            }
        }
        PatternKind.BOARD -> Draw(512, 256).run {
            val rnd = Random(33)
            for (row in 0 until 8) {
                val y = row * 32f
                wrap { drawLine(0f, y + 1f, w.toFloat(), y + 1f, stroke(2f).apply { strokeCap = Paint.Cap.BUTT }) }
                // staggered joints
                val j1 = rnd.nextFloat() * w; val j2 = (j1 + w / 2f + rnd.nextFloat() * 60f) % w
                for (jx in floatArrayOf(j1, j2)) wrap { drawLine(jx, y, jx, y + 32f, stroke(1.6f).apply { strokeCap = Paint.Cap.BUTT }) }
                // wood grain: long gently wavy lines
                repeat(5) {
                    val gy = y + 5f + rnd.nextFloat() * 22f
                    val amp = 0.6f + rnd.nextFloat() * 1.8f
                    val ph = rnd.nextFloat() * 6f
                    val p = Path()
                    for (i in 0..64) { val x = i * 8f; val yy = gy + amp * sin(ph + x / w * 2 * PI.toFloat() * 3); if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy) }
                    wrap { drawPath(p, stroke(0.9f, 110)) }
                }
                if (rnd.nextFloat() < 0.6f) {
                    val kx = rnd.nextFloat() * w; val ky = y + 10f + rnd.nextFloat() * 12f
                    wrap { drawOval(kx - 7f, ky - 3f, kx + 7f, ky + 3f, stroke(1f, 150)); drawOval(kx - 3f, ky - 1.2f, kx + 3f, ky + 1.2f, solid(160)) }
                }
            }
            bmp
        }
        PatternKind.BRICK -> Draw(256, 240).run {
            for (row in 0 until 10) {
                val y = row * 24f
                val off = if (row % 2 == 0) 0f else 32f
                wrap {
                    val p = stroke(2.2f).apply { strokeCap = Paint.Cap.BUTT }
                    drawLine(0f, y, 256f, y, p)
                    var x = off
                    while (x < 256f + 64f) { drawLine(x, y, x, y + 24f, p); x += 64f }
                }
            }
            bmp
        }
        PatternKind.STONE -> Draw(256, 256).run {
            val rnd = Random(35)
            var y = 0f
            val rows = 8
            for (row in 0 until rows) {
                val hgt = 256f / rows
                // widths summing to the tile width so rows wrap seamlessly
                val ws = List(5) { 0.6f + rnd.nextFloat() }
                val sum = ws.sum()
                var x = rnd.nextFloat() * 30f
                for (wi in ws) {
                    val sw = wi / sum * 256f
                    val inset = 2.5f
                    val r = 4f + rnd.nextFloat() * 6f
                    val jx = rnd.nextFloat() * 3f; val jy = rnd.nextFloat() * 3f
                    val rect = RectF(x + inset + jx, y + inset + jy, x + sw - inset, y + hgt - inset)
                    wrap { drawRoundRect(rect, r, r, stroke(2f)) }
                    x += sw
                }
                y += hgt
            }
            bmp
        }
        PatternKind.CORRUGATED -> fromBytes(64, 64) { x, _ ->
            val s = (1f + sin(x * 2 * PI.toFloat() / 16f)) / 2f
            s * s * s * 0.85f
        }
        PatternKind.SHINGLES -> Draw(160, 64).run {
            for (row in 0 until 2) {
                val y = row * 32f
                val off = if (row == 0) 0f else 20f
                wrap {
                    var x = off - 40f
                    while (x < 200f) {
                        val p = Path().apply { moveTo(x, y); lineTo(x, y + 22f); quadTo(x, y + 32f, x + 20f, y + 32f); quadTo(x + 40f, y + 32f, x + 40f, y + 22f); lineTo(x + 40f, y) }
                        drawPath(p, stroke(2f))
                        x += 40f
                    }
                }
            }
            bmp
        }
        PatternKind.CONCRETE -> {
            val rnd = Random(37)
            val nz = noise(256, 32, 38)
            val speck = FloatArray(256 * 256) { if (rnd.nextFloat() < 0.04f) 0.25f + rnd.nextFloat() * 0.4f else 0f }
            fromBytes(256, 256) { x, y ->
                // tie holes on a 128 grid
                val hx = (x % 128) - 64f; val hy = (y % 128) - 64f
                val hole = if (hypot(hx, hy) < 5f) 1f else if (hypot(hx, hy) < 6.5f) 0.4f else 0f
                val seam = if (y % 128 == 0 || y % 128 == 1) 0.6f else 0f
                max(max(hole, seam), max(speck[y * 256 + x], nz[y * 256 + x] * 0.18f))
            }
        }
        PatternKind.LAVA -> Draw(256, 256).run {
            val rnd = Random(39)
            repeat(260) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
                val r = 0.8f + rnd.nextFloat() * rnd.nextFloat() * 6f
                wrap { drawCircle(x, y, r, solid(150 + rnd.nextInt(100))) }
            }
            bmp
        }

        // ---- tiling ----
        PatternKind.BASKET -> fromBytes(128, 128) { x, y ->
            val b = 64; val plank = 16
            val bx = x / b; val by = y / b
            val lx = x % b; val ly = y % b
            val border = lx < 2 || ly < 2
            val horiz = (bx + by) % 2 == 0
            val joint = if (horiz) ly % plank < 1 else lx % plank < 1
            if (border) 1f else if (joint) 0.8f else woodGrain(if (horiz) x else y, if (horiz) y else x)
        }
        PatternKind.STRIP -> fromBytes(256, 128) { x, y ->
            val ph = 16; val pl = 128
            val row = y / ph
            val off = (row * 48) % pl
            val edge = y % ph < 1
            val joint = (x + off) % pl < 1
            if (edge || joint) 1f else woodGrain(x, y)
        }
        PatternKind.LADDER -> fromBytes(192, 64) { x, y ->
            // Blocks of planks that alternate direction; every other row is offset by half a block.
            val r = y / 32
            val xs = (x + r * 24) % 192
            val bi = xs / 48; val lx = xs % 48; val ly = y % 32
            val border = lx < 2 || ly < 1
            val joint = if (bi % 2 == 0) lx % 16 < 1 else (ly * 3) % 32 < 3
            if (border) 1f else if (joint) 0.8f else woodGrain(xs, y)
        }
        PatternKind.HERRINGBONE -> fromBytes(256, 256) { x, y ->
            // 1×4 bricks: a cell is part of a horizontal brick when (i+j) mod 8 < 4.
            val cw = 16; val n = 4
            val i = x / cw; val j = y / cw
            val lx = x % cw; val ly = y % cw
            val k = Math.floorMod(i + j, 2 * n)
            val horiz = k < n
            val edge = if (horiz) {
                ly < 1 || ly >= cw - 1 || (k == 0 && lx < 1) || (k == n - 1 && lx >= cw - 1)
            } else {
                lx < 1 || lx >= cw - 1 || (k == n && ly < 1) || (k == 2 * n - 1 && ly >= cw - 1)
            }
            if (edge) 1f else woodGrain(if (horiz) x else y, if (horiz) y else x)
        }
        PatternKind.CHEVRON -> fromBytes(128, 128) { x, y ->
            val band = 32
            val bi = x / band; val lx = x % band
            val shifted = if (bi % 2 == 0) y + lx else y + (band - lx)
            val edge = lx < 1 || Math.floorMod(shifted, 16) < 1
            if (edge) 1f else woodGrain(x, shifted)
        }
        PatternKind.SQUARE_TILE -> fromBytes(64, 64) { x, y -> if (x % 32 < 2 || y % 32 < 2) 1f else 0f }
        PatternKind.SUBWAY -> fromBytes(128, 64) { x, y ->
            val row = y / 16
            val off = if (row % 2 == 0) 0 else 32
            if (y % 16 < 2 || (x + off) % 64 < 2) 1f else 0f
        }
        PatternKind.HEX_TILE -> {
            val w = 208; val h = 180
            val r = 30f
            val sx = w / (4f * sqrt(3f) * r); val sy = h / (6f * r)
            fromBytes(w, h) { x, y ->
                // Pointy hexagons; distance to the nearest center's edge.
                val px = (x + 0.5f) / sx; val py = (y + 0.5f) / sy
                val hw = sqrt(3f) * r
                var best = Float.MAX_VALUE; var second = Float.MAX_VALUE
                val row0 = floor(py / (1.5f * r)).toInt()
                for (row in row0 - 1..row0 + 2) {
                    val cy = row * 1.5f * r
                    val off = if (Math.floorMod(row, 2) == 1) hw / 2f else 0f
                    val col0 = floor((px - off) / hw).toInt()
                    for (col in col0 - 1..col0 + 2) {
                        val d = hypot(px - (col * hw + off), py - cy)
                        if (d < best) { second = best; best = d } else if (d < second) second = d
                    }
                }
                if (second - best < 2.2f) 1f else 0f
            }
        }
        PatternKind.CHECKER -> fromBytes(64, 64) { x, y -> if ((x / 32 + y / 32) % 2 == 0) 1f else 0f }
        PatternKind.TERRAZZO -> Draw(256, 256).run {
            val rnd = Random(41)
            repeat(70) {
                val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
                val s = 2f + rnd.nextFloat() * rnd.nextFloat() * 10f
                val sides = 3 + rnd.nextInt(3)
                val rot = rnd.nextFloat() * 6.28f
                val p = Path()
                for (i in 0 until sides) {
                    val a = rot + i * 6.283f / sides; val rr = s * (0.6f + rnd.nextFloat() * 0.5f)
                    if (i == 0) p.moveTo(x + rr * cos(a), y + rr * sin(a)) else p.lineTo(x + rr * cos(a), y + rr * sin(a))
                }
                p.close()
                val alpha = 120 + rnd.nextInt(135)
                wrap { drawPath(p, solid(alpha)) }
            }
            bmp
        }
    }

    /** Faint wood grain running along x, used inside parquet planks. */
    private fun woodGrain(along: Int, across: Int): Float {
        val v = sin(across * 1.7f + sin(along * 0.05f) * 2.2f)
        return if (v > 0.93f) 0.22f else 0.04f * abs(sin(along * 0.11f + across))
    }
}
