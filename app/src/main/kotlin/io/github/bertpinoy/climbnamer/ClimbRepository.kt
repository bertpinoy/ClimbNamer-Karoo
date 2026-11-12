package io.github.bertpinoy.climbnamer

import io.github.bertpinoy.climbnamer.naming.ClimbName
import io.github.bertpinoy.climbnamer.naming.ClimbNamer
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.route.RouteClimbs
import io.github.bertpinoy.climbnamer.route.Track
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
    /** Why the last lookup failed, for the app. */
    val lastError: String? = null,
) {
    /** Name to show, or a fallback like "Climb 3". Numbers stay fixed for the whole ride. */
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
    /** The navigated path, to find the rider's position from GPS. */
    val track: Track? = null,
)

/** Where the rider is on the route. */
data class Progress(
    /** Distance along the navigated path (m), or null when not known yet. */
    val distanceAlongRoute: Double?,
)

/**
 * In-process state shared by the extension service, the data field, the map layer and the app.
 */
object ClimbRepository {
    private val _route: MutableStateFlow<ActiveRoute?> = MutableStateFlow(null)
    val route: StateFlow<ActiveRoute?> = _route.asStateFlow()

    private val _progress: MutableStateFlow<Progress> = MutableStateFlow(Progress(null))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** Rider's preferred units (from the Karoo user profile). */
    val units: MutableStateFlow<Units> = MutableStateFlow(Units())

    private var lookupJob: Job? = null

    /** What lookups need, kept so the app can start a retry ("Retry now"). */
    private data class LookupContext(val cache: NameCache, val namer: ClimbNamer, val scope: CoroutineScope, val allowNominatim: Boolean)
    private var lookupContext: LookupContext? = null

    /** Called when the Karoo starts navigating a (new) route, or stops. */
    fun setRoute(
        climbs: RouteClimbs?,
        cache: NameCache,
        namer: ClimbNamer,
        scope: CoroutineScope,
        lookup: Boolean,
        allowNominatim: Boolean,
    ) {
        lookupContext = LookupContext(cache, namer, scope, allowNominatim)
        if (climbs == null) {
            if (_route.value != null) ActivityLog.add("Navigation stopped")
            lookupJob?.cancel()
            _route.value = null
            _progress.value = Progress(null)
            return
        }
        if (_route.value?.key == climbs.key) {
            // Same route again (e.g. a settings change): resume missing lookups, or stop them
            // (including pending retries) when lookups were switched off.
            if (lookup) {
                startLookups()
            } else {
                lookupJob?.cancel()
                _route.update { r: ActiveRoute? -> r?.copy(climbs = r.climbs.map { it.copy(lookingUp = false, retrying = false) }) }
            }
            return
        }
        lookupJob?.cancel()
        val named: List<NamedClimb> = climbs.climbs.map { NamedClimb(it, cache.get(it)) }
        _route.value = ActiveRoute(
            key = climbs.key,
            name = climbs.name,
            routeDistance = climbs.routeDistance,
            climbs = named,
            approachLength = climbs.approachLength,
            diagnostics = climbs.diagnostics,
            neededLookup = named.any { it.name == null },
            track = climbs.track,
        )
        _progress.value = Progress(null)
        val stored: Int = named.count { it.name != null }
        ActivityLog.add("Route '${climbs.name}' loaded: ${named.size} climbs, $stored names already stored")
        if (lookup) startLookups()
    }

    /**
     * Retry schedule for climbs whose lookup failed (no Wi-Fi and no companion app connection, or the
     * services were busy): after 1, 2 and 5 minutes, then every 10 minutes for as long as the route
     * is active. The SDK has no "connection available" event, so retries are timed.
     */
    private val retryMinutes: List<Long> = listOf(1L, 2L, 5L)
    private const val RETRY_EVERY_MINUTES: Long = 10L

    /** Looks up names that are not stored yet, one climb at a time, retrying failures later. */
    fun startLookups() {
        if (lookupJob?.isActive == true) return
        val ctx: LookupContext = lookupContext ?: return
        val routeKey: String = _route.value?.key ?: return
        lookupJob = ctx.scope.launch {
            var attempt: Int = 0
            while (true) {
                val todo: List<NamedClimb> = _route.value?.takeIf { it.key == routeKey }?.climbs?.filter { it.name == null } ?: return@launch
                if (todo.isEmpty()) return@launch
                ActivityLog.add("Looking up ${todo.size} climb name(s)" + if (attempt > 0) " (retry $attempt)" else "")
                for (climb in todo) {
                    updateClimb(routeKey, climb.geo.index) { it.copy(lookupFailed = false, retrying = false, lookingUp = true) }
                    val result: ClimbNamer.Result = ctx.namer.name(climb.geo, ctx.allowNominatim)
                    val name: ClimbName? = result.name
                    if (name != null) {
                        ctx.cache.put(climb.geo, name)
                        ActivityLog.add("Climb ${climb.geo.index + 1} named: ${name.name}")
                    } else {
                        ActivityLog.add("Climb ${climb.geo.index + 1} not named: ${result.error ?: "unknown error"}")
                    }
                    updateClimb(routeKey, climb.geo.index) {
                        it.copy(name = name, lookupFailed = name == null, retrying = name == null, lookingUp = false, lastError = result.error)
                    }
                }
                val stillMissing: Boolean = _route.value?.takeIf { it.key == routeKey }?.climbs?.any { it.name == null } ?: false
                if (!stillMissing) return@launch
                val wait: Long = retryMinutes.getOrNull(attempt) ?: RETRY_EVERY_MINUTES
                attempt++
                ActivityLog.add("Next try in $wait min")
                delay(wait * 60_000)
            }
        }
    }

    /** Called from the app: try missing names again right away. */
    fun retryNow() {
        lookupJob?.cancel()
        lookupJob = null
        ActivityLog.add("Retry requested in the app")
        startLookups()
    }

    private fun updateClimb(routeKey: String, index: Int, change: (NamedClimb) -> NamedClimb) {
        _route.update { r: ActiveRoute? ->
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

    /** The next climb ahead of the rider; the first climb while the position isn't known yet. */
    fun nextClimb(route: ActiveRoute?, progress: Progress): NamedClimb? {
        val d: Double = progress.distanceAlongRoute ?: return route?.climbs?.firstOrNull()
        return route?.climbs?.firstOrNull { it.geo.startDistance > d }
    }

    /** True when the rider has passed the end of the last climb. */
    fun allClimbsDone(route: ActiveRoute?, progress: Progress): Boolean {
        val d: Double = progress.distanceAlongRoute ?: return false
        val last: NamedClimb = route?.climbs?.maxByOrNull { it.geo.endDistance } ?: return false
        return d > last.geo.endDistance
    }
}
