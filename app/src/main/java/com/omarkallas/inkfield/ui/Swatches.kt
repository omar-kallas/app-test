package com.omarkallas.inkfield.ui

import androidx.core.graphics.ColorUtils

/** One named color in the swatch wheel. Names and values are Inkfield's own. */
class Swatch(val name: String, val color: Int)

class SwatchFamily(val name: String, val steps: List<Swatch>)

/**
 * Inkfield's swatch set, laid out as concentric rings:
 * a value ring (black to white), a gray ring (cool, warm, neutral),
 * and an outer ring of color families, each a column from deep to pale.
 */
object SwatchSet {
    private fun hsl(h: Float, s: Float, l: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))

    /** 12 value steps from black to white. */
    val tones: List<Swatch> = List(12) { i ->
        val l = i / 11f
        val name = when (i) { 0 -> "Black"; 11 -> "White"; else -> "Value $i" }
        Swatch(name, hsl(0f, 0f, l))
    }

    /** Cool, warm and neutral grays, 11 steps each (0 = palest). */
    val grays: List<Swatch> = buildList {
        val fams = listOf(Triple("Cool", 212f, 0.14f), Triple("Warm", 32f, 0.12f), Triple("Gray", 0f, 0f))
        for ((name, h, s) in fams) {
            for (i in 0..10) add(Swatch("$name $i", hsl(h, s, 0.94f - i * 0.075f)))
        }
    }

    private val hueFamilies = listOf(
        "Crimson" to 350f, "Red" to 2f, "Coral" to 12f, "Vermilion" to 20f, "Orange" to 28f, "Amber" to 38f,
        "Honey" to 46f, "Yellow" to 54f, "Lime" to 72f, "Leaf" to 95f, "Green" to 125f, "Jade" to 150f,
        "Teal" to 172f, "Aqua" to 185f, "Cyan" to 195f, "Sky" to 205f, "Azure" to 215f, "Blue" to 228f,
        "Indigo" to 245f, "Violet" to 265f, "Purple" to 282f, "Plum" to 300f, "Magenta" to 318f, "Rose" to 335f,
    )

    private val earthFamilies = listOf(
        Triple("Umber", 26f, 0.34f), Triple("Sienna", 18f, 0.52f), Triple("Sand", 36f, 0.42f),
        Triple("Peach", 22f, 0.62f), Triple("Olive", 62f, 0.30f), Triple("Slate", 210f, 0.18f),
    )

    /** Deep to pale. */
    private val lightness = floatArrayOf(0.24f, 0.35f, 0.47f, 0.60f, 0.73f, 0.86f)

    val families: List<SwatchFamily> = buildList {
        for ((name, h) in hueFamilies) {
            // Yellows look muddy when dark; shift their range lighter.
            val lift = if (h in 40f..80f) 0.08f else 0f
            val steps = List(6) { i ->
                val s = 0.82f - i * 0.05f
                Swatch("$name ${i + 1}", hsl(h, s, lightness[i] + lift))
            }
            add(SwatchFamily(name, steps))
        }
        for ((name, h, s) in earthFamilies) {
            val steps = List(6) { i -> Swatch("$name ${i + 1}", hsl(h, s, lightness[i])) }
            add(SwatchFamily(name, steps))
        }
    }
}
