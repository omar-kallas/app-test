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
            ToolType.FILL -> ToolSlot(tool = t, size = 1f, sizePresets = listOf(1f, 1f, 1f, 1f))
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

/** Actions that gestures, stylus buttons and shortcuts can trigger. */
enum class GestureAction(val label: String) {
    NONE("Do nothing"),
    UNDO("Undo"),
    REDO("Redo"),
    SELECT_LAST("Select last item"),
    SELECT_ALL("Select all on layer"),
    SHOW_LAYERS("Show layers"),
    SHOW_COLORS("Show colors"),
    TOOL_SETUP("Brushes menu"),
    SHOW_OBJECTS("Show objects"),
    TOGGLE_GUIDE("Toggle shape guide"),
    TOGGLE_UI("Hide / show interface"),
    TOGGLE_ROTATION("Toggle canvas rotation"),
    TOGGLE_ZOOM("Toggle canvas zoom"),
    ERASER("Switch to eraser"),
}

enum class ToolbarMode(val label: String) { WHEEL("Tool wheel"), BAR_BOTTOM("Toolbar at the bottom"), BAR_TOP("Toolbar at the top") }

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

    var fillThreshold: Float
        get() = sp.getFloat("fillThreshold", 0.3f)
        set(v) = sp.edit().putFloat("fillThreshold", v).apply()

    /** Fill looks at all visible layers (true) or only the active layer. */
    var fillAllLayers: Boolean
        get() = sp.getBoolean("fillAll", true)
        set(v) = sp.edit().putBoolean("fillAll", v).apply()

    var lockRatio: Boolean
        get() = sp.getBoolean("lockRatio", false)
        set(v) = sp.edit().putBoolean("lockRatio", v).apply()

    var wheelHidden: Boolean
        get() = sp.getBoolean("wheelHidden", false)
        set(v) = sp.edit().putBoolean("wheelHidden", v).apply()

    var precisionOpen: Boolean
        get() = sp.getBoolean("precisionOpen", false)
        set(v) = sp.edit().putBoolean("precisionOpen", v).apply()

    private fun action(key: String, def: GestureAction) =
        runCatching { GestureAction.valueOf(sp.getString(key, def.name)!!) }.getOrDefault(def)
    private fun setAction(key: String, v: GestureAction) = sp.edit().putString(key, v.name).apply()

    var tap2: GestureAction
        get() = action("tap2", GestureAction.UNDO)
        set(v) = setAction("tap2", v)
    var tap3: GestureAction
        get() = action("tap3", GestureAction.REDO)
        set(v) = setAction("tap3", v)
    var tap4: GestureAction
        get() = action("tap4", GestureAction.TOGGLE_UI)
        set(v) = setAction("tap4", v)
    var stylusButton: GestureAction
        get() = action("stylusBtn", GestureAction.NONE)
        set(v) = setAction("stylusBtn", v)

    var holdSelects: Boolean
        get() = sp.getBoolean("holdSelects", true)
        set(v) = sp.edit().putBoolean("holdSelects", v).apply()
    var zoomEnabled: Boolean
        get() = sp.getBoolean("zoomOn", true)
        set(v) = sp.edit().putBoolean("zoomOn", v).apply()
    var rotateEnabled: Boolean
        get() = sp.getBoolean("rotateOn", true)
        set(v) = sp.edit().putBoolean("rotateOn", v).apply()
    var zoomSnap: Boolean
        get() = sp.getBoolean("zoomSnap", true)
        set(v) = sp.edit().putBoolean("zoomSnap", v).apply()
    var rotateSnap: Boolean
        get() = sp.getBoolean("rotateSnap", true)
        set(v) = sp.edit().putBoolean("rotateSnap", v).apply()

    var toolbarMode: ToolbarMode
        get() = runCatching { ToolbarMode.valueOf(sp.getString("toolbar", "WHEEL")!!) }.getOrDefault(ToolbarMode.WHEEL)
        set(v) = sp.edit().putString("toolbar", v.name).apply()
    var uiScale: Float
        get() = sp.getFloat("uiScale", 1f)
        set(v) = sp.edit().putFloat("uiScale", v).apply()
    var shortcutsEnabled: Boolean
        get() = sp.getBoolean("shortcuts", true)
        set(v) = sp.edit().putBoolean("shortcuts", v).apply()

    /** Keyboard shortcuts: action name → "ctrl+shift+Z" style key spec. */
    var shortcuts: Map<String, String>
        get() = sp.getString("shortcutMap", null)?.let { runCatching { json.decodeFromString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() }
            ?: DEFAULT_SHORTCUTS
        set(v) = sp.edit().putString("shortcutMap", json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer()), v)).apply()

    var defaultPaper: Int
        get() = sp.getInt("paper", 0xFFFFFFFF.toInt())
        set(v) = sp.edit().putInt("paper", v).apply()

    companion object {
        val DEFAULT_SHORTCUTS: Map<String, String> = mapOf(
            "UNDO" to "ctrl+Z", "REDO" to "ctrl+shift+Z", "DUPLICATE" to "ctrl+D", "DELETE" to "DEL",
            "DESELECT" to "ESCAPE", "SELECT_ALL" to "ctrl+A", "TOGGLE_UI" to "TAB", "SHOW_LAYERS" to "L",
            "ERASER" to "E", "PAN" to "SPACE", "ZOOM_IN" to "ctrl+EQUALS", "ZOOM_OUT" to "ctrl+MINUS", "RESET_VIEW" to "ctrl+0",
        )

        val SHORTCUT_LABELS: Map<String, String> = mapOf(
            "UNDO" to "Undo", "REDO" to "Redo", "DUPLICATE" to "Duplicate selection", "DELETE" to "Delete selection",
            "DESELECT" to "Clear selection", "SELECT_ALL" to "Select all on layer", "TOGGLE_UI" to "Hide / show interface",
            "SHOW_LAYERS" to "Show layers", "ERASER" to "Eraser", "PAN" to "Pan (hold)", "ZOOM_IN" to "Zoom in",
            "ZOOM_OUT" to "Zoom out", "RESET_VIEW" to "Reset view",
        )

        val DEFAULT_PALETTE: List<Int> = listOf(
            0xFF1B1B1F, 0xFF5B5F66, 0xFFC0392B, 0xFFE67E22,
            0xFFF1C40F, 0xFF27AE60, 0xFF2E86C1, 0xFF8E44AD,
        ).map { it.toInt() }
    }
}

/** Key combinations stored as text like "ctrl+shift+Z". */
object KeySpec {
    fun of(ev: android.view.KeyEvent): String {
        val parts = ArrayList<String>()
        if (ev.isCtrlPressed || ev.isMetaPressed) parts.add("ctrl")
        if (ev.isAltPressed) parts.add("alt")
        if (ev.isShiftPressed) parts.add("shift")
        parts.add(android.view.KeyEvent.keyCodeToString(ev.keyCode).removePrefix("KEYCODE_"))
        return parts.joinToString("+")
    }

    fun matches(spec: String, ev: android.view.KeyEvent): Boolean {
        val parts = spec.split("+")
        val key = parts.last()
        val ctrl = "ctrl" in parts; val alt = "alt" in parts; val shift = "shift" in parts
        val code = android.view.KeyEvent.keyCodeFromString("KEYCODE_$key")
        return code == ev.keyCode && ctrl == (ev.isCtrlPressed || ev.isMetaPressed) && alt == ev.isAltPressed && shift == ev.isShiftPressed
    }
}
