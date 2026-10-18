package com.omarkallas.inkfield.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    PNG("png", "image/png", "PNG"),
    JPG("jpg", "image/jpeg", "JPG"),
    SVG("svg", "image/svg+xml", "SVG"),
}

class ExportOptions(
    val format: ExportFormat,
    /** Pixels per canvas unit for raster formats (1 = 72 ppi). */
    val scale: Float,
    val includeBackground: Boolean,
    /** Export only these strokes (a selection), or everything when null. */
    val only: Set<Long>? = null,
)

object Exporter {
    private const val MAX_SIDE = 8192
    private const val MAX_PIXELS = 48_000_000

    private fun subset(d: Drawing, only: Set<Long>?): Drawing =
        if (only == null) d else d.copy(strokes = d.strokes.filter { it.id in only })

    fun bounds(d: Drawing, cache: StrokeCache, only: Set<Long>?): RectF {
        val b = Renderer.contentBounds(subset(d, only), cache) ?: RectF(0f, 0f, 800f, 600f)
        val pad = 16f
        return RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
    }

    /** The scale actually used, after capping very large images. */
    fun effectiveScale(b: RectF, scale: Float): Float {
        var k = scale
        k = min(k, MAX_SIDE / max(b.width(), 1f))
        k = min(k, MAX_SIDE / max(b.height(), 1f))
        val px = b.width() * k * b.height() * k
        if (px > MAX_PIXELS) k *= sqrt(MAX_PIXELS / px)
        return max(k, 0.01f)
    }

    fun pixelSize(d: Drawing, cache: StrokeCache, o: ExportOptions): Pair<Int, Int> {
        val b = bounds(d, cache, o.only)
        val k = effectiveScale(b, o.scale)
        return (b.width() * k).roundToInt().coerceAtLeast(1) to (b.height() * k).roundToInt().coerceAtLeast(1)
    }

    fun renderBitmap(d: Drawing, cache: StrokeCache, o: ExportOptions): Bitmap {
        val src = subset(d, o.only)
        val b = bounds(d, cache, o.only)
        val k = effectiveScale(b, o.scale)
        val w = (b.width() * k).roundToInt().coerceAtLeast(1)
        val h = (b.height() * k).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when {
            o.includeBackground -> c.drawColor(d.background)
            o.format == ExportFormat.JPG -> c.drawColor(Color.WHITE) // JPG has no transparency
        }
        c.scale(k, k)
        c.translate(-b.left, -b.top)
        Renderer.drawStrokes(c, src, cache, RenderOptions(viewScale = k))
        return bmp
    }

    fun encode(d: Drawing, cache: StrokeCache, o: ExportOptions): ByteArray {
        if (o.format == ExportFormat.SVG) return svg(d, cache, o).toByteArray()
        val bmp = renderBitmap(d, cache, o)
        val out = ByteArrayOutputStream()
        bmp.compress(if (o.format == ExportFormat.JPG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Small thumbnail for the gallery. */
    fun thumbnail(d: Drawing, cache: StrokeCache, maxSide: Int = 480): Bitmap {
        val b = bounds(d, cache, null)
        val k = min(maxSide / max(b.width(), 1f), maxSide / max(b.height(), 1f)).coerceAtMost(2f)
        val w = (b.width() * k).roundToInt().coerceIn(1, maxSide)
        val h = (b.height() * k).roundToInt().coerceIn(1, maxSide)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(d.background)
        c.scale(k, k)
        c.translate(-b.left, -b.top)
        Renderer.drawStrokes(c, d, cache, RenderOptions(viewScale = k))
        return bmp
    }

    // ------------------------------------------------------------------
    // SVG
    // ------------------------------------------------------------------

    private fun f(v: Float) = String.format(Locale.US, "%.2f", v)
    private fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)

    fun svg(d: Drawing, cache: StrokeCache, o: ExportOptions): String {
        val src = subset(d, o.only)
        val b = bounds(d, cache, o.only)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${f(b.width())}pt" height="${f(b.height())}pt" viewBox="${f(b.left)} ${f(b.top)} ${f(b.width())} ${f(b.height())}">""").append('\n')
        sb.append("""<title>${escape(d.name)}</title>""").append('\n')
        if (src.strokes.any { it.brush == BrushType.AIRBRUSH }) {
            sb.append("<defs>")
            src.strokes.filter { it.brush == BrushType.AIRBRUSH }.map { it.size }.distinct().forEach { s ->
                sb.append("""<filter id="blur${s.roundToInt()}" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="${f(s * 0.3f)}"/></filter>""")
            }
            sb.append("</defs>\n")
        }
        if (o.includeBackground) {
            sb.append("""<rect x="${f(b.left)}" y="${f(b.top)}" width="${f(b.width())}" height="${f(b.height())}" fill="${hex(d.background)}"/>""").append('\n')
        }
        val byLayer = src.strokes.groupBy { it.layerId }
        for (layer in src.layers) {
            if (!layer.visible) continue
            val list = byLayer[layer.id] ?: continue
            sb.append("""<g id="${escape(layer.name)}"""")
            if (layer.opacity < 0.999f) sb.append(""" opacity="${f(layer.opacity)}"""")
            sb.append(">\n")
            for (s in list) {
                val e = cache.get(s)
                if (s.brush.isMask) continue // masks only exist in raster output
                if (s.brush == BrushType.TEXT) { svgText(sb, s); continue }
                if (s.brush == BrushType.IMAGE) { svgImage(sb, s); continue }
                val pathData = svgPath(e.path)
                if (pathData.isEmpty()) continue
                val a = Color.alpha(s.color) / 255f * s.opacity
                if (!s.brush.isCenterline) {
                    sb.append("""<path d="$pathData" fill="${hex(s.color)}"""")
                    if (a < 0.999f) sb.append(""" fill-opacity="${f(a)}"""")
                    sb.append("/>\n")
                } else {
                    val width = when (s.brush) {
                        BrushType.AIRBRUSH -> s.size * 0.6f
                        else -> s.size
                    }
                    val cap = if (s.brush == BrushType.MARKER) "square" else "round"
                    sb.append("""<path d="$pathData" fill="none" stroke="${hex(s.color)}" stroke-width="${f(width)}" stroke-linecap="$cap" stroke-linejoin="round"""")
                    if (a < 0.999f) sb.append(""" stroke-opacity="${f(a)}"""")
                    if (s.brush == BrushType.AIRBRUSH) sb.append(""" filter="url(#blur${s.size.roundToInt()})"""")
                    sb.append("/>\n")
                }
            }
            sb.append("</g>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun svgMatrix(m: android.graphics.Matrix): String {
        val v = FloatArray(9); m.getValues(v)
        return "matrix(${f(v[0])} ${f(v[3])} ${f(v[1])} ${f(v[4])} ${f(v[2])} ${f(v[5])})"
    }

    private fun svgText(sb: StringBuilder, s: com.omarkallas.inkfield.model.Stroke) {
        val text = s.text ?: return
        val (w, h) = Renderer.textBox(text, s.size)
        val m = Renderer.frameMatrix(s, w, h) ?: return
        val a = Color.alpha(s.color) / 255f * s.opacity
        sb.append("""<text transform="${svgMatrix(m)}" font-family="sans-serif" font-size="${f(s.size)}" fill="${hex(s.color)}"""")
        if (a < 0.999f) sb.append(""" fill-opacity="${f(a)}"""")
        sb.append(">")
        text.split('\n').forEachIndexed { i, line ->
            sb.append("""<tspan x="0" y="${f(s.size * (1.25f * i + 1f))}">${escape(line)}</tspan>""")
        }
        sb.append("</text>\n")
    }

    private fun svgImage(sb: StringBuilder, s: com.omarkallas.inkfield.model.Stroke) {
        val name = s.asset ?: return
        val bmp = Assets.get(name) ?: return
        val bytes = Assets.bytes(name) ?: return
        val mime = if (name.endsWith(".png")) "image/png" else "image/jpeg"
        val m = Renderer.frameMatrix(s, bmp.width.toFloat(), bmp.height.toFloat()) ?: return
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        sb.append("""<image width="${bmp.width}" height="${bmp.height}" transform="${svgMatrix(m)}" href="data:$mime;base64,$b64"/>""").append('\n')
    }

    /** Flattens an android Path into SVG path data using Path.approximate (API 26+). */
    private fun svgPath(p: android.graphics.Path): String {
        val a = p.approximate(0.2f)
        if (a.size < 6) return ""
        val sb = StringBuilder()
        var lastFrac = -1f
        var i = 0
        var started = false
        while (i + 2 < a.size) {
            val frac = a[i]; val x = a[i + 1]; val y = a[i + 2]
            if (!started || frac == lastFrac) sb.append('M') else sb.append('L')
            sb.append(f(x)).append(' ').append(f(y)).append(' ')
            started = true
            lastFrac = frac
            i += 3
        }
        return sb.toString().trim()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
