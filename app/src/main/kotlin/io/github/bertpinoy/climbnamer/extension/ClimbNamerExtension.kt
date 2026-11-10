package io.github.bertpinoy.climbnamer.extension

import io.github.bertpinoy.climbnamer.ActiveRoute
import io.github.bertpinoy.climbnamer.BuildConfig
import io.github.bertpinoy.climbnamer.ClimbRepository
import io.github.bertpinoy.climbnamer.Progress
import io.github.bertpinoy.climbnamer.R
import io.github.bertpinoy.climbnamer.Units
import io.github.bertpinoy.climbnamer.naming.ClimbNamer
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.route.RouteClimbsBuilder
import io.github.bertpinoy.climbnamer.settings.AlertTiming
import io.github.bertpinoy.climbnamer.settings.ClimbSettings
import io.github.bertpinoy.climbnamer.settings.SettingsStore
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

/**
 * The ClimbNamer Karoo extension:
 *  - reads the climbs of the route being navigated and looks up their names (cached);
 *  - 1. provides the "Climb name" data field ([ClimbNameDataType]);
 *  - 2. shows an in-ride alert with the name when a climb starts (or just before);
 *  - 3. shows the names as summit labels on the map.
 * Each of these can be switched on or off in the app's settings.
 */
class ClimbNamerExtension : KarooExtension("climbnamer", BuildConfig.VERSION_NAME) {
    private lateinit var karoo: KarooSystemService
    private lateinit var cache: NameCache
    private lateinit var namer: ClimbNamer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types by lazy { listOf(ClimbNameDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        karoo = KarooSystemService(applicationContext)
        cache = NameCache(applicationContext)
        namer = ClimbNamer(karoo)
        karoo.connect { connected -> Timber.i("Karoo system connected: $connected") }

        watchRoute()
        watchProgress()
        watchUnits()
        watchAlerts()
    }

    override fun onDestroy() {
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ route + names

    private fun watchRoute() = scope.launch {
        // Rebuild when the route or its climbs change, or when the approach to the route
        // (rejoin path) becomes known for the same climbs. Not on every rejoin update, and not
        // when the approach disappears after joining the route: the climb distances still count
        // from where navigation started.
        var lastKey: String? = null
        var lastHadApproach = false
        val navigation = karoo.consumerFlow<OnNavigationState>().filter { nav ->
            val key = navKey(nav.state)
            val hasApproach = (nav.state as? OnNavigationState.NavigationState.NavigatingRoute)?.rejoinPolyline != null
            val rebuild = key != lastKey || (hasApproach && !lastHadApproach)
            if (rebuild) {
                lastKey = key
                lastHadApproach = hasApproach
            }
            rebuild
        }
        combine(navigation, SettingsStore.flow(applicationContext)) { nav, settings -> nav to settings }
            .collect { (nav, settings) ->
                val climbs = runCatching { RouteClimbsBuilder.fromNavigation(nav.state) }
                    .onFailure { Timber.w(it, "Could not read climbs from route") }
                    .getOrNull()
                ClimbRepository.setRoute(
                    climbs = climbs,
                    cache = cache,
                    namer = namer,
                    scope = scope,
                    lookup = settings.lookupOnRouteLoad,
                    allowNominatim = settings.nominatimFallback,
                )
            }
    }

    private fun navKey(state: OnNavigationState.NavigationState): String = when (state) {
        is OnNavigationState.NavigationState.NavigatingRoute ->
            "route:${state.routePolyline.hashCode()}:${state.reversed}:${state.climbs.hashCode()}"
        is OnNavigationState.NavigationState.NavigatingToDestination ->
            "dest:${state.polyline.hashCode()}:${state.climbs.hashCode()}"
        else -> "idle"
    }

    /**
     * Distance along the navigated path = path length − distance to destination.
     * The path includes the approach when navigation started away from the route, so the
     * position is also used on the way to the start (off route); otherwise only on route.
     */
    private fun watchProgress() = scope.launch {
        combine(karoo.streamDataFlow(DataType.Type.DISTANCE_TO_DESTINATION), ClimbRepository.route) { state, route ->
            val values = (state as? StreamState.Streaming)?.dataPoint?.values
            val toGo = values?.get(DataType.Field.DISTANCE_TO_DESTINATION)
            val onRoute = values?.get(DataType.Field.ON_ROUTE)?.let { it > 0.5 } ?: true
            val usable = route != null && toGo != null && (onRoute || route.approachLength > 0)
            if (!usable) null else (route!!.routeDistance - toGo!!).coerceAtLeast(0.0)
        }.collect { ClimbRepository.setProgress(it) }
    }

    private fun watchUnits() = scope.launch {
        karoo.consumerFlow<UserProfile>().collect { p ->
            ClimbRepository.units.value = Units(
                imperialDistance = p.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL,
                imperialElevation = p.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.IMPERIAL,
            )
        }
    }

    // ------------------------------------------------------------------ 2. alerts

    private fun watchAlerts() = scope.launch {
        val shown = mutableSetOf<String>()
        combine(
            ClimbRepository.route,
            ClimbRepository.progress,
            SettingsStore.flow(applicationContext),
        ) { route, progress, settings -> Triple(route, progress, settings) }
            .collect { (route, progress, settings) ->
                if (route == null || !settings.alertsEnabled) return@collect
                alertIfDue(route, progress, settings, shown)
            }
    }

    private fun alertIfDue(route: ActiveRoute, progress: Progress, settings: ClimbSettings, shown: MutableSet<String>) {
        val d = progress.distanceAlongRoute ?: return
        val units = ClimbRepository.units.value
        for (climb in route.climbs) {
            val g = climb.geo
            val due = when (settings.alertTiming) {
                AlertTiming.AT_START -> d >= g.startDistance && d <= g.startDistance + min(300.0, g.length)
                AlertTiming.BEFORE_START -> d >= g.startDistance - settings.alertBeforeMeters && d < g.startDistance
            }
            if (!due) continue
            val id = "${route.key}:${g.index}:${settings.alertTiming}"
            if (id in shown) continue
            // Wait for the name while still in the window, unless unnamed climbs are wanted.
            if (!climb.hasRealName && !settings.alertUnnamedClimbs) continue
            shown += id
            val stats = units.climbStats(g.length, g.grade, g.totalElevation)
            val (title, detail) = when (settings.alertTiming) {
                AlertTiming.AT_START -> climb.label to stats
                AlertTiming.BEFORE_START ->
                    "Next: ${climb.label}" to "in ${units.distance(g.startDistance - d)} · $stats"
            }
            karoo.dispatch(
                InRideAlert(
                    id = "climbnamer-${g.index}",
                    icon = R.drawable.ic_climb,
                    title = title,
                    detail = detail,
                    autoDismissMs = settings.alertSeconds * 1000L,
                    backgroundColor = R.color.alert_background,
                    textColor = R.color.alert_text,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 3. map labels

    override fun startMap(emitter: Emitter<MapEffect>) {
        val job = scope.launch {
            var shownIds = emptyList<String>()
            combine(ClimbRepository.route, SettingsStore.flow(applicationContext)) { route, settings -> route to settings }
                .collect { (route, settings) ->
                    val symbols = if (route != null && settings.mapLabelsEnabled) {
                        route.climbs.filter { it.hasRealName }.map { c ->
                            Symbol.POI(
                                id = "climbnamer-${route.key.hashCode()}-${c.geo.index}",
                                lat = c.geo.top.lat,
                                lng = c.geo.top.lng,
                                type = Symbol.POI.Types.SUMMIT,
                                name = c.label,
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val ids = symbols.map { it.id }
                    val removed = shownIds - ids.toSet()
                    if (removed.isNotEmpty()) emitter.onNext(HideSymbols(removed))
                    if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
                    shownIds = ids
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}
