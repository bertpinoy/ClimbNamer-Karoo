package io.github.bertpinoy.climbnamer.route

import io.hammerhead.karooext.models.OnNavigationState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    /** Highest point of the climb (or its end when no elevation profile is available). */
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
)

object RouteClimbsBuilder {
    /** Returns null when not navigating or when there are no climbs. */
    fun fromNavigation(state: OnNavigationState.NavigationState): RouteClimbs? = when (state) {
        is OnNavigationState.NavigationState.NavigatingRoute -> build(
            name = state.name,
            polyline = state.routePolyline,
            elevationPolyline = state.routeElevationPolyline,
            routeDistance = state.routeDistance,
            climbs = state.climbs,
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

        // Elevation profile: (distance along route, elevation).
        val profile = elevationPolyline?.let { Geo.decodePolyline(it, 1) }?.takeIf { it.size >= 2 }

        // Some route data may be in the opposite direction (e.g. when navigating reversed).
        // Climbs go up, so pick the orientation in which they gain the most elevation.
        val reverse = profile != null && ascent(profile, climbs, total, reversed = true) >
            ascent(profile, climbs, total, reversed = false) + 1.0

        fun pointAt(distance: Double): LatLng {
            val d = (if (reverse) total - distance else distance).coerceIn(0.0, total) * scale
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
            val d = if (reverse) total - distance else distance
            var best = p[0]
            for (pt in p) if (abs(pt.first - d) < abs(best.first - d)) best = pt
            return best.second
        }

        val result = climbs.sortedBy { it.startDistance }.mapIndexed { i, c ->
            val start = c.startDistance
            val end = c.startDistance + c.length
            val step = max(25.0, c.length / 80.0)
            val samples = generateSequence(start) { it + step }.takeWhile { it < end }.toList() + end
            val points = samples.map { pointAt(it) }
            // Top = highest point of the climb according to the elevation profile.
            var topDist = end
            var topEle: Double? = null
            if (profile != null) {
                for (s in samples) {
                    val e = elevationAt(s) ?: continue
                    if (topEle == null || e > topEle) {
                        topEle = e
                        topDist = s
                    }
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
                points = points,
            )
        }
        val key = "${polyline.hashCode()}:${climbs.size}:${total.toInt()}"
        return RouteClimbs(key = key, name = name, routeDistance = total, climbs = result)
    }

    private fun ascent(
        profile: List<Pair<Double, Double>>,
        climbs: List<OnNavigationState.NavigationState.Climb>,
        total: Double,
        reversed: Boolean,
    ): Double {
        fun ele(d: Double): Double {
            val x = if (reversed) total - d else d
            var best = profile[0]
            for (pt in profile) if (abs(pt.first - x) < abs(best.first - x)) best = pt
            return best.second
        }
        return climbs.sumOf { c ->
            val a = ele(min(c.startDistance, total))
            val b = ele(min(c.startDistance + c.length, total))
            b - a
        }
    }
}
