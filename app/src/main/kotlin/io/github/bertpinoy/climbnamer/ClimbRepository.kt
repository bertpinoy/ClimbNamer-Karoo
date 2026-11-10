package io.github.bertpinoy.climbnamer

import io.github.bertpinoy.climbnamer.naming.ClimbName
import io.github.bertpinoy.climbnamer.naming.ClimbNamer
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.route.RouteClimbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/** A climb on the active route with its name (once known). */
data class NamedClimb(
    val geo: ClimbGeo,
    val name: ClimbName? = null,
    val lookupFailed: Boolean = false,
) {
    /** Name to show, or a fallback like "Climb 3". */
    val label: String get() = name?.takeIf { it.source != "none" }?.name ?: "Climb ${geo.index + 1}"
    val hasRealName: Boolean get() = name != null && name.source != "none"
}

data class ActiveRoute(
    val key: String,
    val name: String,
    val routeDistance: Double,
    val climbs: List<NamedClimb>,
    val diagnostics: String = "",
)

/** Where the rider is on the route. */
data class Progress(
    /** Distance along the route (m), or null when unknown / off route. */
    val distanceAlongRoute: Double?,
)

/**
 * In-process state shared by the extension service, the data field and the map layer.
 */
object ClimbRepository {
    private val _route = MutableStateFlow<ActiveRoute?>(null)
    val route: StateFlow<ActiveRoute?> = _route.asStateFlow()

    private val _progress = MutableStateFlow(Progress(null))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Rider's preferred units (from the Karoo user profile). */
    val units = MutableStateFlow(Units())

    private var lookupJob: Job? = null

    /** Called when the Karoo starts navigating a (new) route, or stops. */
    fun setRoute(
        climbs: RouteClimbs?,
        cache: NameCache,
        namer: ClimbNamer,
        scope: CoroutineScope,
        lookup: Boolean,
        allowNominatim: Boolean,
    ) {
        if (climbs == null) {
            lookupJob?.cancel()
            _route.value = null
            _progress.value = Progress(null)
            return
        }
        if (_route.value?.key == climbs.key) {
            // Same route again (e.g. a reroute event): only resume missing lookups.
            if (lookup) startLookups(cache, namer, scope, allowNominatim)
            return
        }
        lookupJob?.cancel()
        _route.value = ActiveRoute(
            key = climbs.key,
            name = climbs.name,
            routeDistance = climbs.routeDistance,
            climbs = climbs.climbs.map { NamedClimb(it, cache.get(it)) },
            diagnostics = climbs.diagnostics,
        )
        Timber.i("Route '${climbs.name}' with ${climbs.climbs.size} climbs")
        if (lookup) startLookups(cache, namer, scope, allowNominatim)
    }

    /** Looks up names that are not cached yet, one climb at a time. */
    fun startLookups(cache: NameCache, namer: ClimbNamer, scope: CoroutineScope, allowNominatim: Boolean) {
        if (lookupJob?.isActive == true) return
        val route = _route.value ?: return
        lookupJob = scope.launch {
            for (climb in route.climbs) {
                if (climb.name != null) continue
                val name = namer.name(climb.geo, allowNominatim)
                if (name != null) cache.put(climb.geo, name)
                _route.update { r ->
                    if (r?.key != route.key) {
                        r
                    } else {
                        r.copy(
                            climbs = r.climbs.map {
                                if (it.geo.index == climb.geo.index) it.copy(name = name, lookupFailed = name == null) else it
                            },
                        )
                    }
                }
            }
        }
    }

    fun setProgress(distanceAlongRoute: Double?) {
        _progress.value = Progress(distanceAlongRoute)
    }

    /** The climb the rider is on, if any. */
    fun currentClimb(route: ActiveRoute?, progress: Progress): NamedClimb? {
        val d = progress.distanceAlongRoute ?: return null
        return route?.climbs?.firstOrNull { d >= it.geo.startDistance && d <= it.geo.endDistance }
    }

    /** The next climb ahead of the rider, if any. */
    fun nextClimb(route: ActiveRoute?, progress: Progress): NamedClimb? {
        val d = progress.distanceAlongRoute ?: return route?.climbs?.firstOrNull()
        return route?.climbs?.firstOrNull { it.geo.startDistance > d }
    }
}
