package com.omarkallas.inkfield.engine

import android.content.Context
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.Pt
import com.omarkallas.inkfield.model.Stroke
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One path of an object, in the object's own units. */
@Serializable
data class ObjPath(val pts: List<Pt>, val closed: Boolean = false, val fill: Boolean = false, val width: Float = 1f)

/**
 * A library object. Paths are in [unit]s: for real-scale packs one unit is a centimeter
 * (placed at 1:100), for icon packs the object fits a 100-unit box.
 */
@Serializable
data class LibObject(
    val id: String,
    val name: String,
    val tags: String = "",
    val paths: List<ObjPath> = emptyList(),
    /** For objects made from a selection: the original strokes, relative to their top-left. */
    val strokes: List<StoredStroke> = emptyList(),
)

@Serializable
data class StoredStroke(
    val brush: BrushType, val size: Float, val color: Int, val opacity: Float, val smoothing: Float,
    val pts: FloatArray, val customBrush: String? = null, val text: String? = null, val asset: String? = null,
)

@Serializable
data class ObjPack(
    val id: String,
    val name: String,
    val description: String = "",
    /** True: units are centimeters drawn at 1:100. False: a 100-unit icon box. */
    val realScale: Boolean = false,
    /** When placed, strokes take the active tool's brush, size and color. */
    val useToolStyle: Boolean = true,
    val objects: List<LibObject> = emptyList(),
    val bundled: Boolean = false,
)

object ObjectLibrary {
    private var dir: File? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var userPacks: List<ObjPack> = emptyList()
        private set
    var favorites: Set<String> = emptySet()
        private set

    fun init(context: Context) {
        dir = context.filesDir
        userPacks = runCatching { json.decodeFromString(ListSerializer(ObjPack.serializer()), File(context.filesDir, "objects.json").readText()) }
            .getOrDefault(emptyList())
        favorites = runCatching { File(context.filesDir, "objfav.txt").readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    }

    private fun persist() {
        val d = dir ?: return
        runCatching { File(d, "objects.json").writeText(json.encodeToString(ListSerializer(ObjPack.serializer()), userPacks)) }
        runCatching { File(d, "objfav.txt").writeText(favorites.joinToString("\n")) }
    }

    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        persist()
    }

    fun newPack(name: String): ObjPack {
        val p = ObjPack(UUID.randomUUID().toString(), name.ifBlank { "My pack" }, useToolStyle = false)
        userPacks = userPacks + p
        persist()
        return p
    }

    fun updatePack(p: ObjPack) { userPacks = userPacks.map { if (it.id == p.id) p else it }; persist() }
    fun deletePack(id: String) { userPacks = userPacks.filter { it.id != id }; persist() }

    /** Adds the given strokes to a user pack as a new object. */
    fun addObject(packId: String, name: String, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        for (s in strokes) for (i in 0 until s.count) {
            minX = minOf(minX, s.pts[i * Stroke.STRIDE]); minY = minOf(minY, s.pts[i * Stroke.STRIDE + 1])
        }
        val stored = strokes.map { s ->
            val p = s.pts.copyOf()
            for (i in 0 until s.count) { p[i * Stroke.STRIDE] -= minX; p[i * Stroke.STRIDE + 1] -= minY }
            StoredStroke(s.brush, s.size, s.color, s.opacity, s.smoothing, p, s.customBrush, s.text, s.asset)
        }
        val obj = LibObject(UUID.randomUUID().toString(), name.ifBlank { "Object" }, strokes = stored)
        userPacks = userPacks.map { if (it.id == packId) it.copy(objects = it.objects + obj) else it }
        persist()
    }

    fun exportPack(p: ObjPack): ByteArray = json.encodeToString(ObjPack.serializer(), p.copy(bundled = false)).toByteArray()

    fun importPack(bytes: ByteArray): ObjPack? {
        val p = runCatching { json.decodeFromString(ObjPack.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        val copy = p.copy(id = UUID.randomUUID().toString(), bundled = false)
        userPacks = userPacks + copy
        persist()
        return copy
    }

    val allPacks: List<ObjPack> get() = bundled + userPacks

    /** Canvas units per object unit for real-scale packs (1 cm drawn at 1:100, in points). */
    const val CM_AT_1_100 = 72f / 2.54f / 100f

    /**
     * Builds strokes for [o] centered at (cx, cy). [iconSize] is the canvas size for icon objects;
     * [style] supplies brush, size and color when the pack uses the tool's style.
     */
    fun place(pack: ObjPack, o: LibObject, cx: Float, cy: Float, iconSize: Float, style: Stroke, drawingScale: Double): List<Stroke> {
        if (o.strokes.isNotEmpty()) {
            var maxX = 0f; var maxY = 0f
            for (s in o.strokes) for (i in 0 until s.pts.size / Stroke.STRIDE) {
                maxX = maxOf(maxX, s.pts[i * Stroke.STRIDE]); maxY = maxOf(maxY, s.pts[i * Stroke.STRIDE + 1])
            }
            return o.strokes.map { st ->
                val p = st.pts.copyOf()
                for (i in 0 until p.size / Stroke.STRIDE) { p[i * Stroke.STRIDE] += cx - maxX / 2; p[i * Stroke.STRIDE + 1] += cy - maxY / 2 }
                if (pack.useToolStyle && !st.brush.isItem) {
                    Stroke(0, style.brush, style.size, style.color, style.opacity, st.smoothing, 0, p, customBrush = style.customBrush)
                } else Stroke(0, st.brush, st.size, st.color, st.opacity, st.smoothing, 0, p, customBrush = st.customBrush, text = st.text, asset = st.asset)
            }
        }
        // Path objects
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (path in o.paths) for (q in path.pts) { minX = minOf(minX, q.x); maxX = maxOf(maxX, q.x); minY = minOf(minY, q.y); maxY = maxOf(maxY, q.y) }
        val k = if (pack.realScale) CM_AT_1_100 * (100.0 / drawingScale).toFloat()
        else iconSize / maxOf(maxX - minX, maxY - minY, 1f)
        val ox = cx - (minX + maxX) / 2f * k; val oy = cy - (minY + maxY) / 2f * k
        return o.paths.map { path ->
            val poly = path.pts.map { Pt(ox + it.x * k, oy + it.y * k) }.let { if (path.closed && it.isNotEmpty()) it + it[0] else it }
            val pts = Snap.samplesAlong(poly, 0.6f, 300f, step = maxOf(iconSize / 60f, 0.5f))
            val brush = when {
                path.fill -> BrushType.FILL
                pack.useToolStyle && style.brush.isBasic && style.brush != BrushType.FILL -> style.brush
                else -> BrushType.FIXED
            }
            val size = if (pack.useToolStyle && !path.fill) style.size * path.width else maxOf(iconSize / 70f, 0.3f) * path.width
            Stroke(0, brush, size, style.color, if (path.fill) 0.25f else style.opacity, 0f, 0, pts)
        }
    }

    // ------------------------------------------------------------------
    // Bundled packs (original drawings)
    // ------------------------------------------------------------------

    private class B {
        val paths = ArrayList<ObjPath>()
        fun line(vararg c: Float, w: Float = 1f) { paths.add(ObjPath(c.toList().chunked(2) { Pt(it[0], it[1]) }, false, width = w)) }
        fun poly(vararg c: Float, fill: Boolean = false, w: Float = 1f) { paths.add(ObjPath(c.toList().chunked(2) { Pt(it[0], it[1]) }, true, fill, w)) }
        fun rect(x: Float, y: Float, w: Float, h: Float, fill: Boolean = false, lw: Float = 1f) = poly(x, y, x + w, y, x + w, y + h, x, y + h, fill = fill, w = lw)
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, lw: Float = 1f) {
            val pts = ArrayList<Pt>()
            fun corner(cx: Float, cy: Float, a0: Float) { for (i in 0..6) { val a = (a0 + 90f * i / 6f) * PI.toFloat() / 180f; pts.add(Pt(cx + r * cos(a), cy + r * sin(a))) } }
            corner(x + w - r, y + r, -90f); corner(x + w - r, y + h - r, 0f); corner(x + r, y + h - r, 90f); corner(x + r, y + r, 180f)
            paths.add(ObjPath(pts, true, width = lw))
        }
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false, lw: Float = 1f, ry: Float = r) {
            paths.add(ObjPath(List(48) { i -> val a = 2 * PI.toFloat() * i / 48; Pt(cx + r * cos(a), cy + ry * sin(a)) }, true, fill, lw))
        }
        fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, lw: Float = 1f) {
            val n = 32
            paths.add(ObjPath(List(n + 1) { i -> val a = (a0 + (a1 - a0) * i / n) * PI.toFloat() / 180f; Pt(cx + r * cos(a), cy + r * sin(a)) }, false, width = lw))
        }
        fun regular(cx: Float, cy: Float, r: Float, n: Int, rot: Float = -90f, inner: Float = 0f) {
            val pts = ArrayList<Pt>()
            val count = if (inner > 0f) n * 2 else n
            for (i in 0 until count) {
                val rr = if (inner > 0f && i % 2 == 1) inner else r
                val a = (rot + 360f * i / count) * PI.toFloat() / 180f
                pts.add(Pt(cx + rr * cos(a), cy + rr * sin(a)))
            }
            paths.add(ObjPath(pts, true))
        }
        fun arrowHead(tx: Float, ty: Float, fromX: Float, fromY: Float, len: Float) {
            val a = kotlin.math.atan2(ty - fromY, tx - fromX)
            val a1 = a + 2.6f; val a2 = a - 2.6f
            line(tx + len * cos(a1), ty + len * sin(a1), tx, ty, tx + len * cos(a2), ty + len * sin(a2))
        }
    }

    private fun obj(pack: String, name: String, tags: String = "", build: B.() -> Unit) =
        LibObject("$pack.${name.lowercase().replace(' ', '-')}", name, tags, B().apply(build).paths)

    val bundled: List<ObjPack> by lazy {
        listOf(
            ObjPack("b.shapes", "Basic shapes", "Simple outlines to start from", objects = listOf(
                obj("shapes", "Circle") { circle(50f, 50f, 50f) },
                obj("shapes", "Square") { rect(0f, 0f, 100f, 100f) },
                obj("shapes", "Rounded square") { rrect(0f, 0f, 100f, 100f, 18f) },
                obj("shapes", "Triangle") { poly(50f, 0f, 100f, 87f, 0f, 87f) },
                obj("shapes", "Diamond") { poly(50f, 0f, 100f, 50f, 50f, 100f, 0f, 50f) },
                obj("shapes", "Pentagon") { regular(50f, 50f, 50f, 5) },
                obj("shapes", "Hexagon") { regular(50f, 50f, 50f, 6, rot = 0f) },
                obj("shapes", "Star") { regular(50f, 50f, 50f, 5, inner = 20f) },
                obj("shapes", "Heart", "love") {
                    val pts = List(60) { i ->
                        val t = 2 * PI.toFloat() * i / 60
                        Pt(50f + 3f * 16f * sin(t) * sin(t) * sin(t), 45f - 3f * (13f * cos(t) - 5f * cos(2 * t) - 2f * cos(3 * t) - cos(4 * t)))
                    }
                    paths.add(ObjPath(pts, true))
                },
                obj("shapes", "Speech bubble", "talk comment") {
                    paths.add(ObjPath(listOf(Pt(10f, 0f), Pt(90f, 0f), Pt(100f, 10f), Pt(100f, 60f), Pt(90f, 70f), Pt(40f, 70f), Pt(20f, 95f), Pt(24f, 70f), Pt(10f, 70f), Pt(0f, 60f), Pt(0f, 10f)), true))
                },
                obj("shapes", "Cloud") {
                    arc(25f, 55f, 20f, 90f, 260f); arc(45f, 32f, 24f, 190f, 330f); arc(72f, 38f, 22f, 240f, 380f); arc(78f, 62f, 18f, 300f, 450f)
                    line(78f, 80f, 25f, 75f)
                },
                obj("shapes", "Cylinder") { circle(50f, 15f, 40f, ry = 15f); line(10f, 15f, 10f, 85f); line(90f, 15f, 90f, 85f); arc(50f, 85f, 40f, 0f, 180f) },
                obj("shapes", "Cube") { poly(20f, 30f, 70f, 30f, 70f, 80f, 20f, 80f); line(20f, 30f, 40f, 10f, 90f, 10f, 70f, 30f); line(90f, 10f, 90f, 60f, 70f, 80f) },
            )),
            ObjPack("b.arrows", "Arrows", "Directions, flows and callouts", objects = listOf(
                obj("arrows", "Arrow") { line(0f, 50f, 100f, 50f); arrowHead(100f, 50f, 0f, 50f, 20f) },
                obj("arrows", "Double arrow") { line(0f, 50f, 100f, 50f); arrowHead(100f, 50f, 0f, 50f, 18f); arrowHead(0f, 50f, 100f, 50f, 18f) },
                obj("arrows", "Curved arrow") { arc(50f, 90f, 50f, 190f, 340f); arrowHead(97f, 73f, 90f, 60f, 18f) },
                obj("arrows", "Bent arrow") { line(10f, 10f, 10f, 80f, 95f, 80f); arrowHead(95f, 80f, 10f, 80f, 18f) },
                obj("arrows", "U-turn") { line(80f, 100f, 80f, 40f); arc(50f, 40f, 30f, 0f, -180f); line(20f, 40f, 20f, 70f); arrowHead(20f, 75f, 20f, 40f, 16f) },
                obj("arrows", "Circular arrow", "refresh cycle") { arc(50f, 50f, 45f, -60f, 240f); arrowHead(72f, 11f, 50f, 5f, 16f) },
                obj("arrows", "Block arrow") { poly(0f, 35f, 60f, 35f, 60f, 15f, 100f, 50f, 60f, 85f, 60f, 65f, 0f, 65f) },
                obj("arrows", "Chevron") { poly(0f, 0f, 60f, 0f, 100f, 50f, 60f, 100f, 0f, 100f, 40f, 50f) },
                obj("arrows", "Leader line", "callout") { line(0f, 100f, 50f, 30f, 100f, 30f); circle(0f, 100f, 4f, fill = true) },
            )),
            ObjPack("b.notes", "Annotation marks", "Marks for reviewing and labeling", objects = listOf(
                obj("notes", "Check") { line(0f, 55f, 35f, 90f, 100f, 10f, w = 1.6f) },
                obj("notes", "Cross") { line(0f, 0f, 100f, 100f, w = 1.6f); line(100f, 0f, 0f, 100f, w = 1.6f) },
                obj("notes", "Asterisk") { line(50f, 0f, 50f, 100f); line(7f, 25f, 93f, 75f); line(93f, 25f, 7f, 75f) },
                obj("notes", "Dimension line", "measure") { line(0f, 50f, 100f, 50f); line(0f, 35f, 0f, 65f); line(100f, 35f, 100f, 65f); arrowHead(0f, 50f, 100f, 50f, 10f); arrowHead(100f, 50f, 0f, 50f, 10f) },
                obj("notes", "North arrow", "compass") { circle(50f, 55f, 40f); poly(50f, 5f, 62f, 70f, 50f, 60f, 38f, 70f, fill = true); line(42f, 0f, 42f, -18f, 58f, 0f, 58f, -18f) },
                obj("notes", "Section marker") { circle(30f, 50f, 25f); line(5f, 50f, 55f, 50f); line(55f, 50f, 100f, 50f, w = 1.6f); arrowHead(100f, 50f, 55f, 50f, 14f) },
                obj("notes", "Level marker", "elevation") { poly(0f, 40f, 30f, 40f, 15f, 60f); line(30f, 40f, 100f, 40f) },
                obj("notes", "Revision cloud") { for (i in 0 until 10) { val a0 = i * 36f; arc(50f + 42f * cos(a0 * PI.toFloat() / 180f), 50f + 42f * sin(a0 * PI.toFloat() / 180f), 13f, a0 - 70f, a0 + 110f) } },
                obj("notes", "Bracket") { line(30f, 0f, 10f, 0f, 10f, 45f, 0f, 50f, 10f, 55f, 10f, 100f, 30f, 100f) },
                obj("notes", "Number badge") { circle(50f, 50f, 50f); circle(50f, 50f, 42f) },
                obj("notes", "Pin", "location") { arc(50f, 35f, 35f, 150f, 390f); line(19.7f, 52.5f, 50f, 100f, 80.3f, 52.5f); circle(50f, 35f, 12f) },
            )),
            ObjPack("b.people", "People", "Scale figures, drawn at 1:100 (fixed real size)", realScale = true, objects = listOf(
                obj("people", "Standing", "person figure") {
                    circle(25f, 12f, 11f); line(25f, 23f, 25f, 95f); line(4f, 50f, 25f, 32f, 46f, 50f); line(10f, 172f, 25f, 95f, 40f, 172f)
                },
                obj("people", "Walking", "person figure") {
                    circle(30f, 12f, 11f); line(30f, 23f, 27f, 95f); line(8f, 60f, 28f, 35f, 48f, 62f); line(5f, 170f, 27f, 95f, 52f, 168f)
                },
                obj("people", "Sitting", "person figure chair") {
                    circle(25f, 12f, 11f); line(25f, 23f, 25f, 85f, 70f, 85f, 70f, 130f); line(25f, 40f, 55f, 62f); line(10f, 130f, 80f, 130f)
                },
                obj("people", "Child", "person figure kid") {
                    circle(20f, 10f, 10f); line(20f, 20f, 20f, 65f); line(4f, 42f, 20f, 30f, 36f, 42f); line(8f, 110f, 20f, 65f, 32f, 110f)
                },
                obj("people", "Person (plan)", "top view") { circle(30f, 15f, 12f, ry = 12f); circle(30f, 15f, 30f, ry = 14f) },
                obj("people", "Wheelchair user", "accessible") {
                    circle(40f, 12f, 11f); line(40f, 23f, 40f, 70f, 80f, 70f, 80f, 115f); line(40f, 40f, 65f, 55f); circle(50f, 100f, 30f); circle(95f, 122f, 8f)
                },
            )),
            ObjPack("b.vehicles", "Vehicles", "Plan and side views at 1:100", realScale = true, objects = listOf(
                obj("vehicles", "Car (plan)", "auto parking") {
                    rrect(0f, 0f, 450f, 180f, 40f); line(120f, 15f, 150f, 30f, 150f, 150f, 120f, 165f); line(330f, 20f, 310f, 35f, 310f, 145f, 330f, 160f)
                    rect(70f, -8f, 60f, 10f); rect(70f, 178f, 60f, 10f); rect(330f, -8f, 60f, 10f); rect(330f, 178f, 60f, 10f)
                },
                obj("vehicles", "Van (plan)", "delivery") { rrect(0f, 0f, 520f, 200f, 30f); line(110f, 15f, 135f, 30f, 135f, 170f, 110f, 185f); line(135f, 100f, 520f, 100f) },
                obj("vehicles", "Truck (plan)", "lorry") { rrect(0f, 0f, 220f, 240f, 25f); rect(230f, 0f, 700f, 240f); line(60f, 20f, 80f, 40f, 80f, 200f, 60f, 220f) },
                obj("vehicles", "Bus (plan)") { rrect(0f, 0f, 1200f, 255f, 30f); for (i in 1..9) line(i * 120f, 10f, i * 120f, 245f) },
                obj("vehicles", "Bicycle (side)", "bike") {
                    circle(35f, 70f, 34f); circle(175f, 70f, 34f); line(35f, 70f, 80f, 70f, 140f, 25f, 175f, 70f); line(80f, 70f, 65f, 22f, 140f, 25f); line(55f, 18f, 80f, 18f); line(140f, 25f, 135f, 8f, 150f, 8f)
                },
                obj("vehicles", "Motorcycle (plan)", "motorbike") { rrect(0f, 25f, 220f, 30f, 14f); line(150f, 0f, 150f, 80f); circle(110f, 40f, 22f, ry = 18f) },
            )),
            ObjPack("b.furniture", "Furniture plan", "Floor-plan furniture at 1:100", realScale = true, objects = listOf(
                obj("furniture", "Single bed", "bedroom") { rect(0f, 0f, 90f, 200f); rect(10f, 8f, 70f, 35f); line(0f, 55f, 90f, 55f) },
                obj("furniture", "Double bed", "bedroom") { rect(0f, 0f, 160f, 200f); rect(10f, 8f, 65f, 35f); rect(85f, 8f, 65f, 35f); line(0f, 55f, 160f, 55f) },
                obj("furniture", "Bedside table", "nightstand") { rect(0f, 0f, 45f, 40f); circle(22.5f, 20f, 9f) },
                obj("furniture", "Wardrobe", "closet storage") { rect(0f, 0f, 120f, 60f); line(60f, 0f, 60f, 60f); line(0f, 0f, 60f, 60f); line(60f, 60f, 120f, 0f) },
                obj("furniture", "Sofa, 3 seats", "couch living") { rect(0f, 0f, 220f, 90f); rect(0f, 0f, 220f, 22f); rect(0f, 22f, 20f, 68f); rect(200f, 22f, 20f, 68f); line(80f, 22f, 80f, 90f); line(140f, 22f, 140f, 90f) },
                obj("furniture", "Armchair", "living") { rect(0f, 0f, 85f, 85f); rect(0f, 0f, 85f, 20f); rect(0f, 20f, 15f, 65f); rect(70f, 20f, 15f, 65f) },
                obj("furniture", "Coffee table", "living") { rect(0f, 0f, 120f, 60f) },
                obj("furniture", "Dining table, 6", "chairs") {
                    rect(25f, 25f, 160f, 90f)
                    for (x in listOf(40f, 87.5f, 135f)) { rect(x, 0f, 35f, 20f); rect(x, 120f, 35f, 20f) }
                },
                obj("furniture", "Round table, 4", "dining chairs") {
                    circle(60f, 60f, 45f); rect(42.5f, -2f, 35f, 18f); rect(42.5f, 104f, 35f, 18f); rect(-2f, 42.5f, 18f, 35f); rect(104f, 42.5f, 18f, 35f)
                },
                obj("furniture", "Desk and chair", "office study") { rect(0f, 0f, 140f, 70f); circle(70f, 95f, 25f); rect(52f, 115f, 36f, 8f) },
                obj("furniture", "Kitchen counter", "sink stove") {
                    rect(0f, 0f, 240f, 60f); rect(20f, 10f, 70f, 40f); circle(55f, 30f, 3f); rect(150f, 5f, 60f, 50f)
                    circle(165f, 18f, 9f); circle(195f, 18f, 9f); circle(165f, 42f, 9f); circle(195f, 42f, 9f)
                },
                obj("furniture", "Fridge", "kitchen refrigerator") { rect(0f, 0f, 70f, 70f); line(0f, 60f, 70f, 60f) },
                obj("furniture", "Toilet", "bathroom wc") { rect(0f, 0f, 40f, 18f); circle(20f, 45f, 18f, ry = 25f) },
                obj("furniture", "Wash basin", "bathroom sink") { rect(0f, 0f, 60f, 45f); circle(30f, 25f, 20f, ry = 14f); circle(30f, 8f, 2.5f) },
                obj("furniture", "Bathtub", "bathroom bath") { rect(0f, 0f, 170f, 75f); rrect(8f, 8f, 154f, 59f, 25f); circle(25f, 37.5f, 3f) },
                obj("furniture", "Shower", "bathroom") { rect(0f, 0f, 90f, 90f); line(0f, 0f, 90f, 90f); line(90f, 0f, 0f, 90f); circle(45f, 45f, 5f) },
                obj("furniture", "Door, 90", "swing opening") { line(0f, 0f, 0f, 90f, w = 1.4f); arc(0f, 0f, 90f, 0f, 90f, lw = 0.6f); line(0f, 0f, 90f, 0f, w = 0.4f) },
                obj("furniture", "Double door", "swing opening") {
                    line(0f, 0f, 0f, 80f, w = 1.4f); arc(0f, 0f, 80f, 0f, 90f, lw = 0.6f); line(160f, 0f, 160f, 80f, w = 1.4f); arc(160f, 0f, 80f, 90f, 180f, lw = 0.6f)
                },
                obj("furniture", "Window", "wall opening") { rect(0f, 0f, 120f, 20f); line(0f, 10f, 120f, 10f) },
                obj("furniture", "Stairs", "steps") {
                    rect(0f, 0f, 100f, 280f); for (i in 1..10) line(0f, i * 28f, 100f, i * 28f); line(50f, 270f, 50f, 20f); arrowHead(50f, 15f, 50f, 270f, 12f)
                },
                obj("furniture", "Plant", "pot tree") {
                    circle(30f, 30f, 30f); for (i in 0 until 6) { val a = i * 60f * PI.toFloat() / 180f; line(30f, 30f, 30f + 26f * cos(a), 30f + 26f * sin(a), w = 0.6f) }
                },
                obj("furniture", "TV unit", "media living") { rect(0f, 0f, 180f, 45f); rect(30f, 5f, 120f, 8f) },
            )),
        ).map { it.copy(bundled = true) }
    }
}
