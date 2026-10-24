package com.omarkallas.inkfield.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Drawing
import com.omarkallas.inkfield.model.MeasureSettings
import com.omarkallas.inkfield.model.UnitSystem
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/** DXF, PSD and PDF writers. */
object ExportExtra {

    // ------------------------------------------------------------------
    // PDF
    // ------------------------------------------------------------------

    /** One-page PDF of [bounds] at true size (canvas units are PDF points). Vector unless [flatten]. */
    fun pdf(d: Drawing, cache: StrokeCache, bounds: RectF, includeBackground: Boolean, flatten: Boolean, ppi: Float, only: Set<Long>?, out: OutputStream) {
        val doc = PdfDocument()
        val w = bounds.width().roundToInt().coerceIn(1, 14400)
        val h = bounds.height().roundToInt().coerceIn(1, 14400)
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        val src = if (only == null) d else d.copy(strokes = d.strokes.filter { it.id in only })
        if (flatten) {
            val bmp = Exporter.renderBitmap(d, cache, ExportOptions(ExportFormat.PNG, ppi / 72f, includeBackground, only, region = bounds))
            c.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
        } else {
            if (includeBackground) c.drawColor(d.background)
            c.translate(-bounds.left, -bounds.top)
            Renderer.drawStrokes(c, src, cache, RenderOptions(viewScale = 1f))
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    // ------------------------------------------------------------------
    // DXF (R12 ASCII, opens in AutoCAD and most CAD/laser tools)
    // ------------------------------------------------------------------

    private fun unitFactor(m: MeasureSettings): Pair<Double, Int> = when (m.system) {
        // (real units per canvas point, $INSUNITS code)
        UnitSystem.METRIC -> m.scale * 25.4 / 72.0 to 4      // millimeters
        UnitSystem.IMPERIAL -> m.scale / 72.0 to 1            // inches
        UnitSystem.DIGITAL -> m.scale to 0                     // unitless points
    }

    private fun aci(c: Int): Int {
        // Nearest of the basic AutoCAD colors; dark neutrals map to 7 (black/white).
        val palette = listOf(1 to 0xFF0000, 2 to 0xFFFF00, 3 to 0x00FF00, 4 to 0x00FFFF, 5 to 0x0000FF, 6 to 0xFF00FF, 8 to 0x808080, 9 to 0xC0C0C0, 7 to 0x000000)
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return palette.minBy { (_, p) ->
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            (pr - r) * (pr - r) + (pg - g) * (pg - g) + (pb - b) * (pb - b)
        }.first
    }

    fun dxf(d: Drawing, cache: StrokeCache, wireframe: Boolean, only: Set<Long>?, out: OutputStream) {
        val (k, insunits) = unitFactor(d.precision.measure)
        val sb = StringBuilder()
        fun g(code: Int, v: Any) { sb.append(code).append('\n').append(v).append('\n') }
        fun num(v: Double) = String.format(Locale.US, "%.4f", v)
        fun layerName(id: Long) = (d.layer(id)?.name ?: "0").replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "0" }
        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$INSUNITS"); g(70, insunits)
        g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LAYER"); g(70, d.layers.size)
        for (l in d.layers) { g(0, "LAYER"); g(2, layerName(l.id)); g(70, 0); g(62, if (l.visible) 7 else -7); g(6, "CONTINUOUS") }
        g(0, "ENDTAB"); g(0, "ENDSEC")
        g(0, "SECTION"); g(2, "ENTITIES")
        for (s in d.strokes) {
            if (only != null && s.id !in only) continue
            if (s.brush.isMask || s.onMask || s.brush == BrushType.IMAGE || s.brush == BrushType.FLOOD) continue
            val lname = layerName(s.layerId)
            val color = aci(s.color)
            if (s.brush == BrushType.TEXT) {
                val f = cache.get(s).processed
                if (f.n < 4) continue
                // Text is placed at its frame's bottom-left corner.
                g(0, "TEXT"); g(8, lname); g(62, color)
                g(10, num(f.x[3] * k)); g(20, num(-f.y[3] * k)); g(30, num(0.0))
                g(40, num(s.size * k)); g(1, (s.text ?: "").replace('\n', ' '))
                g(50, num(Math.toDegrees(kotlin.math.atan2(-(f.y[1] - f.y[0]).toDouble(), (f.x[1] - f.x[0]).toDouble()))))
                continue
            }
            val e = cache.get(s)
            val polylines: List<FloatArray>
            var width = 0.0
            if (wireframe || s.brush.isCenterline || s.brush == BrushType.FILL || s.brush == BrushType.STAMP) {
                val pr = e.processed
                polylines = listOf(FloatArray(pr.n * 2) { if (it % 2 == 0) pr.x[it / 2] else pr.y[it / 2] })
                if (!wireframe && s.brush.isCenterline) width = s.size * k
            } else {
                // Outline of the filled shape, one closed polyline per contour.
                polylines = contours(e.path)
            }
            val closed = !wireframe && (!s.brush.isCenterline || s.brush == BrushType.FILL)
            for (pl in polylines) {
                if (pl.size < 4) continue
                g(0, "POLYLINE"); g(8, lname); g(62, color); g(66, 1); g(70, if (closed) 1 else 0)
                if (width > 0) { g(40, num(width)); g(41, num(width)) }
                for (i in pl.indices step 2) {
                    g(0, "VERTEX"); g(8, lname); g(10, num(pl[i] * k)); g(20, num(-pl[i + 1] * k)); g(30, num(0.0))
                }
                g(0, "SEQEND"); g(8, lname)
            }
        }
        g(0, "ENDSEC"); g(0, "EOF")
        out.write(sb.toString().toByteArray())
    }

    /** Splits a path into polylines using Path.approximate; a repeated fraction marks a new contour. */
    private fun contours(p: android.graphics.Path): List<FloatArray> {
        val a = p.approximate(0.25f)
        val out = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        var last = -1f
        var i = 0
        while (i + 2 < a.size) {
            if (a[i] == last && cur.isNotEmpty()) { out.add(cur.toFloatArray()); cur = ArrayList() }
            cur.add(a[i + 1]); cur.add(a[i + 2])
            last = a[i]
            i += 3
        }
        if (cur.isNotEmpty()) out.add(cur.toFloatArray())
        return out
    }

    // ------------------------------------------------------------------
    // PSD: one raster layer per drawing layer
    // ------------------------------------------------------------------

    fun psd(d: Drawing, cache: StrokeCache, bounds: RectF, scale: Float, includeBackground: Boolean, visibleOnly: Boolean, only: Set<Long>?, out0: OutputStream) {
        var k = scale
        // Keep layers within memory: at most 4096 px per side.
        k = minOf(k, 4096f / bounds.width().coerceAtLeast(1f), 4096f / bounds.height().coerceAtLeast(1f))
        val w = (bounds.width() * k).roundToInt().coerceAtLeast(1)
        val h = (bounds.height() * k).roundToInt().coerceAtLeast(1)
        val layers = d.layers.filter { !visibleOnly || it.visible }
        val src = if (only == null) d else d.copy(strokes = d.strokes.filter { it.id in only })

        fun renderLayer(layerId: Long?): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(k, k); c.translate(-bounds.left, -bounds.top)
            if (layerId == null) {
                if (includeBackground) c.drawColor(d.background)
                Renderer.drawStrokes(c, src, cache, RenderOptions(viewScale = k))
            } else {
                // Each PSD layer holds its own pixels; its blend mode and opacity go in the layer record.
                val one = src.copy(layers = src.layers.filter { it.id == layerId }.map {
                    it.copy(visible = true, opacity = 1f, blend = com.omarkallas.inkfield.model.LayerBlend.NORMAL)
                })
                Renderer.drawStrokes(c, one, cache, RenderOptions(viewScale = k))
            }
            return bmp
        }

        val out = DataOutputStream(BufferedOutputStream(out0, 1 shl 16))
        // Header
        out.writeBytes("8BPS"); out.writeShort(1); out.write(ByteArray(6))
        out.writeShort(4); out.writeInt(h); out.writeInt(w); out.writeShort(8); out.writeShort(3)
        out.writeInt(0) // color mode data
        out.writeInt(0) // image resources

        // Layer records need every layer's channel lengths up front; raw channels have fixed size.
        val chanLen = 2 + w * h
        val bgCount = if (includeBackground) 1 else 0
        val count = layers.size + bgCount
        val names = (if (includeBackground) listOf("Paper") else emptyList()) + layers.map { it.name }
        val blendKeys = (if (includeBackground) listOf("norm") else emptyList()) + layers.map { it.blend.psdKey }
        fun pascal(name: String): ByteArray {
            val b = name.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
            val raw = byteArrayOf(b.size.toByte()) + b
            val pad = (4 - raw.size % 4) % 4
            return raw + ByteArray(pad)
        }
        var recordsLen = 0L
        for (n in names) recordsLen += 16 + 2 + 4 * 6 + 4 + 4 + 4 + 4 + 4 + 4 + pascal(n).size
        val imageLen = count.toLong() * 4 * chanLen
        var layerInfoLen = 2 + recordsLen + imageLen
        val pad = layerInfoLen % 2
        layerInfoLen += pad
        out.writeInt((4 + layerInfoLen + 4).toInt())
        out.writeInt(layerInfoLen.toInt())
        out.writeShort(-count) // negative: first alpha channel holds merged transparency
        val opacities = (if (includeBackground) listOf(1f) else emptyList()) + layers.map { it.opacity }
        val hidden = (if (includeBackground) listOf(false) else emptyList()) + layers.map { !it.visible }
        for (i in 0 until count) {
            out.writeInt(0); out.writeInt(0); out.writeInt(h); out.writeInt(w)
            out.writeShort(4)
            for (id in intArrayOf(-1, 0, 1, 2)) { out.writeShort(id); out.writeInt(chanLen) }
            out.writeBytes("8BIM"); out.writeBytes(blendKeys[i])
            out.write((opacities[i] * 255).roundToInt().coerceIn(0, 255))
            out.write(0)
            out.write(if (hidden[i]) 0x02 else 0x00)
            out.write(0)
            val nameBytes = pascal(names[i])
            out.writeInt(4 + 4 + nameBytes.size)
            out.writeInt(0); out.writeInt(0)
            out.write(nameBytes)
        }
        val row = IntArray(w)
        val plane = ByteArray(w * h)
        fun writeChannels(bmp: Bitmap) {
            for (ch in intArrayOf(3, 0, 1, 2)) { // A, R, G, B matching ids -1, 0, 1, 2
                out.writeShort(0)
                for (y in 0 until h) {
                    bmp.getPixels(row, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val c = row[x]
                        // Android pixels are not premultiplied in getPixels; PSD expects straight color.
                        plane[y * w + x] = when (ch) { 3 -> (c ushr 24); 0 -> (c shr 16) and 0xFF; 1 -> (c shr 8) and 0xFF; else -> c and 0xFF }.toByte()
                    }
                }
                out.write(plane)
            }
        }
        if (includeBackground) {
            val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(d.background or 0xFF000000.toInt()) }
            writeChannels(bg); bg.recycle()
        }
        for (l in layers) { val b = renderLayer(l.id); writeChannels(b); b.recycle() }
        if (pad == 1L) out.write(0)
        out.writeInt(0) // global layer mask info

        // Merged image: raw RGBA planes
        val merged = renderLayer(null)
        out.writeShort(0)
        for (ch in intArrayOf(0, 1, 2, 3)) {
            for (y in 0 until h) {
                merged.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = row[x]
                    plane[y * w + x] = when (ch) { 0 -> (c shr 16) and 0xFF; 1 -> (c shr 8) and 0xFF; 2 -> c and 0xFF; else -> (c ushr 24) }.toByte()
                }
            }
            out.write(plane)
        }
        merged.recycle()
        out.flush()
    }

    @Suppress("unused")
    private fun len(x: Float, y: Float) = hypot(x, y)
}
