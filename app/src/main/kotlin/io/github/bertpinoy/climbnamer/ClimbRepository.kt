package io.github.bertpinoy.climbnamer

import io.github.bertpinoy.climbnamer.naming.ClimbName
import io.github.bertpinoy.climbnamer.naming.ClimbNamer
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.route.RouteClimbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** The failed lookup will be tried again automatically. */
    val retrying: Boolean = false,
    /** A lookup for this climb is running right now. */
    val lookingUp: Boolean = false,
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
    /** Length of the approach to the route (m); 0 when navigation started on the route. */
    val approachLength: Double = 0.0,
    val diagnostics: String = "",
    /** Some names were not stored yet when the route was loaded, so they had to be looked up. */
    val neededLookup: Boolean = false,
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
    private val _route: MutableStateFlow<ActiveRoute?> = MutableStateFlow<ActiveRoute?>(null)
    val route: StateFlow<ActiveRoute?> = _route.asStateFlow()

    private val _progress: MutableStateFlow<Progress> = MutableStateFlow(Progress(null))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Rider's preferred units (from the Karoo user profile). */
    val units: MutableStateFlow<Units> = MutableStateFlow(Units())

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
            // Same route again (e.g. a reroute event or a settings change): resume missing
            // lookups, or stop them (including pending retries) when lookups were switched off.
            if (lookup) {
                startLookups(cache, namer, scope, allowNominatim)
            } else {
                lookupJob?.cancel()
                _route.update { r -> r?.copy(climbs = r.climbs.map { it.copy(lookingUp = false, retrying = false) }) }
            }
            return
        }
        lookupJob?.cancel()
        _route.value = ActiveRoute(
            key = climbs.key,
            name = climbs.name,
            routeDistance = climbs.routeDistance,
            climbs = climbs.climbs.map { NamedClimb(it, cache.get(it)) },
            approachLength = climbs.approachLength,
            diagnostics = climbs.diagnostics,
            neededLookup = climbs.climbs.any { cache.get(it) == null },
        )
        Timber.i("Route '${climbs.name}' with ${climbs.climbs.size} climbs")
        if (lookup) startLookups(cache, namer, scope, allowNominatim)
    }

    /**
     * Retry schedule (minutes) for climbs whose lookup failed, e.g. without Wi-Fi or a connected
     * companion app. The Karoo SDK has no "connection available" event, so retries are timed;
     * after the last one (about an hour in total) ClimbNamer stops until the route is reloaded.
     */
    private val retryMinutes: List<Long> = listOf(1L, 2L, 5L, 10L, 15L, 30L)

    /** Looks up names that are not cached yet, one climb at a time, retrying failures later. */
    fun startLookups(cache: NameCache, namer: ClimbNamer, scope: CoroutineScope, allowNominatim: Boolean) {
        if (lookupJob?.isActive == true) return
        val routeKey: String = _route.value?.key ?: return
        lookupJob = scope.launch {
            var attempt: Int = 0
            while (true) {
                val todo: List<NamedClimb> = _route.value?.takeIf { it.key == routeKey }?.climbs?.filter { it.name == null } ?: return@launch
                if (todo.isEmpty()) return@launch
                val retryLeft: Boolean = attempt < retryMinutes.size
                for (climb in todo) {
                    updateClimb(routeKey, climb.geo.index) { it.copy(lookupFailed = false, retrying = false, lookingUp = true) }
                    val name: ClimbName? = namer.name(climb.geo, allowNominatim)
                    if (name != null) cache.put(climb.geo, name)
                    updateClimb(routeKey, climb.geo.index) {
                        it.copy(name = name, lookupFailed = name == null, retrying = name == null && retryLeft, lookingUp = false)
                    }
                }
                val stillMissing: Boolean = _route.value?.takeIf { it.key == routeKey }?.climbs?.any { it.name == null } ?: false
                if (!stillMissing || !retryLeft) return@launch
                val wait: Long = retryMinutes[attempt++]
                Timber.i("Name lookup failed for some climbs; retrying in $wait min")
                delay(wait * 60_000)
            }
        }
    }

    private fun updateClimb(routeKey: String, index: Int, change: (NamedClimb) -> NamedClimb) {
        _route.update { r ->
            if (r?.key != routeKey) r else r.copy(climbs = r.climbs.map { if (it.geo.index == index) change(it) else it })
        }
    }

    /** Where looking up names for the active route stands, for the Control Center notification. */
    sealed class SyncStatus {
        abstract val named: Int
        abstract val total: Int
        data class Running(override val named: Int, override val total: Int) : SyncStatus()
        data class WaitingForConnection(override val named: Int, override val total: Int) : SyncStatus()
        data class Done(override val named: Int, override val total: Int, val names: List<String>) : SyncStatus()
        data class Failed(override val named: Int, override val total: Int, val failed: Int) : SyncStatus()
    }

    /** Null when there's nothing to report (no route, or no lookups were needed or allowed). */
    fun syncStatus(route: ActiveRoute?): SyncStatus? {
        if (route == null || !route.neededLookup) return null
        val total: Int = route.climbs.size
        val named: Int = route.climbs.count { it.name != null }
        return when {
            route.climbs.any { it.lookingUp } -> SyncStatus.Running(named, total)
            route.climbs.any { it.lookupFailed && it.retrying } -> SyncStatus.WaitingForConnection(named, total)
            named == total -> SyncStatus.Done(named, total, route.climbs.map { it.label })
            route.climbs.any { it.lookupFailed } -> SyncStatus.Failed(named, total, route.climbs.count { it.lookupFailed })
            else -> null
        }
    }

    fun setProgress(distanceAlongRoute: Double?) {
        _progress.value = Progress(distanceAlongRoute)
    }

    /** The climb the rider is on, if any. */
    fun currentClimb(route: ActiveRoute?, progress: Progress): NamedClimb? {
        val d: Double = progress.distanceAlongRoute ?: return null
        return route?.climbs?.firstOrNull { d >= it.geo.startDistance && d <= it.geo.endDistance }
    }

    /** The next climb ahead of the rider, if any. */
    fun nextClimb(route: ActiveRoute?, progress: Progress): NamedClimb? {
        val d: Double = progress.distanceAlongRoute ?: return route?.climbs?.firstOrNull()
        return route?.climbs?.firstOrNull { it.geo.startDistance > d }
    }
}
