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
    MEDIUM("Airbrush"),
    WET("Wet blob"),
    WET_EDGE("Wet edge"),
    WAX("Wax"),
    SPONGE("Sponge"),
    GRIT("Grit"),
    BLOT("Ink blot"),
    RAKE("Hatching rake"),
    RAKE_THIN("Fine rake"),
    HATCH_MARKS("Hatch marks"),
    KNIFE("Knife-cut lead"),
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
    /** Brushes menu section. */
    val pack: String = "",
    /** Texture the stroke reveals (patterns, halftones, granulation). Overrides [grain]. */
    val pattern: PatternKind? = null,
    /** Canvas units per texture pixel. */
    val patternScale: Float = 0.5f,
    /** Halftone: dots or lines grow with opacity instead of fading (gradient textures only). */
    val threshold: Boolean = false,
    /** Strokes darken what's beneath them on the layer, like glazed watercolor. */
    val glaze: Boolean = false,
) {
    /** The texture applied to the stroke, if any. */
    val texture: PatternKind? get() = pattern ?: if (grain) PatternKind.PAPER else null
}

/** Built-in and user brushes, stamp bitmaps and the paper grain texture. */
object BrushLib {
    private const val STAMP_PX = 96
    private var appDir: File? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Brush packs in menu order, with a one-line description. */
    val packs: List<Pair<String, String>> = listOf(
        "Pencils" to "Graphite and carpenter pencils with paper grain",
        "Pastels" to "Soft, chalk and oil pastels",
        "Watercolor" to "Wet washes that pool at the edges and glaze over each other",
        "Airbrushes" to "Hard, medium and soft airbrushes that build up gradually",
        "Spray paints" to "Spray cans and splatter",
        "Ink and blot" to "Sponges, grit and ink textures",
        "Crosshatching" to "Hatching pens and crosshatch fills",
        "Halftones" to "Dotting and hatching screens; opacity sets dot and line size",
        "Memphis patterns" to "Playful confetti, squiggles and shapes",
        "Exterior walls" to "Wall materials for elevations",
        "Tiling patterns" to "Parquet, tiles and floor patterns for plans",
        "Textures" to "Paper and background textures",
    )

    private fun pat(id: String, name: String, pack: String, kind: PatternKind, scale: Float, size: Float = 70f) =
        CustomBrush("lib.$id", name, StampShape.ROUND_HARD, spacing = 0.05f, sizePressure = 0.15f, flow = 1f,
            pattern = kind, patternScale = scale, defaultSize = size, library = true, pack = pack)

    private fun tone(id: String, name: String, kind: PatternKind, scale: Float, size: Float = 50f) =
        CustomBrush("lib.$id", name, StampShape.ROUND_SOFT, spacing = 0.06f, sizePressure = 0.35f, flow = 0.4f,
            pattern = kind, patternScale = scale, threshold = true, defaultSize = size, defaultOpacity = 0.6f, library = true, pack = "Halftones")

    val library: List<CustomBrush> = listOf(
        // Pencils
        CustomBrush("lib.4b", "4B solid graphite", StampShape.CHALK, spacing = 0.07f, sizePressure = 0.35f, opacityPressure = 0.8f, flow = 0.5f,
            pattern = PatternKind.PAPER, patternScale = 0.5f, defaultSize = 6f, library = true, pack = "Pencils"),
        CustomBrush("lib.2h", "2H solid graphite", StampShape.CHALK, spacing = 0.07f, sizePressure = 0.2f, opacityPressure = 0.6f, flow = 0.3f,
            pattern = PatternKind.PAPER, patternScale = 0.35f, defaultSize = 3.5f, defaultOpacity = 0.75f, library = true, pack = "Pencils"),
        CustomBrush("lib.knife", "Knife sharpened pencil", StampShape.KNIFE, spacing = 0.05f, followPath = true, sizePressure = 0.4f, opacityPressure = 0.7f,
            flow = 0.55f, pattern = PatternKind.PAPER, patternScale = 0.45f, defaultSize = 6f, library = true, pack = "Pencils"),
        CustomBrush("lib.carpenter", "Carpenter pencil", StampShape.SQUARE, spacing = 0.04f, followPath = true, sizePressure = 0.1f, opacityPressure = 0.6f,
            flow = 0.35f, pattern = PatternKind.PAPER, patternScale = 0.5f, defaultSize = 12f, library = true, pack = "Pencils"),
        CustomBrush("lib.charcoal", "Charcoal", StampShape.BRISTLE, spacing = 0.08f, followPath = true, rotationJitter = 0.05f, sizePressure = 0.5f,
            opacityPressure = 0.7f, flow = 0.45f, grain = true, defaultSize = 16f, library = true, pack = "Pencils"),
        // Pastels
        CustomBrush("lib.pastel", "Soft pastel", StampShape.CHALK, spacing = 0.18f, scatter = 0.08f, rotationJitter = 1f, sizePressure = 0.3f,
            opacityPressure = 0.6f, flow = 0.55f, grain = true, defaultSize = 18f, library = true, pack = "Pastels"),
        CustomBrush("lib.chalk", "Chalk pastel", StampShape.CHALK, spacing = 0.25f, rotationJitter = 1f, sizeJitter = 0.15f, sizePressure = 0.2f,
            opacityPressure = 0.4f, flow = 0.75f, grain = true, defaultSize = 14f, library = true, pack = "Pastels"),
        CustomBrush("lib.oilpastel", "Oil pastel", StampShape.WAX, spacing = 0.06f, followPath = true, sizePressure = 0.3f, opacityPressure = 0.3f,
            flow = 0.7f, pattern = PatternKind.CANVAS, patternScale = 0.45f, defaultSize = 16f, library = true, pack = "Pastels"),
        CustomBrush("lib.pastelpencil", "Pastel pencil", StampShape.CHALK, spacing = 0.1f, sizePressure = 0.3f, opacityPressure = 0.6f, flow = 0.6f,
            pattern = PatternKind.PAPER, patternScale = 0.4f, defaultSize = 5f, library = true, pack = "Pastels"),
        CustomBrush("lib.blendpastel", "Blending pastel", StampShape.ROUND_SOFT, spacing = 0.08f, sizePressure = 0.2f, opacityPressure = 0.7f, flow = 0.08f,
            pattern = PatternKind.PAPER, patternScale = 0.6f, defaultSize = 40f, defaultOpacity = 0.7f, library = true, pack = "Pastels"),
        // Watercolor
        CustomBrush("lib.wc.wash", "Watercolor wash", StampShape.WET, spacing = 0.1f, rotationJitter = 1f, sizeJitter = 0.1f, sizePressure = 0.3f,
            opacityPressure = 0.4f, flow = 0.07f, pattern = PatternKind.GRANULATE, patternScale = 0.6f, glaze = true, defaultSize = 60f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.round", "Round watercolor", StampShape.WET, spacing = 0.08f, rotationJitter = 1f, sizePressure = 0.6f, opacityPressure = 0.3f,
            flow = 0.12f, pattern = PatternKind.GRANULATE, patternScale = 0.5f, glaze = true, defaultSize = 24f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.edge", "Wet edge", StampShape.WET_EDGE, spacing = 0.08f, rotationJitter = 1f, sizePressure = 0.4f, flow = 0.1f,
            pattern = PatternKind.GRANULATE, patternScale = 0.7f, glaze = true, defaultSize = 32f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.granulate", "Granulating", StampShape.WET, spacing = 0.1f, rotationJitter = 1f, sizePressure = 0.4f, flow = 0.12f,
            pattern = PatternKind.GRANULATE, patternScale = 1.3f, glaze = true, defaultSize = 40f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.bleed", "Bleed", StampShape.WET, spacing = 0.15f, scatter = 0.35f, sizeJitter = 0.4f, rotationJitter = 1f, sizePressure = 0.3f,
            flow = 0.07f, pattern = PatternKind.GRANULATE, patternScale = 0.8f, glaze = true, defaultSize = 40f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.dry", "Dry brush", StampShape.BRISTLE, spacing = 0.05f, followPath = true, sizePressure = 0.4f, opacityPressure = 0.5f, flow = 0.25f,
            pattern = PatternKind.PAPER, patternScale = 0.7f, glaze = true, defaultSize = 22f, library = true, pack = "Watercolor"),
        CustomBrush("lib.wc.splash", "Splash", StampShape.WET, spacing = 1.2f, scatter = 2f, sizeJitter = 0.7f, rotationJitter = 1f, sizePressure = 0.3f,
            flow = 0.25f, pattern = PatternKind.GRANULATE, patternScale = 0.6f, glaze = true, defaultSize = 14f, library = true, pack = "Watercolor"),
        // Airbrushes
        CustomBrush("lib.air.hard", "Hard airbrush", StampShape.ROUND_HARD, spacing = 0.06f, sizePressure = 0.2f, opacityPressure = 0.7f, flow = 0.07f,
            defaultSize = 30f, library = true, pack = "Airbrushes"),
        CustomBrush("lib.air.medium", "Medium airbrush", StampShape.MEDIUM, spacing = 0.06f, sizePressure = 0.2f, opacityPressure = 0.7f, flow = 0.06f,
            defaultSize = 40f, library = true, pack = "Airbrushes"),
        CustomBrush("lib.soft", "Soft airbrush", StampShape.ROUND_SOFT, spacing = 0.08f, sizePressure = 0.4f, opacityPressure = 0.8f, flow = 0.15f,
            defaultSize = 60f, library = true, pack = "Airbrushes"),
        CustomBrush("lib.air.fine", "Fine airbrush", StampShape.ROUND_SOFT, spacing = 0.06f, sizePressure = 0.5f, opacityPressure = 0.6f, flow = 0.12f,
            defaultSize = 8f, library = true, pack = "Airbrushes"),
        CustomBrush("lib.air.spatter", "Spatter airbrush", StampShape.SPRAY, spacing = 0.25f, scatter = 0.1f, rotationJitter = 1f, sizePressure = 0.2f,
            opacityPressure = 0.6f, flow = 0.5f, defaultSize = 40f, library = true, pack = "Airbrushes"),
        // Spray paints
        CustomBrush("lib.spray", "Spray can", StampShape.SPRAY, spacing = 0.3f, scatter = 0.15f, rotationJitter = 1f, sizePressure = 0f, opacityPressure = 0.6f,
            flow = 0.5f, defaultSize = 50f, defaultOpacity = 0.8f, library = true, pack = "Spray paints"),
        CustomBrush("lib.spray.fine", "Fine spray", StampShape.SPRAY, spacing = 0.2f, scatter = 0.1f, rotationJitter = 1f, opacityPressure = 0.6f, flow = 0.35f,
            defaultSize = 20f, library = true, pack = "Spray paints"),
        CustomBrush("lib.spray.fat", "Fat cap", StampShape.SPRAY, spacing = 0.2f, scatter = 0.1f, rotationJitter = 1f, opacityPressure = 0.4f, flow = 0.6f,
            defaultSize = 90f, library = true, pack = "Spray paints"),
        CustomBrush("lib.splatter", "Splatter", StampShape.ROUND_HARD, spacing = 1.4f, scatter = 2.5f, sizeJitter = 0.8f, opacityJitter = 0.3f, sizePressure = 0.3f,
            flow = 0.9f, defaultSize = 8f, library = true, pack = "Spray paints"),
        CustomBrush("lib.stipple", "Stipple", StampShape.ROUND_HARD, spacing = 0.9f, scatter = 1.2f, sizeJitter = 0.5f, sizePressure = 0.2f, flow = 1f,
            defaultSize = 3f, library = true, pack = "Spray paints"),
        // Ink and blot
        CustomBrush("lib.ink", "Ink brush", StampShape.ROUND_HARD, spacing = 0.04f, sizePressure = 0.9f, flow = 1f, defaultSize = 10f, library = true, pack = "Ink and blot"),
        CustomBrush("lib.sponge", "Sponge block", StampShape.SPONGE, spacing = 0.3f, rotationJitter = 1f, sizePressure = 0.2f, opacityPressure = 0.4f, flow = 0.6f,
            defaultSize = 40f, library = true, pack = "Ink and blot"),
        CustomBrush("lib.sponge.grit", "Sponge grit", StampShape.SPONGE, spacing = 0.5f, scatter = 0.3f, rotationJitter = 1f, sizeJitter = 0.3f, flow = 0.4f,
            pattern = PatternKind.PAPER, patternScale = 0.8f, defaultSize = 30f, library = true, pack = "Ink and blot"),
        CustomBrush("lib.grit", "Fine grit", StampShape.GRIT, spacing = 0.4f, scatter = 0.2f, rotationJitter = 1f, flow = 0.85f, defaultSize = 24f,
            library = true, pack = "Ink and blot"),
        CustomBrush("lib.blot", "Rough ink", StampShape.BLOT, spacing = 0.2f, rotationJitter = 1f, sizeJitter = 0.2f, sizePressure = 0.7f, flow = 0.9f,
            defaultSize = 16f, library = true, pack = "Ink and blot"),
        CustomBrush("lib.flat", "Flat marker", StampShape.SQUARE, spacing = 0.05f, followPath = true, sizePressure = 0.1f, flow = 0.25f, defaultSize = 20f,
            library = true, pack = "Ink and blot"),
        // Crosshatching
        CustomBrush("lib.hatch.pen", "Hatching pen", StampShape.RAKE, spacing = 0.03f, followPath = true, sizePressure = 0.25f, flow = 1f, defaultSize = 12f,
            library = true, pack = "Crosshatching"),
        CustomBrush("lib.hatch.thin", "Thin hatching pen", StampShape.RAKE_THIN, spacing = 0.03f, followPath = true, sizePressure = 0.2f, flow = 1f, defaultSize = 14f,
            library = true, pack = "Crosshatching"),
        CustomBrush("lib.hatch.curved", "Curved hatching", StampShape.RAKE_THIN, spacing = 0.03f, followPath = true, scatter = 0.04f, sizePressure = 0.5f,
            opacityPressure = 0.4f, flow = 0.9f, defaultSize = 20f, library = true, pack = "Crosshatching"),
        CustomBrush("lib.hatch.marks", "Hatch marks", StampShape.HATCH_MARKS, spacing = 0.6f, scatter = 0.25f, sizeJitter = 0.15f, sizePressure = 0.3f, flow = 0.95f,
            defaultSize = 16f, library = true, pack = "Crosshatching"),
        CustomBrush("lib.hatch.cross", "Crosshatch pen", StampShape.ROUND_SOFT, spacing = 0.06f, sizePressure = 0.3f, flow = 0.4f, pattern = PatternKind.CROSS,
            patternScale = 0.35f, threshold = true, defaultSize = 28f, defaultOpacity = 0.55f, library = true, pack = "Crosshatching"),
        CustomBrush("lib.hatch.fill", "Hatch fill", StampShape.ROUND_HARD, spacing = 0.05f, sizePressure = 0.15f, flow = 1f, pattern = PatternKind.LINES_45,
            patternScale = 0.4f, threshold = true, defaultSize = 50f, defaultOpacity = 0.35f, library = true, pack = "Crosshatching"),
        // Halftones (dotting and hatching)
        tone("ht.dots.fine", "Fine dots", PatternKind.DOTS, 0.3f),
        tone("ht.dots", "Halftone dots", PatternKind.DOTS, 0.6f),
        tone("ht.dots.coarse", "Coarse dots", PatternKind.DOTS, 1.2f, 70f),
        tone("ht.hatch1", "Hatch 1", PatternKind.LINES_45, 0.3f),
        tone("ht.hatch2", "Hatch 2", PatternKind.LINES_45, 0.6f),
        tone("ht.hatch3", "Hatch 3", PatternKind.LINES_0, 0.5f),
        tone("ht.hatch4", "Hatch 4", PatternKind.LINES_135, 0.5f),
        tone("ht.vertical", "Vertical lines", PatternKind.LINES_90, 0.5f),
        tone("ht.cross1", "Crosshatch 1", PatternKind.CROSS, 0.4f),
        tone("ht.cross2", "Crosshatch 2", PatternKind.CROSS_SQUARE, 0.6f),
        // Memphis
        pat("memphis1", "Memphis 1", "Memphis patterns", PatternKind.CONFETTI, 0.5f),
        pat("memphis2", "Memphis 2", "Memphis patterns", PatternKind.ZEBRA, 0.5f),
        pat("memphis3", "Memphis 3", "Memphis patterns", PatternKind.SHAPES, 0.5f),
        pat("memphis4", "Memphis 4", "Memphis patterns", PatternKind.POLKA, 0.5f),
        pat("memphis5", "Memphis 5", "Memphis patterns", PatternKind.ZIGZAG, 0.5f),
        pat("memphis6", "Memphis 6", "Memphis patterns", PatternKind.WAVES, 0.5f),
        pat("memphis7", "Memphis 7", "Memphis patterns", PatternKind.STRIPES, 0.5f),
        // Exterior walls
        pat("wall.panel", "Panel wall", "Exterior walls", PatternKind.PANEL, 0.6f, 90f),
        pat("wall.slat", "Slat wall", "Exterior walls", PatternKind.SLAT, 0.6f, 90f),
        pat("wall.board", "Board wall", "Exterior walls", PatternKind.BOARD, 0.6f, 90f),
        pat("wall.brick", "Brick wall", "Exterior walls", PatternKind.BRICK, 0.4f, 90f),
        pat("wall.stone", "Stone wall", "Exterior walls", PatternKind.STONE, 0.5f, 90f),
        pat("wall.corrugated", "Corrugated metal", "Exterior walls", PatternKind.CORRUGATED, 0.5f, 90f),
        pat("wall.shingles", "Shingles", "Exterior walls", PatternKind.SHINGLES, 0.5f, 90f),
        pat("wall.concrete", "Concrete", "Exterior walls", PatternKind.CONCRETE, 0.6f, 90f),
        pat("wall.lava", "Lava stone", "Exterior walls", PatternKind.LAVA, 0.5f, 90f),
        // Tiling
        pat("tile.parquet1", "Wood parquet 1", "Tiling patterns", PatternKind.BASKET, 0.4f, 90f),
        pat("tile.parquet2", "Wood parquet 2", "Tiling patterns", PatternKind.STRIP, 0.4f, 90f),
        pat("tile.parquet3", "Wood parquet 3", "Tiling patterns", PatternKind.LADDER, 0.4f, 90f),
        pat("tile.herringbone", "Herringbone parquet", "Tiling patterns", PatternKind.HERRINGBONE, 0.4f, 90f),
        pat("tile.chevron", "Chevron parquet", "Tiling patterns", PatternKind.CHEVRON, 0.4f, 90f),
        pat("tile.square", "Square tiles", "Tiling patterns", PatternKind.SQUARE_TILE, 0.6f, 90f),
        pat("tile.subway", "Subway tiles", "Tiling patterns", PatternKind.SUBWAY, 0.5f, 90f),
        pat("tile.hex", "Hexagon tiles", "Tiling patterns", PatternKind.HEX_TILE, 0.4f, 90f),
        pat("tile.checker", "Checkerboard", "Tiling patterns", PatternKind.CHECKER, 0.6f, 90f),
        pat("tile.terrazzo", "Terrazzo", "Tiling patterns", PatternKind.TERRAZZO, 0.5f, 90f),
        // Textures
        CustomBrush("lib.texture", "Paper texture", StampShape.CHALK, spacing = 0.35f, rotationJitter = 1f, sizeJitter = 0.2f, sizePressure = 0f, flow = 0.35f,
            grain = true, defaultSize = 120f, defaultOpacity = 0.6f, library = true, pack = "Textures"),
        CustomBrush("lib.canvas", "Canvas texture", StampShape.ROUND_SOFT, spacing = 0.1f, sizePressure = 0f, flow = 0.2f, pattern = PatternKind.CANVAS,
            patternScale = 0.6f, defaultSize = 120f, defaultOpacity = 0.6f, library = true, pack = "Textures"),
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
            StampShape.MEDIUM -> {
                p.shader = RadialGradient(r, r, r, intArrayOf(Color.BLACK, 0xE0000000.toInt(), 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(r, r, r, p)
            }
            StampShape.WET, StampShape.WET_EDGE -> return wetStamp(shape == StampShape.WET_EDGE)
            StampShape.WAX -> {
                // Dense waxy speckle with streaks along the stroke direction.
                repeat(1800) {
                    val a = rnd.nextFloat() * 6.2832f
                    val d = kotlin.math.sqrt(rnd.nextFloat()) * (r - 3f)
                    p.alpha = 140 + rnd.nextInt(116)
                    c.drawCircle(r + d * kotlin.math.cos(a), r + d * kotlin.math.sin(a), 1f + rnd.nextFloat() * 2.4f, p)
                }
                p.alpha = 255
                for (i in 0 until 10) {
                    val y = n * (0.15f + 0.7f * rnd.nextFloat())
                    c.drawRect(n * 0.1f, y, n * 0.9f, y + 1.5f, p)
                }
            }
            StampShape.SPONGE -> {
                val nz = FloatArray(n * n)
                val g = 12
                val lat = Array(g) { FloatArray(g) { rnd.nextFloat() } }
                val px = ByteArray(n * n)
                for (y in 0 until n) for (x in 0 until n) {
                    val fx = x * (g - 1) / n.toFloat(); val fy = y * (g - 1) / n.toFloat()
                    val x0 = fx.toInt(); val y0 = fy.toInt()
                    val tx = fx - x0; val ty = fy - y0
                    val v = (lat[y0][x0] * (1 - tx) + lat[y0][x0 + 1] * tx) * (1 - ty) + (lat[y0 + 1][x0] * (1 - tx) + lat[y0 + 1][x0 + 1] * tx) * ty
                    nz[y * n + x] = v
                    val d = kotlin.math.hypot(x - r, y - r) / r
                    val edge = (1f - d).coerceIn(0f, 0.25f) * 4f
                    val a = if (v > 0.45f + rnd.nextFloat() * 0.1f) 1f else 0f
                    px[y * n + x] = (a * edge * 255).roundToInt().toByte()
                }
                bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
            }
            StampShape.GRIT -> repeat(140) {
                val a = rnd.nextFloat() * 6.2832f
                val d = kotlin.math.sqrt(rnd.nextFloat()) * (r - 3f)
                p.alpha = 200 + rnd.nextInt(56)
                c.drawCircle(r + d * kotlin.math.cos(a), r + d * kotlin.math.sin(a), 0.7f + rnd.nextFloat() * 1.6f, p)
            }
            StampShape.BLOT -> {
                c.drawCircle(r, r, r * 0.55f, p)
                repeat(14) {
                    val a = rnd.nextFloat() * 6.2832f
                    val d = r * (0.4f + rnd.nextFloat() * 0.45f)
                    c.drawCircle(r + d * kotlin.math.cos(a), r + d * kotlin.math.sin(a), r * (0.06f + rnd.nextFloat() * 0.16f), p)
                }
            }
            StampShape.RAKE -> for (y in floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f)) c.drawRect(0f, n * y - 3f, n.toFloat(), n * y + 3f, p)
            StampShape.RAKE_THIN -> for (i in 0 until 6) { val y = n * (0.12f + 0.76f * i / 5f); c.drawRect(0f, y - 1.3f, n.toFloat(), y + 1.3f, p) }
            StampShape.HATCH_MARKS -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
                for (i in 0 until 3) {
                    val o = (i - 1) * n * 0.22f
                    c.drawLine(n * 0.22f + o, n * 0.78f, n * 0.62f + o, n * 0.22f, p)
                }
            }
            StampShape.KNIFE -> repeat(700) {
                // An uneven, elongated lead: speckles in a narrow, ragged ellipse.
                val a = rnd.nextFloat() * 6.2832f
                val d = kotlin.math.sqrt(rnd.nextFloat())
                val rx = r * 0.85f; val ry = r * (0.22f + 0.1f * kotlin.math.sin(a * 3f))
                p.alpha = 90 + rnd.nextInt(166)
                c.drawCircle(r + d * rx * kotlin.math.cos(a), r + d * ry * kotlin.math.sin(a), 0.8f + rnd.nextFloat() * 1.6f, p)
            }
        }
        return bmp
    }

    /** Watercolor dab: lighter middle, pigment gathered at an irregular rim, soft outer falloff. */
    private fun wetStamp(strongEdge: Boolean): Bitmap {
        val n = STAMP_PX
        val rnd = Random(if (strongEdge) 77 else 76)
        val bumps = FloatArray(7) { rnd.nextFloat() * 6.2832f }
        val px = ByteArray(n * n)
        val c = n / 2f
        val center = if (strongEdge) 0.18f else 0.38f
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x + 0.5f - c; val dy = y + 0.5f - c
            val ang = kotlin.math.atan2(dy, dx)
            var rr = 0.84f
            for ((k, ph) in bumps.withIndex()) rr += 0.035f * kotlin.math.sin(ang * (k + 2) + ph) / (k + 1) * 2f
            val d = kotlin.math.hypot(dx, dy) / c / rr
            val inner = center + (1f - center) * smooth(0.55f, 0.97f, d)
            val a = inner * (1f - smooth(0.97f, 1.08f, d))
            px[y * n + x] = (a.coerceIn(0f, 1f) * 255).roundToInt().toByte()
        }
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
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
