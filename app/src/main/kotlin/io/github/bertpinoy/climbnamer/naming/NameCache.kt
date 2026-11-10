package io.github.bertpinoy.climbnamer.naming

import android.content.Context
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** A name for a climb and where it came from. */
@Serializable
data class ClimbName(
    val name: String,
    /** Extra context, e.g. "via Lettingstraat / Kemmelbergweg" or the nearest village. */
    val detail: String = "",
    /** summit | road | place | none */
    val source: String,
    val at: Long = System.currentTimeMillis(),
)

/**
 * Names cached on the Karoo, so each climb is looked up only once and names are
 * available offline during the ride. Keyed by rounded start and top (~100 m grid),
 * the same key as the ClimbNamer browser extension.
 */
class NameCache(context: Context) {
    private val file: File = File(context.filesDir, "climb_names.json")
    private val json: Json = Json { ignoreUnknownKeys = true }
    private val lock: Any = Any()
    private var map: MutableMap<String, ClimbName> = load()

    private fun load(): MutableMap<String, ClimbName> = runCatching {
        if (file.exists()) json.decodeFromString<Map<String, ClimbName>>(file.readText()).toMutableMap() else null
    }.getOrNull() ?: mutableMapOf()

    fun get(climb: ClimbGeo): ClimbName? = synchronized(lock) { map[key(climb)] }

    fun put(climb: ClimbGeo, name: ClimbName): Unit = synchronized(lock) {
        map[key(climb)] = name
        runCatching { file.writeText(json.encodeToString(map.toMap())) }
        Unit
    }

    val size: Int get() = synchronized(lock) { map.size }

    fun clear(): Unit = synchronized(lock) {
        map.clear()
        file.delete()
        Unit
    }

    companion object {
        private const val VERSION: Int = 2
        fun key(c: ClimbGeo): String {
            fun r(v: Double): String = String.format(Locale.US, "%.3f", v)
            return "v$VERSION:${r(c.start.lat)},${r(c.start.lng)}>${r(c.top.lat)},${r(c.top.lng)}"
        }
    }
}
