package com.omarkallas.inkfield.model

import kotlinx.serialization.Serializable

/**
 * The document model. Everything is immutable: an edit produces a new [Drawing],
 * which makes undo/redo a simple stack of snapshots and keeps rendering, saving
 * and export reading the same data.
 */

enum class BrushType(val label: String, val family: String, val defaultSize: Float) {
    PEN("Pen", "Pens", 4f),
    FOUNTAIN("Fountain Pen", "Pens", 6f),
    DYNAMIC("Dynamic Pen", "Pens", 6f),
    FIXED("Fixed Width", "Pens", 3f),
    WIRE("Wire", "Wire", 1.5f),
    MARKER("Marker", "Marker", 16f),
    AIRBRUSH("Airbrush", "Airbrush", 40f);

    /** Drawn as a constant-width centerline instead of a variable-width outline. */
    val isCenterline: Boolean get() = this == FIXED || this == WIRE || this == MARKER || this == AIRBRUSH
}

enum class ToolType(val label: String) {
    BRUSH("Brush"),
    SLICE("Slice"),
    SELECT("Select"),
    PAN("Pan"),
    EYEDROPPER("Color picker"),
}

/**
 * One stroke. [pts] holds the raw input as a flat array, stride [STRIDE]:
 * x, y (canvas units), pressure (0..1), time (ms since stroke start).
 * Smoothing, width and texture are applied at render time, so every property
 * stays editable after drawing.
 */
@Serializable
class Stroke(
    val id: Long,
    val brush: BrushType,
    val size: Float,
    val color: Int,          // ARGB, alpha is the color's own opacity
    val opacity: Float,      // tool opacity 0..1, multiplies with color alpha
    val smoothing: Float,    // 0..1
    val layerId: Long,
    val pts: FloatArray,
    /** Show this stroke's length (drawn while Measure was on). */
    val measured: Boolean = false,
) {
    val count: Int get() = pts.size / STRIDE

    fun copy(
        id: Long = this.id,
        brush: BrushType = this.brush,
        size: Float = this.size,
        color: Int = this.color,
        opacity: Float = this.opacity,
        smoothing: Float = this.smoothing,
        layerId: Long = this.layerId,
        pts: FloatArray = this.pts,
        measured: Boolean = this.measured,
    ) = Stroke(id, brush, size, color, opacity, smoothing, layerId, pts, measured)

    companion object {
        const val STRIDE = 4
    }
}

@Serializable
data class Layer(
    val id: Long,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    /** Tool family this layer collects in Automatic sorting, or null for a user layer. */
    val family: String? = null,
)

@Serializable
data class Viewport(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f, // degrees
)

@Serializable
data class Drawing(
    val id: String,
    val name: String,
    val created: Long,
    val updated: Long,
    val background: Int = Paper.WHITE.color,
    /** Bottom to top. */
    val layers: List<Layer>,
    val activeLayerId: Long,
    val autoLayers: Boolean = true,
    /** Paint order: later strokes draw above earlier ones within a layer. */
    val strokes: List<Stroke> = emptyList(),
    val viewport: Viewport = Viewport(),
    val nextId: Long = 1000,
    val precision: Precision = Precision(),
) {
    fun layer(id: Long): Layer? = layers.firstOrNull { it.id == id }
    val activeLayer: Layer get() = layer(activeLayerId) ?: layers.last()

    companion object {
        fun blank(id: String, name: String, now: Long): Drawing {
            val first = Layer(id = 1, name = "Layer 1")
            return Drawing(
                id = id, name = name, created = now, updated = now,
                layers = listOf(first), activeLayerId = first.id,
            )
        }
    }
}

enum class Paper(val label: String, val color: Int, val dark: Boolean = false) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    CREAM("Cream", 0xFFF7F1E3.toInt()),
    GRAY("Light gray", 0xFFE9E9E7.toInt()),
    KRAFT("Kraft", 0xFFD8C3A0.toInt()),
    BLUEPRINT("Blueprint", 0xFF1E4D8C.toInt(), dark = true),
    DARKPRINT("Darkprint", 0xFF222428.toInt(), dark = true),
}

/** Metadata shown in the gallery without loading every stroke. */
@Serializable
data class DrawingInfo(
    val id: String,
    val name: String,
    val created: Long,
    val updated: Long,
    val deletedAt: Long? = null,
)
