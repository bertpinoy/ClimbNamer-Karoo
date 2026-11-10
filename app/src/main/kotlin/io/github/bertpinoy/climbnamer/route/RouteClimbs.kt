package io.github.bertpinoy.climbnamer.route

import io.hammerhead.karooext.models.OnNavigationState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Geometry of one climb on the active route. */
data class ClimbGeo(
    /** 0-based index in route order. */
    val index: Int,
    /** Distance along the route where the climb starts (m), as reported by the Karoo. */
    val startDistance: Double,
    val length: Double,
    val grade: Double,
    val totalElevation: Double,
    val start: LatLng,
    /** Highest point of the climb (or its end when no usable elevation profile is available). */
    val top: LatLng,
    val topElevation: Double?,
    /** Points along the climb, roughly every 25 m (max ~80). */
    val points: List<LatLng>,
) {
    val endDistance: Double get() = startDistance + length
}

/** The climbs of the route or destination the Karoo is navigating. */
data class RouteClimbs(
    /** Identifies the route; changes when another route is loaded. */
    val key: String,
    val name: String,
    val routeDistance: Double,
    val climbs: List<ClimbGeo>,
    /** What ClimbNamer received from the Karoo, shown in the app for troubleshooting. */
    val diagnostics: String = "",
)

/** How the elevation profile from the Karoo could be used. */
enum class ProfileStatus { OK, SWAPPED, UNUSABLE, MISSING }

object RouteClimbsBuilder {
    /** Returns null when not navigating or when there are no climbs. */
    fun fromNavigation(state: OnNavigationState.NavigationState): RouteClimbs? = when (state) {
        is OnNavigationState.NavigationState.NavigatingRoute -> build(
            name = state.name,
            polyline = state.routePolyline,
            elevationPolyline = state.routeElevationPolyline,
            routeDistance = state.routeDistance,
            climbs = state.climbs,
            reversed = state.reversed,
        )
        is OnNavigationState.NavigationState.NavigatingToDestination -> build(
            name = state.destination.name ?: "Destination",
            polyline = state.polyline,
            elevationPolyline = state.elevationPolyline,
            routeDistance = null,
            climbs = state.climbs,
        )
        else -> null
    }

    fun build(
        name: String,
        polyline: String,
        elevationPolyline: String?,
        routeDistance: Double?,
        climbs: List<OnNavigationState.NavigationState.Climb>,
        reversed: Boolean = false,
    ): RouteClimbs? {
        if (climbs.isEmpty()) return null
        val coords = Geo.decodePolyline(polyline, 5).map { LatLng(it.first, it.second) }
        if (coords.size < 2) return null

        val cum = DoubleArray(coords.size)
        for (i in 1 until coords.size) cum[i] = cum[i - 1] + Geo.haversine(coords[i - 1], coords[i])
        val geoLength = cum.last()
        val total = routeDistance?.takeIf { it > 0 } ?: geoLength
        // Karoo distances and our geometric distances differ slightly: scale to match.
        val scale = if (geoLength > 0) geoLength / total else 1.0

        // Climbs are placed along the polyline in the order the Karoo gives it, from its start.
        // (An earlier version tried to detect reversed data from the elevation profile; that
        // guess could flip the route and put climbs at the wrong place.)
        val (profile, profileStatus) = readProfile(elevationPolyline, total)

        fun pointAt(distance: Double): LatLng {
            val d = distance.coerceIn(0.0, total) * scale
            var lo = 0
            var hi = cum.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (cum[mid] <= d) lo = mid else hi = mid
            }
            val span = cum[hi] - cum[lo]
            val t = if (span > 0) (d - cum[lo]) / span else 0.0
            val a = coords[lo]
            val b = coords[hi]
            return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
        }

        fun elevationAt(distance: Double): Double? {
            val p = profile ?: return null
            var best = p[0]
            for (pt in p) if (abs(pt.first - distance) < abs(best.first - distance)) best = pt
            return best.second
        }

        val result = climbs.sortedBy { it.startDistance }.mapIndexed { i, c ->
            val start = c.startDistance
            val end = c.startDistance + c.length
            val step = max(25.0, c.length / 80.0)
            val samples = generateSequence(start) { it + step }.takeWhile { it < end }.toList() + end
            // Top = highest point of the climb according to the profile, else the end.
            var topDist = end
            var topEle: Double? = null
            for (s in samples) {
                val e = elevationAt(s) ?: break
                if (topEle == null || e > topEle) {
                    topEle = e
                    topDist = s
                }
            }
            ClimbGeo(
                index = i,
                startDistance = start,
                length = c.length,
                grade = c.grade,
                totalElevation = c.totalElevation,
                start = pointAt(start),
                top = pointAt(topDist),
                topElevation = topEle,
                points = samples.map { pointAt(it) },
            )
        }
        val diagnostics = String.format(
            Locale.US,
            "route %.1f km (polyline %.1f km) · %d climbs · elevation profile %s · reversed=%s",
            total / 1000,
            geoLength / 1000,
            climbs.size,
            profileStatus.name.lowercase(Locale.US),
            reversed,
        )
        val key = "${polyline.hashCode()}:${climbs.size}:${total.toInt()}"
        return RouteClimbs(key = key, name = name, routeDistance = total, climbs = result, diagnostics = diagnostics)
    }

    /**
     * The Karoo sends (distance, elevation) pairs. Only use them if they look right:
     * distances must increase and end near the route length. If the pairs turn out to be
     * (elevation, distance) they are swapped; anything else is ignored.
     */
    fun readProfile(encoded: String?, total: Double): Pair<List<Pair<Double, Double>>?, ProfileStatus> {
        if (encoded.isNullOrEmpty()) return null to ProfileStatus.MISSING
        val raw = runCatching { Geo.decodePolyline(encoded, 1) }.getOrNull()
        if (raw == null || raw.size < 2) return null to ProfileStatus.UNUSABLE
        fun plausibleDistances(values: List<Double>): Boolean {
            val increasing = values.zipWithNext().all { (a, b) -> b >= a - 1.0 }
            val last = values.last()
            return increasing && values.first() >= -1.0 && last > total * 0.8 && last < total * 1.2
        }
        fun plausibleElevations(values: List<Double>) = values.all { it > -500 && it < 9000 }
        val firsts = raw.map { it.first }
        val seconds = raw.map { it.second }
        return when {
            plausibleDistances(firsts) && plausibleElevations(seconds) -> raw to ProfileStatus.OK
            plausibleDistances(seconds) && plausibleElevations(firsts) ->
                raw.map { it.second to it.first } to ProfileStatus.SWAPPED
            else -> null to ProfileStatus.UNUSABLE
        }
    }
}
