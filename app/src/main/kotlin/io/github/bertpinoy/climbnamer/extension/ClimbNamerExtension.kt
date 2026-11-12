package io.github.bertpinoy.climbnamer.extension

import io.github.bertpinoy.climbnamer.ActiveRoute
import io.hammerhead.karooext.models.OnLocationChanged
import io.github.bertpinoy.climbnamer.route.Track
import io.github.bertpinoy.climbnamer.route.LatLng
import io.github.bertpinoy.climbnamer.ActivityLog
import io.github.bertpinoy.climbnamer.BuildConfig
import io.github.bertpinoy.climbnamer.ClimbRepository
import io.github.bertpinoy.climbnamer.Progress
import io.github.bertpinoy.climbnamer.R
import io.github.bertpinoy.climbnamer.Units
import io.github.bertpinoy.climbnamer.naming.ClimbNamer
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.naming.Summits
import io.github.bertpinoy.climbnamer.route.RouteClimbsBuilder
import io.github.bertpinoy.climbnamer.settings.AlertTiming
import io.github.bertpinoy.climbnamer.settings.ClimbSettings
import io.github.bertpinoy.climbnamer.settings.SettingsStore
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import io.github.bertpinoy.climbnamer.route.RouteClimbs
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

/** Up to this far along the route (m), a climb list that arrives late still replaces the current one. */
private const val STILL_AT_START: Double = 500.0

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types: List<DataTypeImpl> by lazy { listOf(ClimbNameDataType(extension)) }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        ActivityLog.init(applicationContext)
        ActivityLog.add("ClimbNamer ${BuildConfig.VERSION_NAME} started")
        karoo = KarooSystemService(applicationContext)
        cache = NameCache(applicationContext)
        namer = ClimbNamer(karoo, Summits(applicationContext))
        karoo.connect { connected -> Timber.i("Karoo system connected: $connected") }

        watchRoute()
        watchProgress()
        watchUnits()
        watchAlerts()
        watchSyncNotifications()
    }

    override fun onDestroy() {
        scope.cancel()
        karoo.disconnect()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ route + names

    private fun watchRoute(): Job = scope.launch {
        // The climb list is fixed per route. During a ride the Karoo drops climbs that are behind
        // the rider from its list (and its distances change with it), which renumbered the climbs
        // ("climb 2" became "climb 1") and broke the position. So the climbs are taken once: when a
        // route with climbs is loaded, or when the approach to the route becomes known before the
        // rider has moved along it. A different route (or stopping navigation) starts over.
        var lastKey: String? = null
        var lastHadClimbs: Boolean = false
        var lastHadApproach: Boolean = false
        val navigation: Flow<OnNavigationState> = karoo.consumerFlow<OnNavigationState>().filter { nav ->
            val key: String = navKey(nav.state)
            val hasClimbs: Boolean = climbsOf(nav.state).isNotEmpty()
            val hasApproach: Boolean = (nav.state as? OnNavigationState.NavigationState.NavigatingRoute)?.rejoinPolyline != null
            // Position tracking starts right away (often at 0.0 km while still at home), and the Karoo
            // can send its climb list a little after the route itself. So "not moved yet" means not
            // past the first few hundred metres, not "no position yet".
            val travelled: Double? = ClimbRepository.progress.value.distanceAlongRoute
            val notMovedYet: Boolean = travelled == null || travelled < STILL_AT_START
            val rebuild: Boolean = key != lastKey ||
                (hasClimbs && !lastHadClimbs && notMovedYet) ||
                (hasApproach && !lastHadApproach && notMovedYet)
            if (rebuild) {
                lastKey = key
                lastHadClimbs = hasClimbs
                lastHadApproach = hasApproach
            }
            rebuild
        }
        combine(navigation, SettingsStore.flow(applicationContext)) { nav, settings -> nav to settings }
            .collect { (nav, settings) ->
                val climbs: RouteClimbs? = runCatching { RouteClimbsBuilder.fromNavigation(nav.state) }
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

    /** Identifies the route being navigated (not its climbs, which the Karoo updates while riding). */
    private fun navKey(state: OnNavigationState.NavigationState): String = when (state) {
        is OnNavigationState.NavigationState.NavigatingRoute -> "route:${state.routePolyline.hashCode()}:${state.reversed}"
        is OnNavigationState.NavigationState.NavigatingToDestination -> "dest:${state.destination.lat},${state.destination.lng}"
        else -> "idle"
    }

    private fun climbsOf(state: OnNavigationState.NavigationState): List<OnNavigationState.NavigationState.Climb> = when (state) {
        is OnNavigationState.NavigationState.NavigatingRoute -> state.climbs
        is OnNavigationState.NavigationState.NavigatingToDestination -> state.climbs
        else -> emptyList()
    }

    /**
     * The rider's position along the navigated path, from GPS matched to ClimbNamer's own copy of
     * the path (approach + route). Independent of the Karoo's distances, which change as climbs
     * drop off its list. Off the path (detour), the last position is kept.
     */
    private fun watchProgress(): Job = scope.launch {
        combine(karoo.consumerFlow<OnLocationChanged>(), ClimbRepository.route) { location, route -> location to route }
            .collect { (location: OnLocationChanged, route: ActiveRoute?) ->
                val track: Track = route?.track ?: return@collect
                val previous: Double? = ClimbRepository.progress.value.distanceAlongRoute
                val d: Double = track.locate(LatLng(location.lat, location.lng), previous) ?: return@collect
                if (previous == null) ActivityLog.add(String.format(java.util.Locale.US, "Position found on the route at %.1f km", d / 1000))
                ClimbRepository.setProgress(d)
            }
    }

    private fun watchUnits(): Job = scope.launch {
        karoo.consumerFlow<UserProfile>().collect { p ->
            ClimbRepository.units.value = Units(
                imperialDistance = p.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL,
                imperialElevation = p.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.IMPERIAL,
            )
        }
    }

    // ------------------------------------------------------------------ 2. alerts

    private fun watchAlerts(): Job = scope.launch {
        val shown: MutableSet<String> = mutableSetOf<String>()
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
        val d: Double = progress.distanceAlongRoute ?: return
        val units: Units = ClimbRepository.units.value
        for (climb in route.climbs) {
            val g: ClimbGeo = climb.geo
            val due: Boolean = when (settings.alertTiming) {
                AlertTiming.AT_START -> d >= g.startDistance && d <= g.startDistance + min(300.0, g.length)
                AlertTiming.BEFORE_START -> d >= g.startDistance - settings.alertBeforeMeters && d < g.startDistance
            }
            if (!due) continue
            val id: String = "${route.key}:${g.index}:${settings.alertTiming}"
            if (id in shown) continue
            // Wait for the name while still in the window, unless unnamed climbs are wanted.
            if (!climb.hasRealName && !settings.alertUnnamedClimbs) continue
            shown += id
            val stats: String = units.climbStats(g.length, g.grade, g.totalElevation)
            val (title: String, detail: String) = when (settings.alertTiming) {
                AlertTiming.AT_START -> climb.label to stats
                AlertTiming.BEFORE_START ->
                    "Next: ${climb.label}" to "in ${units.distance(g.startDistance - d)} · $stats"
            }
            ActivityLog.add("Alert: $title · $detail")
            karoo.dispatch(
                InRideAlert(
                    id = "climbnamer-${g.index}",
                    icon = R.drawable.ic_climb_alert,
                    title = title,
                    detail = detail,
                    autoDismissMs = settings.alertSeconds * 1000L,
                    backgroundColor = R.color.alert_background,
                    textColor = R.color.alert_text,
                ),
            )
        }
    }

    // ------------------------------------------------------------------ sync notification

    /**
     * One Control Center notification per route showing where looking up names stands; it is
     * updated in place (same id). Only shown when names actually had to be looked up.
     */
    private fun watchSyncNotifications(): Job = scope.launch {
        combine(ClimbRepository.route, SettingsStore.flow(applicationContext)) { route, settings ->
            if (!settings.syncNotifications) null else ClimbRepository.syncStatus(route)?.let { route!!.name to it }
        }
            .distinctUntilChanged()
            .collect { pair ->
                val (routeName: String, status: ClimbRepository.SyncStatus) = pair ?: return@collect
                val plural: (Int) -> String = { n: Int -> if (n == 1) "climb" else "climbs" }
                val (message: String, subText: String, style: SystemNotification.Style) = when (status) {
                    is ClimbRepository.SyncStatus.Running -> Triple(
                        "Naming climbs… ${status.named} of ${status.total}",
                        routeName,
                        SystemNotification.Style.UPDATE,
                    )
                    is ClimbRepository.SyncStatus.WaitingForConnection -> Triple(
                        "Waiting for a connection · ${status.named} of ${status.total} named",
                        "Retrying automatically · $routeName",
                        SystemNotification.Style.UPDATE,
                    )
                    is ClimbRepository.SyncStatus.Done -> Triple(
                        "${status.total} ${plural(status.total)} named",
                        status.names.joinToString(", ").let { if (it.length > 80) it.take(79) + "…" else it },
                        SystemNotification.Style.EVENT,
                    )
                    is ClimbRepository.SyncStatus.Failed -> Triple(
                        "Couldn't name ${status.failed} ${plural(status.failed)}",
                        "Reload the route to try again · $routeName",
                        SystemNotification.Style.ERROR,
                    )
                }
                ActivityLog.add("Control Center: $message")
                karoo.dispatch(
                    SystemNotification(
                        id = "climbnamer-sync",
                        message = message,
                        subText = subText,
                        header = "ClimbNamer",
                        style = style,
                        action = "Open",
                        actionIntent = "io.github.bertpinoy.climbnamer.MAIN",
                    ),
                )
            }
    }

    // ------------------------------------------------------------------ 3. map labels

    override fun startMap(emitter: Emitter<MapEffect>) {
        val job: Job = scope.launch {
            var shownIds: List<String> = emptyList<String>()
            combine(ClimbRepository.route, SettingsStore.flow(applicationContext)) { route, settings -> route to settings }
                .collect { (route, settings) ->
                    val symbols: List<Symbol.POI> = if (route != null && settings.mapLabelsEnabled) {
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
                    val ids: List<String> = symbols.map { it.id }
                    val removed: List<String> = shownIds - ids.toSet()
                    if (removed.isNotEmpty()) emitter.onNext(HideSymbols(removed))
                    if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
                    shownIds = ids
                }
        }
        emitter.setCancellable { job.cancel() }
    }
}
