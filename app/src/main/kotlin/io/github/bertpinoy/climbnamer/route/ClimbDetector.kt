package io.github.bertpinoy.climbnamer.route

import kotlin.math.max
import kotlin.math.min

/**
 * Finds climbs in an elevation profile. Used for the route part when navigation starts away from
 * the route: the Karoo then only lists climbs on the way to the route, not on the route itself.
 *
 * Tuned against Hammerhead's own climb detection on real routes (it finds the same climbs, with
 * start, length and grade within about 100–200 m and a few tenths of a percent).
 */
object ClimbDetector {
    data class Climb(val start: Double, val length: Double, val grade: Double, val gain: Double)

    private const val STEP: Double = 50.0          // resample interval (m)
    private const val SMOOTH: Int = 2              // moving average over ±2 samples (±100 m)
    private const val MIN_GAIN: Double = 20.0      // smallest climb: +20 m …
    private const val MIN_LENGTH: Double = 300.0   // … over at least 300 m …
    private const val MIN_GRADE: Double = 3.0      // … at 3 % or more on average
    private const val MAX_DIP: Double = 8.0        // a dip of up to 8 m doesn't end a climb …
    private const val PLATEAU: Double = 800.0      // nor does up to 800 m without a new high point
    private const val RUN_UP_GRADE: Double = 2.5   // a gentler run-up is trimmed off the start

    /** Climbs between [from] and [to] (distances in the profile's units, m), in order. */
    fun detect(profile: List<Pair<Double, Double>>, from: Double, to: Double): List<Climb> {
        val inRange: List<Pair<Double, Double>> = profile.sortedBy { it.first }
        if (inRange.size < 2) return emptyList()
        val start: Double = max(from, inRange.first().first)
        val end: Double = min(to, inRange.last().first)
        if (end - start < MIN_LENGTH) return emptyList()

        // Resample to a fixed interval, then smooth out noise.
        val raw: ArrayList<Pair<Double, Double>> = ArrayList()
        var j: Int = 0
        var d: Double = start
        while (d <= end) {
            while (j < inRange.size - 2 && inRange[j + 1].first < d) j++
            val a: Pair<Double, Double> = inRange[j]
            val b: Pair<Double, Double> = inRange[min(j + 1, inRange.size - 1)]
            val t: Double = if (b.first > a.first) ((d - a.first) / (b.first - a.first)).coerceIn(0.0, 1.0) else 0.0
            raw += d to (a.second + (b.second - a.second) * t)
            d += STEP
        }
        val s: List<Pair<Double, Double>> = raw.indices.map { i: Int ->
            val lo: Int = max(0, i - SMOOTH)
            val hi: Int = min(raw.size - 1, i + SMOOTH)
            raw[i].first to (lo..hi).sumOf { raw[it].second } / (hi - lo + 1)
        }

        fun valid(a: Int, b: Int): Boolean {
            val length: Double = s[b].first - s[a].first
            val gain: Double = s[b].second - s[a].second
            return gain >= MIN_GAIN && length >= MIN_LENGTH && gain / length * 100 >= MIN_GRADE
        }

        val out: ArrayList<Climb> = ArrayList()
        var i: Int = 0
        while (i < s.size - 1) {
            if (s[i + 1].second <= s[i].second) {
                i++
                continue
            }
            // Follow the rise, allowing dips and plateaus, up to the highest point.
            val st: Int = i
            var top: Int = i
            var k: Int = i
            while (k < s.size - 1) {
                k++
                if (s[k].second > s[top].second) top = k
                val drop: Double = s[top].second - s[k].second
                if (drop > MAX_DIP || s[k].first - s[top].first > PLATEAU) break
            }
            // Within that stretch, the valid part with the most height gain (then the longest).
            var bestA: Int = -1
            var bestB: Int = -1
            var bestGain: Double = 0.0
            var bestLength: Double = 0.0
            for (a in st until top) {
                for (b in a + 1..top) {
                    if (!valid(a, b)) continue
                    val gain: Double = s[b].second - s[a].second
                    val length: Double = s[b].first - s[a].first
                    if (bestA < 0 || gain > bestGain + 0.5 || (kotlin.math.abs(gain - bestGain) <= 0.5 && length > bestLength)) {
                        bestA = a
                        bestB = b
                        bestGain = gain
                        bestLength = length
                    }
                }
            }
            if (bestA < 0) {
                i = max(top, i + 1)
                continue
            }
            // Trim a gentle run-up off the start.
            var a: Int = bestA
            while (a < bestB) {
                val e: Int = min(a + 6, bestB)
                val grade: Double = (s[e].second - s[a].second) / (s[e].first - s[a].first) * 100
                if (grade < RUN_UP_GRADE && valid(a + 1, bestB)) a++ else break
            }
            val length: Double = s[bestB].first - s[a].first
            val gain: Double = s[bestB].second - s[a].second
            out += Climb(s[a].first, length, gain / length * 100, gain)
            i = bestB
        }
        return out
    }
}
