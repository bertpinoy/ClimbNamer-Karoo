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
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val s = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(s))
    }

    /** Distance in metres from p to segment a-b (local equirectangular projection). */
    fun distToSegment(p: LatLng, a: LatLng, b: LatLng): Double {
        val k = cos(Math.toRadians(p.lat))
        val ax = (a.lng - p.lng) * k
        val ay = a.lat - p.lat
        val bx = (b.lng - p.lng) * k
        val by = b.lat - p.lat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(ax + t * dx, ay + t * dy) * 111_320.0
    }

    /**
     * Decodes a Google encoded polyline. Returns pairs of (first, second) values:
     * (lat, lng) for routes (precision 5), (distance, elevation) for elevation profiles (precision 1).
     */
    fun decodePolyline(encoded: String, precision: Int): List<Pair<Double, Double>> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<Pair<Double, Double>>()
        var index = 0
        var first = 0L
        var second = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
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
