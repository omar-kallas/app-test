package com.omarkallas.inkfield.prefs

import android.content.Context
import com.omarkallas.inkfield.model.BrushType
import com.omarkallas.inkfield.model.ToolType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class ToolSlot(
    val tool: ToolType,
    val brush: BrushType = BrushType.PEN,
    val size: Float,
    val opacity: Float = 1f,
    val smoothing: Float = 0.25f,
    val sizePresets: List<Float>,
    val opacityPresets: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f),
    val smoothingPresets: List<Float> = listOf(0f, 0.25f, 0.5f, 1f),
    /** Custom (stamp) brush id when [brush] is STAMP. */
    val custom: String? = null,
) {
    companion object {
        fun forCustom(b: com.omarkallas.inkfield.engine.CustomBrush): ToolSlot {
            val s = b.defaultSize
            return ToolSlot(
                tool = ToolType.BRUSH, brush = BrushType.STAMP, size = s, opacity = b.defaultOpacity, smoothing = 0.2f,
                sizePresets = listOf(s * 0.5f, s, s * 2f, s * 4f), custom = b.id,
            )
        }

        fun defaultFor(b: BrushType): ToolSlot {
            val s = b.defaultSize
            return ToolSlot(
                tool = ToolType.BRUSH, brush = b, size = s,
                opacity = when (b) { BrushType.MARKER -> 0.55f; BrushType.AIRBRUSH -> 0.35f; BrushType.WATERCOLOR -> 0.8f; else -> 1f },
                smoothing = if (b == BrushType.WIRE) 0.4f else 0.25f,
                sizePresets = listOf(s * 0.5f, s, s * 2f, s * 4f),
            )
        }

        fun tool(t: ToolType): ToolSlot = when (t) {
            ToolType.SLICE, ToolType.NUDGE -> ToolSlot(tool = t, size = if (t == ToolType.SLICE) 24f else 80f, sizePresets = listOf(8f, 24f, 60f, 120f))
            ToolType.TEXT -> ToolSlot(tool = t, size = 24f, sizePresets = listOf(12f, 24f, 48f, 96f))
            else -> ToolSlot(tool = t, size = 1f, sizePresets = listOf(1f, 1f, 1f, 1f))
        }

        val defaults: List<ToolSlot> = listOf(
            defaultFor(BrushType.PEN),
            defaultFor(BrushType.DYNAMIC),
            defaultFor(BrushType.FIXED),
            defaultFor(BrushType.WIRE),
            defaultFor(BrushType.MARKER),
            defaultFor(BrushType.AIRBRUSH),
            tool(ToolType.SLICE),
            tool(ToolType.SELECT),
        )
    }
}

@Serializable
data class NamedPalette(val name: String, val colors: List<Int>)

enum class FingerMode(val label: String) {
    AUTO("Auto: draw until a stylus is used"),
    DRAW("Fingers draw"),
    NAVIGATE("Fingers only pan, zoom and select"),
}

/** Small user settings kept in SharedPreferences. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("inkfield", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var slots: List<ToolSlot>
        get() = sp.getString("slots", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(ToolSlot.serializer()), it) }.getOrNull() }
            ?.takeIf { it.size == 8 } ?: ToolSlot.defaults
        set(v) = sp.edit().putString("slots", json.encodeToString(ListSerializer(ToolSlot.serializer()), v)).apply()

    var activeSlot: Int
        get() = sp.getInt("activeSlot", 0)
        set(v) = sp.edit().putInt("activeSlot", v).apply()

    var color: Int
        get() = sp.getInt("color", 0xFF1B1B1F.toInt())
        set(v) = sp.edit().putInt("color", v).apply()

    var palettes: List<NamedPalette>
        get() = sp.getString("palettes", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(NamedPalette.serializer()), it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(NamedPalette("Palette 1", palette))
        set(v) = sp.edit().putString("palettes", json.encodeToString(ListSerializer(NamedPalette.serializer()), v)).apply()

    var activePalette: Int
        get() = sp.getInt("activePalette", 0)
        set(v) = sp.edit().putInt("activePalette", v).apply()

    var paletteStrip: Boolean
        get() = sp.getBoolean("paletteStrip", true)
        set(v) = sp.edit().putBoolean("paletteStrip", v).apply()

    var objectsOpen: Boolean
        get() = sp.getBoolean("objectsOpen", false)
        set(v) = sp.edit().putBoolean("objectsOpen", v).apply()

    /** Legacy single palette (kept for migration). */
    var palette: List<Int>
        get() = sp.getString("palette", null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(Int.serializer()), it) }.getOrNull() }
            ?.takeIf { it.size == 8 } ?: DEFAULT_PALETTE
        set(v) = sp.edit().putString("palette", json.encodeToString(ListSerializer(Int.serializer()), v)).apply()

    var partialLasso: Boolean
        get() = sp.getBoolean("partialLasso", false)
        set(v) = sp.edit().putBoolean("partialLasso", v).apply()

    var fingerMode: FingerMode
        get() = runCatching { FingerMode.valueOf(sp.getString("fingerMode", FingerMode.AUTO.name)!!) }.getOrDefault(FingerMode.AUTO)
        set(v) = sp.edit().putString("fingerMode", v.name).apply()

    /** Set once a stylus has been used, so AUTO finger mode switches to navigation. */
    var stylusSeen: Boolean
        get() = sp.getBoolean("stylusSeen", false)
        set(v) = sp.edit().putBoolean("stylusSeen", v).apply()

    var pressureEnabled: Boolean
        get() = sp.getBoolean("pressure", true)
        set(v) = sp.edit().putBoolean("pressure", v).apply()

    var predictionEnabled: Boolean
        get() = sp.getBoolean("prediction", true)
        set(v) = sp.edit().putBoolean("prediction", v).apply()

    var highlightSelection: Boolean
        get() = sp.getBoolean("highlightSelection", true)
        set(v) = sp.edit().putBoolean("highlightSelection", v).apply()

    var holdToSelectMs: Int
        get() = sp.getInt("holdMs", 450)
        set(v) = sp.edit().putInt("holdMs", v).apply()

    var wheelX: Float
        get() = sp.getFloat("wheelX", -1f)
        set(v) = sp.edit().putFloat("wheelX", v).apply()

    var wheelY: Float
        get() = sp.getFloat("wheelY", -1f)
        set(v) = sp.edit().putFloat("wheelY", v).apply()

    var layersOpen: Boolean
        get() = sp.getBoolean("layersOpen", true)
        set(v) = sp.edit().putBoolean("layersOpen", v).apply()

    /** Last color mode: "swatch", "hsv" or "rgb". */
    var colorMode: String
        get() = sp.getString("colorMode", "swatch") ?: "swatch"
        set(v) = sp.edit().putString("colorMode", v).apply()

    var swatchRotation: Float
        get() = sp.getFloat("swatchRot", 0f)
        set(v) = sp.edit().putFloat("swatchRot", v).apply()

    /** Eyedropper: sample the stroke's own color and opacity (true) or the blended pixel (false). */
    var eyedropperAlpha: Boolean
        get() = sp.getBoolean("dropAlpha", true)
        set(v) = sp.edit().putBoolean("dropAlpha", v).apply()

    /** Eyedropper also copies the touched stroke's brush, size and smoothing. */
    var eyedropperBrush: Boolean
        get() = sp.getBoolean("dropBrush", false)
        set(v) = sp.edit().putBoolean("dropBrush", v).apply()

    var precisionOpen: Boolean
        get() = sp.getBoolean("precisionOpen", false)
        set(v) = sp.edit().putBoolean("precisionOpen", v).apply()

    var defaultPaper: Int
        get() = sp.getInt("paper", 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt("paper", v).apply()

    companion object {
        val DEFAULT_PALETTE: List<Int> = listOf(
            0xFF1B1B1F, 0xFF5B5F66, 0xFFC0392B, 0xFFE67E22,
            0xFFF1C40F, 0xFF27AE60, 0xFF2E86C1, 0xFF8E44AD,
        ).map { it.toInt() }
    }
}
