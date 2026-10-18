package com.omarkallas.inkfield.engine

import com.omarkallas.inkfield.model.LengthUnit
import com.omarkallas.inkfield.model.MeasureSettings
import com.omarkallas.inkfield.model.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Converts canvas lengths (points at 1:1) to real-world text and back, honoring the drawing scale. */
object Units {

    fun unitsFor(system: UnitSystem): List<LengthUnit> = LengthUnit.entries.filter { it.system == system }

    /** The unit used to show [points] real points, picking automatically when no unit is fixed. */
    fun pick(points: Double, m: MeasureSettings): LengthUnit {
        m.unit?.let { if (it.system == m.system) return it }
        val a = abs(points)
        return when (m.system) {
            UnitSystem.DIGITAL -> LengthUnit.PT
            UnitSystem.METRIC -> when {
                a < LengthUnit.CM.points -> LengthUnit.MM
                a < LengthUnit.M.points -> LengthUnit.CM
                a < LengthUnit.KM.points -> LengthUnit.M
                else -> LengthUnit.KM
            }
            UnitSystem.IMPERIAL -> when {
                a < LengthUnit.FT.points -> LengthUnit.IN
                a < LengthUnit.MI.points / 10 -> LengthUnit.FT
                else -> LengthUnit.MI
            }
        }
    }

    fun length(canvasLen: Float, m: MeasureSettings): String {
        val pts = canvasLen.toDouble() * m.scale
        val u = pick(pts, m)
        val v = pts / u.points
        return number(v, u, m) + " " + u.label
    }

    fun area(canvasArea: Float, m: MeasureSettings): String {
        val pts2 = canvasArea.toDouble() * m.scale * m.scale
        val u = pick(kotlin.math.sqrt(abs(pts2)), m)
        val v = pts2 / (u.points * u.points)
        return number(v, u, m) + " " + u.label + "²"
    }

    private fun number(v: Double, u: LengthUnit, m: MeasureSettings): String {
        if (m.fractional && m.system == UnitSystem.IMPERIAL) {
            val whole = v.toLong()
            val sixteenths = ((v - whole) * 16).roundToInt()
            if (sixteenths == 0) return whole.toString()
            if (sixteenths == 16) return (whole + 1).toString()
            var num = sixteenths; var den = 16
            while (num % 2 == 0) { num /= 2; den /= 2 }
            return if (whole == 0L) "$num/$den" else "$whole $num/$den"
        }
        val d = if (u == LengthUnit.PX || u == LengthUnit.PT) minOf(m.decimals, 1) else m.decimals
        return String.format(Locale.US, "%.${d}f", v)
    }

    /**
     * Parses "7.5", "7.5 m", "30cm", "2 1/2 in" into a canvas length. A bare number uses
     * [defaultUnit]. Returns null when the text isn't a length.
     */
    fun parse(text: String, m: MeasureSettings, defaultUnit: LengthUnit): Float? {
        val t = text.trim().lowercase(Locale.US).replace(",", ".")
        if (t.isEmpty()) return null
        val unit = LengthUnit.entries.sortedByDescending { it.label.length }.firstOrNull { t.endsWith(it.label) }
        val numPart = if (unit != null) t.removeSuffix(unit.label).trim() else t
        val value = parseNumber(numPart) ?: return null
        val u = unit ?: defaultUnit
        return (value * u.points / m.scale).toFloat()
    }

    private fun parseNumber(s: String): Double? {
        s.toDoubleOrNull()?.let { return it }
        // "2 1/2" or "1/2"
        val parts = s.split(" ").filter { it.isNotBlank() }
        var total = 0.0
        for (p in parts) {
            if (p.contains("/")) {
                val (n, d) = p.split("/").let { (it.getOrNull(0)?.toDoubleOrNull() ?: return null) to (it.getOrNull(1)?.toDoubleOrNull() ?: return null) }
                if (d == 0.0) return null
                total += n / d
            } else total += p.toDoubleOrNull() ?: return null
        }
        return total
    }

    val scalePresets: List<Pair<String, Double>> = listOf(
        "1:1" to 1.0, "1:2" to 2.0, "1:5" to 5.0, "1:10" to 10.0, "1:12" to 12.0, "1:20" to 20.0,
        "1:24" to 24.0, "1:48" to 48.0, "1:50" to 50.0, "1:100" to 100.0, "1:200" to 200.0, "1:500" to 500.0, "1:1000" to 1000.0,
    )

    fun scaleLabel(n: Double): String =
        if (n == n.roundToInt().toDouble()) "1:${n.roundToInt()}" else String.format(Locale.US, "1:%.2f", n)
}
