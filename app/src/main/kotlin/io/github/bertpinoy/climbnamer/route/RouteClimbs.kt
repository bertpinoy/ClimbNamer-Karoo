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
    /** The navigated path, for finding where the rider is from GPS. */
    val track: Track? = null,
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
        val route: Path = Path.of(Geo.decodePolyline(polyline, 5).map { LatLng(it.first, it.second) }) ?: return null
        val routeTotal: Double = routeDistance?.takeIf { it > 0 } ?: route.length
        // Karoo route distances and our geometric distances differ slightly: scale to match.
        val routeScale: Double = if (route.length > 0) route.length / routeTotal else 1.0

        val approach: Path? = rejoinPolyline?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(Geo.decodePolyline(it, 5).map { p -> LatLng(p.first, p.second) }) }
        val approachLength: Double = approach?.length ?: 0.0
        // Where the approach joins the route. The Karoo's rejoinDistance turned out to be
        // unreliable (it reported the route's end while the approach joined mid-route), so the
        // join point is taken from the map: the route point closest to the end of the approach.
        val geoJoin: Double? = approach?.let { route.closestDistanceTo(it.end) }
        val joinAt: Double = when {
            approach == null -> 0.0
            geoJoin != null -> (geoJoin / routeScale).coerceIn(0.0, routeTotal)
            else -> (rejoinDistance ?: 0.0).coerceIn(0.0, routeTotal)
        }
        val total: Double = approachLength + (routeTotal - joinAt)

        /** Position along the navigated path: approach first, then the route from the join point. */
        fun pointAt(distance: Double): LatLng {
            val d: Double = distance.coerceIn(0.0, total)
            return if (approach != null && d < approachLength) {
                approach.pointAt(d)
            } else {
                route.pointAt((d - approachLength + joinAt) * routeScale)
            }
        }

        // The elevation profile covers the route only (not the approach).
        val (profile: List<Pair<Double, Double>>?, profileStatus: ProfileStatus) = readProfile(elevationPolyline, routeTotal)

        // Which climbs to use. Navigating from elsewhere, the Karoo only lists climbs on the way to
        // the route, so the climbs on the route part that will be ridden (from the join point) are
        // detected from the route's elevation profile. Otherwise the Karoo's own list is used.
        val approachClimbs: List<OnNavigationState.NavigationState.Climb> =
            if (approach != null) climbs.filter { it.startDistance < approachLength } else climbs
        val detected: List<OnNavigationState.NavigationState.Climb> = if (approach != null && profile != null) {
            val approachEnd: Double = approachClimbs.maxOfOrNull { it.startDistance + it.length } ?: 0.0
            ClimbDetector.detect(profile, joinAt, routeTotal)
                .map { c: ClimbDetector.Climb ->
                    OnNavigationState.NavigationState.Climb(approachLength + (c.start - joinAt), c.length, c.grade, c.gain)
                }
                .filter { it.startDistance >= approachEnd - 50.0 }
        } else {
            emptyList()
        }
        val used: List<OnNavigationState.NavigationState.Climb> =
            if (approach != null && profile != null) approachClimbs + detected else climbs
        if (used.isEmpty()) return null

        fun elevationAt(distance: Double): Double? {
            val p: List<Pair<Double, Double>> = profile ?: return null
            if (distance < approachLength) return null
            val routeD: Double = distance - approachLength + joinAt
            var best: Pair<Double, Double> = p[0]
            for (pt in p) if (abs(pt.first - routeD) < abs(best.first - routeD)) best = pt
            return best.second
        }

        val result: List<ClimbGeo> = used.sortedBy { it.startDistance }.mapIndexed { i, c ->
            val start: Double = c.startDistance
            // A climb can run past the end of the navigated path (e.g. when the Karoo joins a loop
            // route at its end). Only use the part that exists; squeezing the rest onto the last
            // point gave names of the road at the end of the route.
            val end: Double = minOf(c.startDistance + c.length, total)
            val step: Double = max(25.0, c.length / 80.0)
            val samples: List<Double> = if (start >= end) {
                listOf(minOf(start, total))
            } else {
                generateSequence(start) { it + step }.takeWhile { it < end }.toList() + end
            }
            // Top = highest point of the climb according to the profile, else the end.
            var topDist: Double = end
            var topEle: Double? = null
            for (s in samples) {
                val e: Double = elevationAt(s) ?: continue
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

        val beyond: Int = used.count { it.startDistance + it.length > total * 1.02 }
        val diagnostics: String = buildString {
            append(String.format(Locale.US, "route %.1f km (polyline %.1f km)", routeTotal / 1000, route.length / 1000))
            if (approach != null) {
                append(String.format(Locale.US, " · approach %.1f km, joins route at %.1f km", approachLength / 1000, joinAt / 1000))
                if (rejoinDistance != null) append(String.format(Locale.US, " (Karoo: approach %.1f km)", rejoinDistance / 1000))
            }
            if (approach != null && profile != null) {
                append(" · ${approachClimbs.size} climbs from the Karoo on the approach, ${detected.size} detected on the route")
            } else {
                append(" · ${used.size} climbs")
            }
            if (beyond > 0) append(" ($beyond beyond the end!)")
            append(" · elevation profile ${profileStatus.name.lowercase(Locale.US)} · reversed=$reversed")
        }
        val key: String = "${polyline.hashCode()}:${used.hashCode()}:${total.toInt()}"
        return RouteClimbs(
            key = key,
            name = name,
            routeDistance = total,
            climbs = result,
            approachLength = approachLength,
            diagnostics = diagnostics,
            track = Track(approach, approachLength, route, joinAt * routeScale, routeScale),
        )
    }

    /**
     * The Karoo sends (distance, elevation) pairs. Only use them if they look right:
     * distances must increase and end near the route length. If the pairs turn out to be
     * (elevation, distance) they are swapped; anything else is ignored.
     */
    fun readProfile(encoded: String?, total: Double): Pair<List<Pair<Double, Double>>?, ProfileStatus> {
        if (encoded.isNullOrEmpty()) return null to ProfileStatus.MISSING
        val raw: List<Pair<Double, Double>>? = runCatching { Geo.decodePolyline(encoded, 1) }.getOrNull()
        if (raw == null || raw.size < 2) return null to ProfileStatus.UNUSABLE
        fun plausibleDistances(values: List<Double>): Boolean {
            val increasing: Boolean = values.zipWithNext().all { (a, b) -> b >= a - 1.0 }
            val last: Double = values.last()
            return increasing && values.first() >= -1.0 && last > total * 0.8 && last < total * 1.2
        }
        fun plausibleElevations(values: List<Double>): Boolean = values.all { it > -500 && it < 9000 }
        val firsts: List<Double> = raw.map { it.first }
        val seconds: List<Double> = raw.map { it.second }
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
    val end: LatLng get() = coords.last()

    /**
     * Distance along this path of the point closest to [p]. When several parts of the path are
     * (almost) equally close, e.g. the shared start/end of a loop, the earliest one wins:
     * after joining, the Karoo follows the route forwards from there.
     */
    fun closestDistanceTo(p: LatLng): Double {
        val candidates: List<Pair<Double, Double>> = projections(p)
        val best: Double = candidates.minOf { it.first }
        return candidates.filter { it.first <= best + 30.0 }.minOf { it.second }
    }

    /** For every segment: (distance from [p] to the segment in m, distance along the path of the closest point). */
    fun projections(p: LatLng): List<Pair<Double, Double>> {
        val candidates: ArrayList<Pair<Double, Double>> = ArrayList()
        for (i in 1 until coords.size) {
            val a: LatLng = coords[i - 1]
            val b: LatLng = coords[i]
            val offset: Double = Geo.distToSegment(p, a, b)
            // Position of the projection along the segment.
            val k: Double = Math.cos(Math.toRadians(p.lat))
            val dx: Double = (b.lng - a.lng) * k
            val dy: Double = b.lat - a.lat
            val len2: Double = dx * dx + dy * dy
            val t: Double = if (len2 > 0) ((((p.lng - a.lng) * k) * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val along: Double = cum[i - 1] + t * (cum[i] - cum[i - 1])
            candidates += offset to along
        }
        return candidates
    }

    fun pointAt(distance: Double): LatLng {
        val d: Double = distance.coerceIn(0.0, length)
        var lo: Int = 0
        var hi: Int = cum.size - 1
        while (hi - lo > 1) {
            val mid: Int = (lo + hi) / 2
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val span: Double = cum[hi] - cum[lo]
        val t: Double = if (span > 0) (d - cum[lo]) / span else 0.0
        val a: LatLng = coords[lo]
        val b: LatLng = coords[hi]
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    companion object {
        fun of(coords: List<LatLng>): Path? {
            if (coords.size < 2) return null
            val cum: DoubleArray = DoubleArray(coords.size)
            for (i in 1 until coords.size) cum[i] = cum[i - 1] + Geo.haversine(coords[i - 1], coords[i])
            return Path(coords, cum)
        }
    }
}

/**
 * The path the Karoo navigates (approach, then the route from where it is joined), used to turn a
 * GPS position into a distance along that path, in the same units as the Karoo's climb distances.
 */
class Track(
    private val approach: Path?,
    private val approachLength: Double,
    private val route: Path,
    /** Geometric distance along [route] where the approach joins it. */
    private val joinAtGeo: Double,
    /** Geometric route length / Karoo route length. */
    private val routeScale: Double,
) {
    /**
     * Distance along the navigated path (m) for [position], or null when the rider is more than
     * [MAX_OFFSET] m away from it (e.g. a detour). With a [previous] distance, the match nearest to
     * it going forwards is used, so loops and roads ridden twice don't make the position jump.
     */
    fun locate(position: LatLng, previous: Double?): Double? {
        val candidates: ArrayList<Pair<Double, Double>> = ArrayList() // (offset m, distance along path)
        approach?.projections(position)?.forEach { (offset: Double, along: Double) -> candidates += offset to along }
        route.projections(position).forEach { (offset: Double, along: Double) ->
            if (along >= joinAtGeo - 1.0) candidates += offset to (approachLength + (along - joinAtGeo) / routeScale)
        }
        val near: List<Pair<Double, Double>> = candidates.filter { it.first <= MAX_OFFSET }
        if (near.isEmpty()) return null
        if (previous != null) {
            val ahead: List<Pair<Double, Double>> = near.filter { it.second >= previous - 200.0 && it.second <= previous + 3000.0 }
            if (ahead.isNotEmpty()) return ahead.minByOrNull { it.first }!!.second
        }
        val best: Double = near.minOf { it.first }
        return near.filter { it.first <= best + 30.0 }.minOf { it.second }
    }

    companion object {
        const val MAX_OFFSET: Double = 60.0
    }
}
