package io.github.bertpinoy.climbnamer.route

import io.hammerhead.karooext.models.OnNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class RouteClimbsTest {

    @Test
    fun decodesGooglePolylineExample() {
        // Example from Google's polyline documentation.
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-6)
        assertEquals(-120.2, pts[0].second, 1e-6)
        assertEquals(43.252, pts[2].first, 1e-6)
        assertEquals(-126.453, pts[2].second, 1e-6)
    }

    @Test
    fun encodeDecodeRoundTripPrecision1() {
        val pairs = listOf(0.0 to 30.2, 120.5 to 31.0, 980.3 to 75.4)
        val back = Geo.decodePolyline(encode(pairs, 1), 1)
        pairs.zip(back).forEach { (a, b) ->
            assertEquals(a.first, b.first, 0.05)
            assertEquals(a.second, b.second, 0.05)
        }
    }

    @Test
    fun locatesClimbAndTopAlongRoute() {
        // A straight 10 km route due north; a climb from 4 km to 6 km, highest at 6 km.
        val start = LatLng(50.0, 3.0)
        val route = (0..100).map { LatLng(start.lat + it * 0.0008983, start.lng) } // ~100 m steps
        val total = (1 until route.size).sumOf { Geo.haversine(route[it - 1], route[it]) }
        val profile = (0..100).map { i ->
            val d = i * total / 100
            val e = when {
                d < 4000 -> 50.0
                d < 6000 -> 50.0 + (d - 4000) * 0.05
                else -> 150.0 - (d - 6000) * 0.01
            }
            d to e
        }
        val climbs = listOf(OnNavigationState.NavigationState.Climb(4000.0, 2000.0, 5.0, 100.0))
        val result = RouteClimbsBuilder.build(
            name = "Test",
            polyline = encode(route.map { it.lat to it.lng }, 5),
            elevationPolyline = encode(profile, 1),
            routeDistance = total,
            climbs = climbs,
        )
        assertNotNull(result)
        val c = result!!.climbs.single()
        assertTrue("start ~4 km north", abs(Geo.haversine(start, c.start) - 4000) < 60)
        assertTrue("top ~6 km north", abs(Geo.haversine(start, c.top) - 6000) < 60)
        assertTrue(c.points.size > 10)
    }

    private val origin = LatLng(50.0, 3.0)
    private val straightRoute = (0..100).map { LatLng(origin.lat + it * 0.0008983, origin.lng) } // ~100 m steps
    private val straightTotal = (1 until straightRoute.size).sumOf { Geo.haversine(straightRoute[it - 1], straightRoute[it]) }
    private val oneClimb = listOf(OnNavigationState.NavigationState.Climb(4000.0, 2000.0, 5.0, 100.0))

    @Test
    fun neverFlipsTheRoute() {
        // Elevation profile that only "climbs" when read backwards: the climb must still be
        // placed 4 km from the START (regression: 0.1.0 flipped it to 4 km before the finish).
        val profile = (0..100).map { i ->
            val d = i * straightTotal / 100
            val r = straightTotal - d
            d to (if (r in 4000.0..6000.0) 50.0 + (r - 4000) * 0.05 else if (r > 6000) 150.0 else 50.0)
        }
        val result = RouteClimbsBuilder.build("NoFlip", encode(straightRoute.map { it.lat to it.lng }, 5), encode(profile, 1), straightTotal, oneClimb)
        val c = result!!.climbs.single()
        assertTrue(abs(Geo.haversine(origin, c.start) - 4000) < 60)
    }

    @Test
    fun placesClimbsAlongApproachAndRoute() {
        // Navigation starts ~3 km west of the route: the Karoo measures climbs along
        // approach (3 km eastwards) + route (10 km northwards), joining the route at 2 km.
        val approach = (0..30).map { LatLng(origin.lat + 2 * 0.008983, origin.lng - 0.042 + it * 0.0014) }
        val approachLen = (1 until approach.size).sumOf { Geo.haversine(approach[it - 1], approach[it]) }
        val climbs = listOf(
            OnNavigationState.NavigationState.Climb(1000.0, 500.0, 4.0, 20.0), // on the approach
            OnNavigationState.NavigationState.Climb(approachLen + 2000.0, 2000.0, 5.0, 100.0), // route km 4–6
        )
        val result = RouteClimbsBuilder.build(
            name = "Approach",
            polyline = encode(straightRoute.map { it.lat to it.lng }, 5),
            elevationPolyline = null,
            routeDistance = straightTotal,
            climbs = climbs,
            rejoinPolyline = encode(approach.map { it.lat to it.lng }, 5),
            rejoinDistance = 2000.0,
        )!!
        assertEquals(approachLen + straightTotal - 2000.0, result.routeDistance, 1.0)
        assertEquals(approachLen, result.approachLength, 1.0)
        val (a, b) = result.climbs
        assertTrue(a.onApproach)
        assertTrue("approach climb ~1 km along the approach", abs(Geo.haversine(approach.first(), a.start) - 1000) < 60)
        assertTrue(!b.onApproach)
        assertTrue("route climb starts ~4 km north of the route start", abs(Geo.haversine(origin, b.start) - 4000) < 60)
        assertTrue(result.diagnostics.contains("approach"))
    }

    @Test
    fun detectsSwappedProfile() {
        // Pairs given as (elevation, distance) instead of (distance, elevation).
        val profile = (0..100).map { i ->
            val d = i * straightTotal / 100
            val e = when {
                d < 4000 -> 50.0
                d < 5000 -> 50.0 + (d - 4000) * 0.1
                else -> 150.0 - (d - 5000) * 0.01
            }
            e to d
        }
        val (parsed, status) = RouteClimbsBuilder.readProfile(encode(profile, 1), straightTotal)
        assertEquals(ProfileStatus.SWAPPED, status)
        assertNotNull(parsed)
        val result = RouteClimbsBuilder.build("Swapped", encode(straightRoute.map { it.lat to it.lng }, 5), encode(profile, 1), straightTotal, oneClimb)
        // Highest point at 5 km, not at the climb's end (6 km).
        assertTrue(abs(Geo.haversine(origin, result!!.climbs.single().top) - 5000) < 60)
    }

    @Test
    fun ignoresUnusableProfile() {
        val garbage = (0..50).map { (it * 37 % 11).toDouble() to (it * 1000.0) * -1 }
        val (_, status) = RouteClimbsBuilder.readProfile(encode(garbage, 1), straightTotal)
        assertEquals(ProfileStatus.UNUSABLE, status)
        val result = RouteClimbsBuilder.build("Garbage", encode(straightRoute.map { it.lat to it.lng }, 5), encode(garbage, 1), straightTotal, oneClimb)
        val c = result!!.climbs.single()
        // Without a usable profile the top is the climb's end (6 km).
        assertTrue(abs(Geo.haversine(origin, c.top) - 6000) < 60)
        assertTrue(result.diagnostics.contains("unusable"))
    }

    /** Minimal Google polyline encoder for tests. */
    private fun encode(points: List<Pair<Double, Double>>, precision: Int): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var pa = 0L
        var pb = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x + 63).toInt().toChar())
        }
        for ((a, b) in points) {
            val ia = (a * factor).roundToLong()
            val ib = (b * factor).roundToLong()
            enc(ia - pa)
            enc(ib - pb)
            pa = ia
            pb = ib
        }
        return sb.toString()
    }
}
