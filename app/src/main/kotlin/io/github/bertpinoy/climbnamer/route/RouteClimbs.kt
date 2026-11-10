package io.github.bertpinoy.climbnamer.route

import io.hammerhead.karooext.models.OnNavigationState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Geometry of one climb on the active route. */
data class ClimbGeo(
    /** 0-based index in route order. */
    val index: Int,
    /** Distance along the navigated path where the climb starts (m), as reported by the Karoo. */
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
    /** True when the climb is on the way to the start of the route (the approach). */
    val onApproach: Boolean = false,
) {
    val endDistance: Double get() = startDistance + length
}

/** The climbs of the route or destination the Karoo is navigating. */
data class RouteClimbs(
    /** Identifies the route; changes when another route is loaded. */
    val key: String,
    val name: String,
    /**
     * Length of the whole navigated path (m): approach + route from where it is joined.
     * Climb distances and progress are measured along this path.
     */
    val routeDistance: Double,
    val climbs: List<ClimbGeo>,
    /** Length of the approach to the route (m); 0 when already on the route. */
    val approachLength: Double = 0.0,
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
            rejoinPolyline = state.rejoinPolyline,
            rejoinDistance = state.rejoinDistance,
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

    /**
     * When the rider is not on the route yet, the Karoo first guides them to it along
     * [rejoinPolyline], joining the route at [rejoinDistance]. The Karoo then reports climbs
     * along that whole path (approach + route from the join point), so climbs are placed on it.
     */
    fun build(
        name: String,
        polyline: String,
        elevationPolyline: String?,
        routeDistance: Double?,
        climbs: List<OnNavigationState.NavigationState.Climb>,
        reversed: Boolean = false,
        rejoinPolyline: String? = null,
        rejoinDistance: Double? = null,
    ): RouteClimbs? {
        if (climbs.isEmpty()) return null
        val route = Path.of(Geo.decodePolyline(polyline, 5).map { LatLng(it.first, it.second) }) ?: return null
        val routeTotal = routeDistance?.takeIf { it > 0 } ?: route.length
        // Karoo route distances and our geometric distances differ slightly: scale to match.
        val routeScale = if (route.length > 0) route.length / routeTotal else 1.0

        val approach = rejoinPolyline?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(Geo.decodePolyline(it, 5).map { p -> LatLng(p.first, p.second) }) }
        val approachLength = approach?.length ?: 0.0
        val joinAt = if (approach != null) (rejoinDistance ?: 0.0).coerceIn(0.0, routeTotal) else 0.0
        val total = approachLength + (routeTotal - joinAt)

        /** Position along the navigated path: approach first, then the route from the join point. */
        fun pointAt(distance: Double): LatLng {
            val d = distance.coerceIn(0.0, total)
            return if (approach != null && d < approachLength) {
                approach.pointAt(d)
            } else {
                route.pointAt((d - approachLength + joinAt) * routeScale)
            }
        }

        // The elevation profile covers the route only (not the approach).
        val (profile, profileStatus) = readProfile(elevationPolyline, routeTotal)

        fun elevationAt(distance: Double): Double? {
            val p = profile ?: return null
            if (distance < approachLength) return null
            val routeD = distance - approachLength + joinAt
            var best = p[0]
            for (pt in p) if (abs(pt.first - routeD) < abs(best.first - routeD)) best = pt
            return best.second
        }

        val result = climbs.sortedBy { it.startDistance }.mapIndexed { i, c ->
            val start = c.startDistance
            // A climb can run past the end of the navigated path (e.g. when the Karoo joins a loop
            // route at its end). Only use the part that exists; squeezing the rest onto the last
            // point gave names of the road at the end of the route.
            val end = minOf(c.startDistance + c.length, total)
            val step = max(25.0, c.length / 80.0)
            val samples = if (start >= end) {
                listOf(minOf(start, total))
            } else {
                generateSequence(start) { it + step }.takeWhile { it < end }.toList() + end
            }
            // Top = highest point of the climb according to the profile, else the end.
            var topDist = end
            var topEle: Double? = null
            for (s in samples) {
                val e = elevationAt(s) ?: continue
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
                onApproach = approach != null && start < approachLength,
            )
        }

        val beyond = climbs.count { it.startDistance + it.length > total * 1.02 }
        val diagnostics = buildString {
            append(String.format(Locale.US, "route %.1f km (polyline %.1f km)", routeTotal / 1000, route.length / 1000))
            if (approach != null) {
                append(String.format(Locale.US, " · approach %.1f km, joins route at %.1f km", approachLength / 1000, joinAt / 1000))
            }
            append(" · ${climbs.size} climbs")
            if (beyond > 0) append(" ($beyond beyond the end!)")
            append(" · elevation profile ${profileStatus.name.lowercase(Locale.US)} · reversed=$reversed")
        }
        val key = "${polyline.hashCode()}:${climbs.hashCode()}:${total.toInt()}"
        return RouteClimbs(
            key = key,
            name = name,
            routeDistance = total,
            climbs = result,
            approachLength = approachLength,
            diagnostics = diagnostics,
        )
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

/** A polyline with cumulative distances, for finding the point at a given distance. */
class Path private constructor(private val coords: List<LatLng>, private val cum: DoubleArray) {
    val length: Double get() = cum.last()

    fun pointAt(distance: Double): LatLng {
        val d = distance.coerceIn(0.0, length)
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

    companion object {
        fun of(coords: List<LatLng>): Path? {
            if (coords.size < 2) return null
            val cum = DoubleArray(coords.size)
            for (i in 1 until coords.size) cum[i] = cum[i - 1] + Geo.haversine(coords[i - 1], coords[i])
            return Path(coords, cum)
        }
    }
}
