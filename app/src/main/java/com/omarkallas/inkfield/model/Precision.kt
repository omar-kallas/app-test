package com.omarkallas.inkfield.model

import kotlinx.serialization.Serializable

enum class GridType(val label: String, val perspective: Boolean = false) {
    DOT("Dot"),
    GRAPH("Graph paper"),
    LINED("Lined paper"),
    ISOMETRIC("Isometric"),
    TRIANGLE("Triangle"),
    PERSP1("1-point perspective", true),
    PERSP2("2-point perspective", true),
    PERSP3("3-point perspective", true),
}

@Serializable
data class Pt(val x: Float, val y: Float)

/** Grid settings, per drawing. Spacing is in canvas units (points). */
@Serializable
data class GridSettings(
    val type: GridType = GridType.GRAPH,
    val spacing: Float = 24f,
    val divisions: Int = 4,
    val lineWeight: Float = 1f,
    /** Null = adapt to the paper; otherwise ARGB. */
    val color: Int? = null,
    val opacity: Float = 0.5f,
    /** Lined, isometric and triangle grids can turn 90°. */
    val portrait: Boolean = false,
    val originX: Float = 0f,
    val originY: Float = 0f,
    /** Rotation of basic grids in degrees. */
    val angle: Float = 0f,
    /** Perspective: rays per vanishing point. */
    val density: Int = 36,
    /** Vanishing points for perspective grids (1, 2 or 3 used). */
    val vps: List<Pt> = listOf(Pt(400f, 300f), Pt(-200f, 300f), Pt(1000f, 300f), Pt(400f, 2200f)),
)

@Serializable
data class SnapSettings(
    /** Strokes follow the grid lines and land on grid points. */
    val toGrid: Boolean = true,
    /** Strokes keep grid directions but can start anywhere. */
    val align: Boolean = false,
    val allowTurns: Boolean = true,
    /** A stroke ending near another stroke's end joins it. */
    val autoComplete: Boolean = true,
    /** Moving a selection snaps its corners and center to other strokes. */
    val keyPoints: Boolean = true,
    val activeLayerOnly: Boolean = false,
)

enum class UnitSystem { DIGITAL, METRIC, IMPERIAL }

enum class LengthUnit(val label: String, val system: UnitSystem, val points: Double) {
    PX("px", UnitSystem.DIGITAL, 1.0),
    PT("pt", UnitSystem.DIGITAL, 1.0),
    MM("mm", UnitSystem.METRIC, 72.0 / 25.4),
    CM("cm", UnitSystem.METRIC, 72.0 / 2.54),
    M("m", UnitSystem.METRIC, 72.0 / 0.0254),
    KM("km", UnitSystem.METRIC, 72.0 / 0.0254 * 1000.0),
    IN("in", UnitSystem.IMPERIAL, 72.0),
    FT("ft", UnitSystem.IMPERIAL, 72.0 * 12),
    YD("yd", UnitSystem.IMPERIAL, 72.0 * 36),
    MI("mi", UnitSystem.IMPERIAL, 72.0 * 63360),
}

@Serializable
data class MeasureSettings(
    val system: UnitSystem = UnitSystem.METRIC,
    /** Null = automatic unit for the system. */
    val unit: LengthUnit? = null,
    /** Drawing scale 1:n — one canvas unit represents n real units. */
    val scale: Double = 1.0,
    val decimals: Int = 1,
    val fractional: Boolean = false,
)

enum class GuideType(val label: String) { LINE("Line"), ARC("Arc"), ANGLE("Angle"), ELLIPSE("Ellipse"), RECT("Rectangle") }

/**
 * A shape guide. Points are in canvas units. Meaning by type:
 * LINE: a–b. ARC: center c, from a around to b (counterclockwise sweep [sweep] degrees, radius |a-c|).
 * ANGLE: vertex c, arms toward a and b. ELLIPSE/RECT: center c, half-sizes rx, ry, rotation [rot].
 */
@Serializable
data class Guide(
    val type: GuideType,
    val c: Pt,
    val a: Pt,
    val b: Pt,
    val rx: Float = 120f,
    val ry: Float = 80f,
    val rot: Float = 0f,
    val sweep: Float = 180f,
)

@Serializable
data class Precision(
    val gridOn: Boolean = false,
    val grid: GridSettings = GridSettings(),
    val snapOn: Boolean = false,
    val snap: SnapSettings = SnapSettings(),
    val measureOn: Boolean = false,
    val measure: MeasureSettings = MeasureSettings(),
    val guideOn: Boolean = false,
    val guideType: GuideType = GuideType.LINE,
    val guide: Guide? = null,
    val recognitionOn: Boolean = true,
    val recognitionMs: Int = 500,
)
