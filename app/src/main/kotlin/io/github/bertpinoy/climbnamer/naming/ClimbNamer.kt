package io.github.bertpinoy.climbnamer.naming

import io.github.bertpinoy.climbnamer.BuildConfig
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.route.Geo
import io.github.bertpinoy.climbnamer.route.LatLng
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.URLEncoder
import java.util.Locale

/**
 * Finds a name for a climb using OpenStreetMap, in this order:
 *   1. a named summit or pass near the top (natural=peak/hill/saddle, mountain_pass=yes);
 *   2. the road the climb mostly follows;
 *   3. the nearest village or town ("Climb near …").
 *
 * Overpass API first (one small query per climb); Nominatim as optional fallback.
 * Fair use: mirrors are tried one after another, Nominatim gets at most 1 request/second,
 * and every request identifies the app.
 */
class ClimbNamer(private val karoo: KarooSystemService) {
    private val json: Json = Json { ignoreUnknownKeys = true }
    private val userAgent: String = "ClimbNamer-Karoo/${BuildConfig.VERSION_NAME} (+https://github.com/bertpinoy/ClimbNamer-Karoo)"
    private val headers: Map<String, String> = mapOf("User-Agent" to userAgent, "Accept" to "application/json")

    /** The name, or why it couldn't be found. */
    data class Result(val name: ClimbName?, val error: String?)

    suspend fun name(climb: ClimbGeo, allowNominatim: Boolean): Result {
        val overpassError: String = try {
            return Result(withOverpass(climb), null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Timber.w(e, "Overpass failed for climb ${climb.index}")
            describe(e)
        }
        if (!allowNominatim) return Result(null, "Overpass: $overpassError")
        val nominatimError: String = try {
            return Result(withNominatim(climb), null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Timber.w(e, "Nominatim failed for climb ${climb.index}")
            describe(e)
        }
        return Result(null, "Overpass: $overpassError; Nominatim: $nominatimError")
    }

    private fun describe(e: Exception): String = when (e) {
        is kotlinx.coroutines.TimeoutCancellationException -> "no answer in time (no connection?)"
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------------ Overpass

    private val overpassMirrors: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    private suspend fun overpass(query: String): JsonObject {
        var last: Exception? = null
        for (url in overpassMirrors) {
            // GET with the query in the URL: simpler than POST for the Karoo's companion-app
            // (Bluetooth) connection, and accepted by Overpass for queries of this size.
            val res: HttpResult = karoo.http("GET", url + "?data=" + URLEncoder.encode(query, "UTF-8"), headers)
            if (res.ok && res.body != null) {
                return json.parseToJsonElement(res.body).jsonObject
            }
            last = IllegalStateException("${url.substringAfter("//").substringBefore("/")} ${if (res.status > 0) "HTTP ${res.status}" else ""} ${res.error ?: ""}".trim())
        }
        throw last ?: IllegalStateException("Overpass unavailable")
    }

    private fun bbox(points: List<LatLng>, margin: Double): String {
        val s: Double = points.minOf { it.lat } - margin
        val n: Double = points.maxOf { it.lat } + margin
        val w: Double = points.minOf { it.lng } - margin
        val e: Double = points.maxOf { it.lng } + margin
        return listOf(s, w, n, e).joinToString(",") { String.format(Locale.US, "%.5f", it) }
    }

    private suspend fun withOverpass(climb: ClimbGeo): ClimbName {
        val skip: String = "footway|steps|corridor|elevator|platform|proposed|construction|bus_stop"
        val q: String = """
            [out:json][timeout:25];
            (
              way[highway][name][highway!~"^($skip)${'$'}"](${bbox(climb.points, 0.0008)});
              node[name][natural~"^(peak|hill|saddle)${'$'}"](${bbox(listOf(climb.top), 0.007)});
              node[name][mountain_pass=yes](${bbox(listOf(climb.top), 0.005)});
              node[name][place~"^(village|town|hamlet|suburb|city)${'$'}"](${bbox(listOf(climb.top), 0.04)});
            );
            out tags geom;
        """.trimIndent()
        val elements: JsonArray = overpass(q)["elements"]?.jsonArray ?: JsonArray(emptyList())

        val summits: MutableList<Candidate> = mutableListOf<Candidate>()
        val places: MutableList<Pair<String, Double>> = mutableListOf<Pair<String, Double>>()
        val ways: MutableList<Pair<String, List<LatLng>>> = mutableListOf<Pair<String, List<LatLng>>>()
        for (el in elements) {
            val o: JsonObject = el.jsonObject
            val tags: JsonObject = o["tags"]?.jsonObject ?: continue
            val name: String = tags["name"]?.jsonPrimitive?.contentOrNull ?: continue
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "node" -> {
                    val lat: Double = o["lat"]?.jsonPrimitive?.doubleOrNull ?: continue
                    val lon: Double = o["lon"]?.jsonPrimitive?.doubleOrNull ?: continue
                    val natural: String? = tags["natural"]?.jsonPrimitive?.contentOrNull
                    val pass: Boolean = tags["mountain_pass"]?.jsonPrimitive?.contentOrNull == "yes"
                    if (tags["place"] != null) {
                        places += name to Geo.haversine(climb.top, LatLng(lat, lon))
                    } else if (natural != null || pass) {
                        val kind: Kind = when {
                            pass || natural == "saddle" -> Kind.PASS
                            natural == "hill" -> Kind.HILL
                            else -> Kind.PEAK
                        }
                        summits += Candidate(name, LatLng(lat, lon), kind)
                    }
                }
                "way" -> {
                    val geom: List<LatLng> = o["geometry"]?.jsonArray?.mapNotNull {
                        val p: JsonObject = it.jsonObject
                        val lat: Double? = p["lat"]?.jsonPrimitive?.doubleOrNull
                        val lon: Double? = p["lon"]?.jsonPrimitive?.doubleOrNull
                        if (lat != null && lon != null) LatLng(lat, lon) else null
                    }.orEmpty()
                    if (geom.size >= 2) ways += name to geom
                }
            }
        }

        val summit: Candidate? = pickSummit(climb.top, summits)

        // Roads the climb follows, weighted by the distance covered.
        val tally: LinkedHashMap<String, Double> = linkedMapOf<String, Double>()
        val pts: List<LatLng> = climb.points
        for (i in 1 until pts.size) {
            val mid: LatLng = LatLng((pts[i - 1].lat + pts[i].lat) / 2, (pts[i - 1].lng + pts[i].lng) / 2)
            var best: String? = null
            var bestD: Double = 35.0
            for ((name, g) in ways) {
                for (j in 1 until g.size) {
                    val d: Double = Geo.distToSegment(mid, g[j - 1], g[j])
                    if (d < bestD) {
                        bestD = d
                        best = name
                    }
                }
            }
            if (best != null) tally[best] = (tally[best] ?: 0.0) + Geo.haversine(pts[i - 1], pts[i])
        }
        val total: Double = tally.values.sum().takeIf { it > 0 } ?: 1.0
        val roads: List<String> = tally.entries.filter { it.value / total >= 0.2 }.sortedByDescending { it.value }.map { it.key }
        val place: String = places.minByOrNull { it.second }?.first.orEmpty()

        return compose(summit, roads, place)
    }

    // ------------------------------------------------------------------ Nominatim

    private val nominatimLock: Mutex = Mutex()
    private var nominatimLastAt: Long = 0L

    private suspend fun nominatim(path: String): String = nominatimLock.withLock {
        val wait: Long = nominatimLastAt + 1100 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        nominatimLastAt = System.currentTimeMillis()
        val res: HttpResult = karoo.http("GET", "https://nominatim.openstreetmap.org$path", headers)
        if (!res.ok || res.body == null) throw IllegalStateException("${if (res.status > 0) "HTTP ${res.status}" else ""} ${res.error ?: ""}".trim())
        res.body
    }

    private suspend fun withNominatim(climb: ClimbGeo): ClimbName {
        val top: LatLng = climb.top
        val candidates: MutableList<Candidate> = mutableListOf<Candidate>()
        suspend fun search(phrase: String, radius: Double, kind: Kind) {
            val vb: String = listOf(top.lng - radius, top.lat + radius, top.lng + radius, top.lat - radius)
                .joinToString(",") { String.format(Locale.US, "%.5f", it) }
            val body: String = nominatim("/search?format=jsonv2&q=${URLEncoder.encode(phrase, "UTF-8")}&viewbox=$vb&bounded=1&limit=10")
            json.parseToJsonElement(body).jsonArray.forEach {
                val o: JsonObject = it.jsonObject
                val name: String = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { n -> n.isNotBlank() } ?: return@forEach
                val lat: Double = o["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@forEach
                val lon: Double = o["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@forEach
                candidates += Candidate(name, LatLng(lat, lon), kind)
            }
        }
        if ((climb.topElevation ?: 0.0) >= 400) search("[mountain_pass=yes]", 0.005, Kind.PASS)
        search("[natural=peak]", 0.007, Kind.PEAK)
        val summit: Candidate? = pickSummit(top, candidates)

        val tally: LinkedHashMap<String, Int> = linkedMapOf<String, Int>()
        var place: String = ""
        for (f in listOf(0.3, 0.75)) {
            val p: LatLng = climb.points[((climb.points.size - 1) * f).toInt()]
            val body: String = nominatim(
                String.format(Locale.US, "/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=17&layer=address&addressdetails=1", p.lat, p.lng),
            )
            val o: JsonObject = json.parseToJsonElement(body).jsonObject
            val address: JsonObject? = o["address"]?.jsonObject
            val road: String? = if (o["category"]?.jsonPrimitive?.contentOrNull == "highway") {
                o["name"]?.jsonPrimitive?.contentOrNull
            } else {
                address?.get("road")?.jsonPrimitive?.contentOrNull
            }
            if (!road.isNullOrBlank()) tally[road] = (tally[road] ?: 0) + 1
            if (place.isEmpty() && address != null) {
                place = listOf("village", "town", "hamlet", "suburb", "city", "municipality")
                    .firstNotNullOfOrNull { address[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
            }
        }
        val roads: List<String> = tally.entries.sortedByDescending { it.value }.map { it.key }
        return compose(summit, roads, place)
    }

    // ------------------------------------------------------------------ shared

    private enum class Kind { PASS, PEAK, HILL }

    private data class Candidate(val name: String, val pos: LatLng, val kind: Kind)

    private fun pickSummit(top: LatLng, candidates: List<Candidate>): Candidate? = candidates
        .map { it to Geo.haversine(top, it.pos) }
        .filter { (c, d) -> d <= if (c.kind == Kind.PASS) 350.0 else 600.0 }
        .sortedWith(compareBy({ it.first.kind.ordinal }, { it.second }))
        .firstOrNull()?.first

    private fun compose(summit: Candidate?, roads: List<String>, place: String): ClimbName = when {
        summit != null -> {
            val via: List<String> = roads.filter { it != summit.name }.take(2)
            ClimbName(summit.name, if (via.isNotEmpty()) "via " + via.joinToString(" / ") else place, "summit")
        }
        roads.isNotEmpty() -> ClimbName(
            roads[0],
            listOfNotNull(roads.getOrNull(1)?.let { "then $it" }, place.takeIf { it.isNotEmpty() }).joinToString(" · "),
            "road",
        )
        place.isNotEmpty() -> ClimbName("Climb near $place", "", "place")
        else -> ClimbName("Unnamed climb", "", "none")
    }
}
