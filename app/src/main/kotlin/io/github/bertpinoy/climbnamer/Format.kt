package io.github.bertpinoy.climbnamer

import java.util.Locale

/** Formats distances/elevations in the rider's preferred units. */
data class Units(val imperialDistance: Boolean = false, val imperialElevation: Boolean = false) {
    fun distance(m: Double): String = if (imperialDistance) {
        val mi: Double = m / 1609.344
        if (mi < 0.1) String.format(Locale.US, "%d ft", (m * 3.28084).toInt()) else String.format(Locale.US, "%.1f mi", mi)
    } else {
        if (m < 1000) String.format(Locale.US, "%d m", m.toInt()) else String.format(Locale.US, "%.1f km", m / 1000)
    }

    fun elevation(m: Double): String = if (imperialElevation) {
        String.format(Locale.US, "+%d ft", (m * 3.28084).toInt())
    } else {
        String.format(Locale.US, "+%d m", m.toInt())
    }

    fun grade(pct: Double): String = String.format(Locale.US, "%.1f%%", pct)

    /** "2.8 km · 3.8% · +124 m" */
    fun climbStats(length: Double, grade: Double, gain: Double): String =
        "${distance(length)} · ${grade(grade)} · ${elevation(gain)}"
}
