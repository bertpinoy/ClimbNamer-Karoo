package io.github.bertpinoy.climbnamer.route

import io.hammerhead.karooext.models.OnNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

class ClimbDetectorTest {

    /** Profile every 25 m from an elevation function. */
    private fun profile(lengthM: Double, ele: (Double) -> Double): List<Pair<Double, Double>> =
        (0..(lengthM / 25).toInt()).map { i: Int -> val d: Double = i * 25.0; d to ele(d) }

    @Test
    fun findsAClearClimb() {
        // Flat at 50 m, then 2–3 km up to 100 m (5 %), then flat.
        val p: List<Pair<Double, Double>> = profile(6000.0) { d: Double ->
            when {
                d < 2000 -> 50.0
                d < 3000 -> 50.0 + (d - 2000) * 0.05
                else -> 100.0
            }
        }
        val climbs: List<ClimbDetector.Climb> = ClimbDetector.detect(p, 0.0, 6000.0)
        assertEquals(1, climbs.size)
        val c: ClimbDetector.Climb = climbs.single()
        assertTrue("start ~2 km, was ${c.start}", abs(c.start - 2000) < 200)
        assertTrue("gain ~50 m, was ${c.gain}", abs(c.gain - 50) < 8)
        assertTrue("grade ~5 %, was ${c.grade}", abs(c.grade - 5) < 1.0)
    }

    @Test
    fun gentleSlopeIsNotAClimb() {
        // 40 m over 4 km = 1 %.
        val p: List<Pair<Double, Double>> = profile(4000.0) { d: Double -> 50.0 + d * 0.01 }
        assertTrue(ClimbDetector.detect(p, 0.0, 4000.0).isEmpty())
    }

    @Test
    fun smallDipKeepsOneClimb() {
        // Up 60 m, down 6 m, up 50 m again: one climb.
        val p: List<Pair<Double, Double>> = profile(5000.0) { d: Double ->
            when {
                d < 1000 -> 40.0
                d < 2200 -> 40.0 + (d - 1000) * 0.05      // to 100
                d < 2320 -> 100.0 - (d - 2200) * 0.05     // down to 94
                d < 3320 -> 94.0 + (d - 2320) * 0.05      // up to 144
                else -> 144.0
            }
        }
        val climbs: List<ClimbDetector.Climb> = ClimbDetector.detect(p, 0.0, 5000.0)
        assertEquals(1, climbs.size)
        assertTrue("whole climb ~2.3 km, was ${climbs.single().length}", climbs.single().length > 2000)
    }

    @Test
    fun clearDipSplitsTheClimb() {
        // Like "TFC clubrit" at 43.6–46.4 km: up 78 m, down 15 m, up 27 m. Hammerhead counts two climbs.
        val p: List<Pair<Double, Double>> = profile(5000.0) { d: Double ->
            when {
                d < 1000 -> 49.0
                d < 2800 -> 49.0 + (d - 1000) * 78.0 / 1800   // to 127
                d < 3300 -> 127.0 - (d - 2800) * 0.03         // down to 112
                d < 3800 -> 112.0 + (d - 3300) * 0.054        // up to 139
                else -> 139.0
            }
        }
        val climbs: List<ClimbDetector.Climb> = ClimbDetector.detect(p, 0.0, 5000.0)
        assertEquals("climbs: $climbs", 2, climbs.size)
        assertTrue("second climb starts ~3.3 km, was ${climbs[1].start}", abs(climbs[1].start - 3300) < 200)
    }

    @Test
    fun gentleRunUpIsTrimmed() {
        // 1.5 km at 1 %, then 600 m at 6 %: the climb starts where it gets steep.
        val p: List<Pair<Double, Double>> = profile(4000.0) { d: Double ->
            when {
                d < 1000 -> 50.0
                d < 2500 -> 50.0 + (d - 1000) * 0.01
                d < 3100 -> 65.0 + (d - 2500) * 0.06
                else -> 101.0
            }
        }
        val c: ClimbDetector.Climb = ClimbDetector.detect(p, 0.0, 4000.0).single()
        assertTrue("start ~2.5 km, was ${c.start}", abs(c.start - 2500) < 350)
    }

    @Test
    fun onlyTheRangeAfterTheJoinPointIsUsed() {
        val p: List<Pair<Double, Double>> = profile(8000.0) { d: Double ->
            when {
                d < 1000 -> 50.0
                d < 2000 -> 50.0 + (d - 1000) * 0.05   // climb at 1–2 km
                d < 5000 -> 100.0
                d < 6000 -> 100.0 + (d - 5000) * 0.05  // climb at 5–6 km
                else -> 150.0
            }
        }
        val climbs: List<ClimbDetector.Climb> = ClimbDetector.detect(p, 4000.0, 8000.0)
        assertEquals(1, climbs.size)
        assertTrue(abs(climbs.single().start - 5000) < 200)
    }

    @Test
    fun builderCombinesApproachClimbsWithDetectedRouteClimbs() {
        // Straight 10 km route with a climb at 6–7 km; approach (~3 km) joins at ~4 km.
        // The Karoo lists only one climb, on the approach.
        val origin: LatLng = LatLng(50.0, 3.0)
        val route: List<LatLng> = (0..100).map { LatLng(origin.lat + it * 0.0008983, origin.lng) }
        val total: Double = (1 until route.size).sumOf { Geo.haversine(route[it - 1], route[it]) }
        val prof: List<Pair<Double, Double>> = (0..100).map { i: Int ->
            val d: Double = i * total / 100
            d to (if (d < 6000) 50.0 else if (d < 7000) 50.0 + (d - 6000) * 0.05 else 100.0)
        }
        val join: LatLng = route[40]
        val approach: List<LatLng> = (0..30).map { LatLng(join.lat, join.lng - 0.042 + it * 0.0014) }
        val approachLen: Double = (1 until approach.size).sumOf { Geo.haversine(approach[it - 1], approach[it]) }
        val karooClimbs: List<OnNavigationState.NavigationState.Climb> =
            listOf(OnNavigationState.NavigationState.Climb(1000.0, 500.0, 4.0, 20.0))
        val result: RouteClimbs = RouteClimbsBuilder.build(
            name = "Combined",
            polyline = encode(route.map { it.lat to it.lng }, 5),
            elevationPolyline = encode(prof, 1),
            routeDistance = total,
            climbs = karooClimbs,
            rejoinPolyline = encode(approach.map { it.lat to it.lng }, 5),
            rejoinDistance = approachLen,
        )!!
        assertEquals(2, result.climbs.size)
        assertTrue(result.climbs[0].onApproach)
        val routeClimb: ClimbGeo = result.climbs[1]
        assertTrue(!routeClimb.onApproach)
        assertTrue("route climb starts ~6 km north, was ${Geo.haversine(origin, routeClimb.start)}",
            abs(Geo.haversine(origin, routeClimb.start) - 6000) < 200)
        assertTrue(result.diagnostics.contains("1 detected on the route"))
    }

    private fun encode(points: List<Pair<Double, Double>>, precision: Int): String {
        val factor: Double = Math.pow(10.0, precision.toDouble())
        val sb: StringBuilder = StringBuilder()
        var pa: Long = 0L
        var pb: Long = 0L
        fun enc(v: Long) {
            var x: Long = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x + 63).toInt().toChar())
        }
        for ((a, b) in points) {
            val ia: Long = (a * factor).roundToLong()
            val ib: Long = (b * factor).roundToLong()
            enc(ia - pa)
            enc(ib - pb)
            pa = ia
            pb = ib
        }
        return sb.toString()
    }
}
