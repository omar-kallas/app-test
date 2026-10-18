package com.omarkallas.inkfield.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

enum class StampShape(val label: String) {
    ROUND_HARD("Round"),
    ROUND_SOFT("Soft round"),
    SQUARE("Square"),
    CHALK("Chalk"),
    SPRAY("Spray"),
    BRISTLE("Bristle"),
    IMAGE("Image"),
}

/**
 * A brush made by stamping a shape along the stroke. Every value is 0..1 unless noted.
 * Pressure responses are -1..1: positive grows with pressure, negative shrinks.
 */
@Serializable
data class CustomBrush(
    val id: String,
    val name: String,
    val stamp: StampShape = StampShape.ROUND_SOFT,
    /** For IMAGE stamps: file name in the brushes folder. */
    val image: String? = null,
    /** Distance between stamps as a fraction of size (0.02–2). */
    val spacing: Float = 0.12f,
    val scatter: Float = 0f,
    val sizeJitter: Float = 0f,
    val opacityJitter: Float = 0f,
    /** Random rotation, 0 = none, 1 = any angle. */
    val rotationJitter: Float = 0f,
    val followPath: Boolean = false,
    val sizePressure: Float = 0.5f,
    val opacityPressure: Float = 0f,
    /** Alpha of each stamp. */
    val flow: Float = 0.6f,
    /** Paper grain shows through the stroke. */
    val grain: Boolean = false,
    val defaultSize: Float = 14f,
    val defaultOpacity: Float = 1f,
    val library: Boolean = false,
)

/** Built-in and user brushes, stamp bitmaps and the paper grain texture. */
object BrushLib {
    private const val STAMP_PX = 96
    private var appDir: File? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val library: List<CustomBrush> = listOf(
        CustomBrush("lib.ink", "Ink brush", StampShape.ROUND_HARD, spacing = 0.04f, sizePressure = 0.9f, flow = 1f, defaultSize = 10f, library = true),
        CustomBrush("lib.pastel", "Pastel", StampShape.CHALK, spacing = 0.18f, scatter = 0.08f, rotationJitter = 1f, sizePressure = 0.3f, opacityPressure = 0.6f, flow = 0.55f, grain = true, defaultSize = 18f, library = true),
        CustomBrush("lib.chalk", "Chalk", StampShape.CHALK, spacing = 0.25f, rotationJitter = 1f, sizeJitter = 0.15f, sizePressure = 0.2f, opacityPressure = 0.4f, flow = 0.75f, grain = true, defaultSize = 14f, library = true),
        CustomBrush("lib.charcoal", "Charcoal", StampShape.BRISTLE, spacing = 0.08f, followPath = true, rotationJitter = 0.05f, sizePressure = 0.5f, opacityPressure = 0.7f, flow = 0.45f, grain = true, defaultSize = 16f, library = true),
        CustomBrush("lib.spray", "Spray can", StampShape.SPRAY, spacing = 0.3f, scatter = 0.15f, rotationJitter = 1f, sizePressure = 0f, opacityPressure = 0.6f, flow = 0.5f, defaultSize = 50f, defaultOpacity = 0.8f, library = true),
        CustomBrush("lib.splatter", "Splatter", StampShape.ROUND_HARD, spacing = 1.4f, scatter = 2.5f, sizeJitter = 0.8f, opacityJitter = 0.3f, sizePressure = 0.3f, flow = 0.9f, defaultSize = 8f, library = true),
        CustomBrush("lib.stipple", "Stipple", StampShape.ROUND_HARD, spacing = 0.9f, scatter = 1.2f, sizeJitter = 0.5f, sizePressure = 0.2f, flow = 1f, defaultSize = 3f, library = true),
        CustomBrush("lib.flat", "Flat marker", StampShape.SQUARE, spacing = 0.05f, followPath = true, sizePressure = 0.1f, flow = 0.25f, defaultSize = 20f, library = true),
        CustomBrush("lib.soft", "Soft shader", StampShape.ROUND_SOFT, spacing = 0.08f, sizePressure = 0.4f, opacityPressure = 0.8f, flow = 0.15f, defaultSize = 60f, library = true),
        CustomBrush("lib.texture", "Paper texture", StampShape.CHALK, spacing = 0.35f, rotationJitter = 1f, sizeJitter = 0.2f, sizePressure = 0f, flow = 0.35f, grain = true, defaultSize = 120f, defaultOpacity = 0.6f, library = true),
    )

    /** User brushes, persisted in the app's files. */
    var user: List<CustomBrush> = emptyList()
        private set

    fun init(context: Context) {
        appDir = context.filesDir
        user = runCatching { json.decodeFromString(ListSerializer(CustomBrush.serializer()), File(context.filesDir, "brushes.json").readText()) }
            .getOrDefault(emptyList())
    }

    fun brushesDir(): File = File(appDir ?: File("."), "brushes").apply { mkdirs() }

    private var preview: CustomBrush? = null

    /** A brush being edited, so its preview can render before it is saved. */
    fun setPreview(b: CustomBrush) { preview = b }

    fun find(id: String?): CustomBrush? = if (id == null) null
    else library.firstOrNull { it.id == id } ?: user.firstOrNull { it.id == id } ?: preview?.takeIf { it.id == id }

    fun saveUser(list: List<CustomBrush>) {
        user = list
        val dir = appDir ?: return
        runCatching { File(dir, "brushes.json").writeText(json.encodeToString(ListSerializer(CustomBrush.serializer()), list)) }
        stampCache.keys.removeAll { it.startsWith("img:") }
    }

    fun upsert(b: CustomBrush) = saveUser(user.filter { it.id != b.id } + b)
    fun delete(id: String) = saveUser(user.filter { it.id != id })
    fun newId() = "user." + UUID.randomUUID().toString().take(8)

    // ---- brush pack files (.inkbrush): JSON with embedded stamp images ----
    @Serializable
    private data class PackEntry(val brush: CustomBrush, val imagePng: String? = null)

    fun exportPack(brushes: List<CustomBrush>): ByteArray {
        val entries = brushes.map { b ->
            val img = b.image?.let { name ->
                runCatching { android.util.Base64.encodeToString(File(brushesDir(), name).readBytes(), android.util.Base64.NO_WRAP) }.getOrNull()
            }
            PackEntry(b.copy(library = false), img)
        }
        return json.encodeToString(ListSerializer(PackEntry.serializer()), entries).toByteArray()
    }

    /** Imports a pack; returns how many brushes were added. */
    fun importPack(bytes: ByteArray): Int {
        val entries = runCatching { json.decodeFromString(ListSerializer(PackEntry.serializer()), bytes.decodeToString()) }.getOrNull() ?: return 0
        val added = entries.map { e ->
            var b = e.brush.copy(id = newId(), library = false)
            if (e.imagePng != null) {
                val name = UUID.randomUUID().toString() + ".png"
                runCatching { File(brushesDir(), name).writeBytes(android.util.Base64.decode(e.imagePng, android.util.Base64.NO_WRAP)) }
                b = b.copy(image = name, stamp = StampShape.IMAGE)
            }
            b
        }
        saveUser(user + added)
        return added.size
    }

    /** Stores a picked image as a stamp: grayscale, dark = opaque, at most 256 px. Returns its file name. */
    fun saveStampImage(src: Bitmap): String? {
        val k = min(1f, 256f / max(src.width, src.height))
        val w = max(1, (src.width * k).roundToInt()); val h = max(1, (src.height * k).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val lum = (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f) / 255f
            val a = ((1f - lum) * Color.alpha(c) / 255f * 255f).roundToInt().coerceIn(0, 255)
            px[i] = Color.argb(a, 0, 0, 0)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        val name = UUID.randomUUID().toString() + ".png"
        return runCatching {
            File(brushesDir(), name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name
        }.getOrNull()
    }

    // ---- stamps ----
    private val stampCache = HashMap<String, Bitmap>()

    @Synchronized
    fun stamp(b: CustomBrush): Bitmap {
        val key = if (b.stamp == StampShape.IMAGE) "img:${b.image}" else b.stamp.name
        stampCache[key]?.let { return it }
        val bmp = if (b.stamp == StampShape.IMAGE) loadImageStamp(b.image) ?: makeStamp(StampShape.ROUND_SOFT) else makeStamp(b.stamp)
        stampCache[key] = bmp
        return bmp
    }

    private fun loadImageStamp(name: String?): Bitmap? {
        if (name == null) return null
        val src = runCatching { BitmapFactory.decodeFile(File(brushesDir(), name).path) }.getOrNull() ?: return null
        return src.extractAlpha()
    }

    private fun makeStamp(shape: StampShape): Bitmap {
        val n = STAMP_PX
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val r = n / 2f
        val rnd = Random(shape.ordinal * 7919 + 13)
        when (shape) {
            StampShape.ROUND_HARD, StampShape.IMAGE -> c.drawCircle(r, r, r - 1f, p)
            StampShape.ROUND_SOFT -> {
                p.shader = RadialGradient(r, r, r, intArrayOf(Color.BLACK, 0x80000000.toInt(), 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(r, r, r, p)
            }
            StampShape.SQUARE -> c.drawRect(n * 0.08f, n * 0.38f, n * 0.92f, n * 0.62f, p)
            StampShape.CHALK -> {
                // A rough disc made of many speckles.
                repeat(900) {
                    val a = rnd.nextFloat() * 6.2832f
                    val d = kotlin.math.sqrt(rnd.nextFloat()) * (r - 3f)
                    p.alpha = 60 + rnd.nextInt(196)
                    c.drawCircle(r + d * kotlin.math.cos(a), r + d * kotlin.math.sin(a), 0.8f + rnd.nextFloat() * 2.2f, p)
                }
            }
            StampShape.SPRAY -> {
                repeat(220) {
                    val a = rnd.nextFloat() * 6.2832f
                    val d = (rnd.nextFloat() + rnd.nextFloat()) / 2f * (r - 2f)
                    p.alpha = 120 + rnd.nextInt(136)
                    c.drawCircle(r + d * kotlin.math.cos(a), r + d * kotlin.math.sin(a), 0.6f + rnd.nextFloat() * 1.2f, p)
                }
            }
            StampShape.BRISTLE -> {
                // A row of hairs across the stamp, drawn as short soft dabs.
                for (i in 0 until 14) {
                    val y = n * (0.12f + 0.76f * i / 13f) + rnd.nextFloat() * 2f
                    p.alpha = 90 + rnd.nextInt(166)
                    c.drawOval(n * 0.35f, y - 1.6f, n * 0.65f, y + 1.6f, p)
                }
            }
        }
        return bmp
    }

    // ---- paper grain ----
    private var grainBmp: Bitmap? = null

    /** A seamless 128×128 alpha noise used by pencils and grain brushes. */
    @Synchronized
    fun grain(): Bitmap {
        grainBmp?.let { return it }
        val n = 128
        val rnd = Random(424242)
        // Value noise on a coarse lattice, wrapped for seamless tiling, plus fine speckle.
        val g = 16
        val lattice = Array(g) { FloatArray(g) { rnd.nextFloat() } }
        val px = ByteArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x * g / n.toFloat(); val fy = y * g / n.toFloat()
            val x0 = fx.toInt() % g; val y0 = fy.toInt() % g
            val x1 = (x0 + 1) % g; val y1 = (y0 + 1) % g
            val tx = fx - fx.toInt(); val ty = fy - fy.toInt()
            val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
            val v = (lattice[y0][x0] * (1 - sx) + lattice[y0][x1] * sx) * (1 - sy) + (lattice[y1][x0] * (1 - sx) + lattice[y1][x1] * sx) * sy
            val speck = rnd.nextFloat()
            val a = (0.35f + 0.45f * v + 0.35f * speck).coerceIn(0f, 1f)
            px[y * n + x] = (a * 255).roundToInt().coerceIn(0, 255).toByte()
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        grainBmp = bmp
        return bmp
    }
}
