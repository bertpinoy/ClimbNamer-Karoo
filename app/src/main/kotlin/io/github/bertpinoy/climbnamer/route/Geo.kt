package io.github.bertpinoy.climbnamer.route

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class LatLng(val lat: Double, val lng: Double)

object Geo {
    /** Great-circle distance in metres. */
    fun haversine(a: LatLng, b: LatLng): Double {
        val r: Double = 6371000.0
        val dLat: Double = Math.toRadians(b.lat - a.lat)
        val dLng: Double = Math.toRadians(b.lng - a.lng)
        val s: Double = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(s))
    }

    /** Distance in metres from p to segment a-b (local equirectangular projection). */
    fun distToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val k: Double = cos(Math.toRadians(p.lat))
        val ax: Double = (a.lng - p.lng) * k
        val ay: Double = a.lat - p.lat
        val bx: Double = (b.lng - p.lng) * k
        val by: Double = b.lat - p.lat
        val dx: Double = bx - ax
        val dy: Double = by - ay
        val len2: Double = dx * dx + dy * dy
        val t: Double = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(ax + t * dx, ay + t * dy) * 111_320.0
    }

    /**
     * Decodes a Google encoded polyline. Returns pairs of (first, second) values:
     * (lat, lng) for routes (precision 5), (distance, elevation) for elevation profiles (precision 1).
     */
    fun decodePolyline(encoded: String, precision: Int): List<Pair<Double, Double>> {
        val factor: Double = 10.0.pow(precision)
        val out: ArrayList<Pair<Double, Double>> = ArrayList()
        var index: Int = 0
        var first: Long = 0L
        var second: Long = 0L
        while (index < encoded.length) {
            var result: Long = 0L
            var shift: Int = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            first += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0L
            shift = 0
            if (index >= encoded.length) break
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            second += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(Pair(first / factor, second / factor))
        }
        return out
    }
}
