package io.github.bertpinoy.climbnamer.naming

import android.content.Context
import io.github.bertpinoy.climbnamer.route.Geo
import io.github.bertpinoy.climbnamer.route.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Named summits and passes in Belgium, the Netherlands and Luxembourg, bundled with the app
 * (assets/summits-benelux.json, from OpenStreetMap, ODbL). Climbs ending at one of them are named
 * instantly, without any connection. Built by tools/make-summits.py in the ClimbNamer repository.
 */
class Summits(private val context: Context) {
    data class Summit(val name: String, val position: LatLng, val isPass: Boolean)

    private val summits: List<Summit> by lazy { load() }

    private fun load(): List<Summit> = runCatching {
        val text: String = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val rows: JsonArray = Json.parseToJsonElement(text).jsonObject["rows"]?.jsonArray ?: JsonArray(emptyList())
        rows.mapNotNull { row ->
            val r: JsonArray = row.jsonArray
            val name: String = r[2].jsonPrimitive.contentOrNull ?: return@mapNotNull null
            Summit(name, LatLng(r[0].jsonPrimitive.double, r[1].jsonPrimitive.double), r[3].jsonPrimitive.int == KIND_PASS)
        }
    }.onFailure { Timber.w(it, "Could not load bundled summits") }.getOrDefault(emptyList())

    /** True when [p] lies in the area the bundled list covers (so an online summit search adds nothing). */
    fun covers(p: LatLng): Boolean = p.lat in 49.4..53.6 && p.lng in 2.5..7.3

    /** A named pass within 350 m or summit within 600 m of [top]; passes first, then the nearest. */
    fun near(top: LatLng): Summit? = summits
        .map { it to Geo.haversine(top, it.position) }
        .filter { (s: Summit, d: Double) -> d <= if (s.isPass) 350.0 else 600.0 }
        .sortedWith(compareBy({ if (it.first.isPass) 0 else 1 }, { it.second }))
        .firstOrNull()?.first

    companion object {
        private const val ASSET: String = "summits-benelux.json"
        private const val KIND_PASS: Int = 1
    }
}
