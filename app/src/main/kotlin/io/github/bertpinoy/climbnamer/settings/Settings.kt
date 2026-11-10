package io.github.bertpinoy.climbnamer.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** When the climb alert pops up. */
enum class AlertTiming { AT_START, BEFORE_START }

/** Everything the rider can switch on or off. All defaults can be changed in the app. */
data class ClimbSettings(
    // Looking up names
    /** Look up names as soon as a route (or destination) is loaded. */
    val lookupOnRouteLoad: Boolean = true,
    /** Use Nominatim when Overpass is busy. */
    val nominatimFallback: Boolean = true,
    /** Show the progress of looking up names as a Control Center notification. */
    val syncNotifications: Boolean = true,

    // 1. Data field
    /** Show the next climb in the data field when not on a climb. */
    val fieldShowsNextClimb: Boolean = true,

    // 2. Alerts
    val alertsEnabled: Boolean = true,
    val alertTiming: AlertTiming = AlertTiming.AT_START,
    /** Distance before the start for [AlertTiming.BEFORE_START] (m). */
    val alertBeforeMeters: Int = 500,
    /** How long the alert stays on screen (s). */
    val alertSeconds: Int = 8,
    /** Also alert climbs that have no name (shown as "Climb 3"). */
    val alertUnnamedClimbs: Boolean = false,

    // 3. Map
    val mapLabelsEnabled: Boolean = true,
)

object SettingsStore {
    private const val PREFS = "climbnamer_settings"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): ClimbSettings {
        val p = prefs(context)
        val d = ClimbSettings()
        return ClimbSettings(
            lookupOnRouteLoad = p.getBoolean("lookupOnRouteLoad", d.lookupOnRouteLoad),
            nominatimFallback = p.getBoolean("nominatimFallback", d.nominatimFallback),
            syncNotifications = p.getBoolean("syncNotifications", d.syncNotifications),
            fieldShowsNextClimb = p.getBoolean("fieldShowsNextClimb", d.fieldShowsNextClimb),
            alertsEnabled = p.getBoolean("alertsEnabled", d.alertsEnabled),
            alertTiming = runCatching { AlertTiming.valueOf(p.getString("alertTiming", null) ?: "") }
                .getOrDefault(d.alertTiming),
            alertBeforeMeters = p.getInt("alertBeforeMeters", d.alertBeforeMeters),
            // 30 s was an option in 0.1.0; it's now 5, 8 or 15 s.
            alertSeconds = p.getInt("alertSeconds", d.alertSeconds).coerceAtMost(15),
            alertUnnamedClimbs = p.getBoolean("alertUnnamedClimbs", d.alertUnnamedClimbs),
            mapLabelsEnabled = p.getBoolean("mapLabelsEnabled", d.mapLabelsEnabled),
        )
    }

    fun save(context: Context, s: ClimbSettings) {
        prefs(context).edit()
            .putBoolean("lookupOnRouteLoad", s.lookupOnRouteLoad)
            .putBoolean("nominatimFallback", s.nominatimFallback)
            .putBoolean("syncNotifications", s.syncNotifications)
            .putBoolean("fieldShowsNextClimb", s.fieldShowsNextClimb)
            .putBoolean("alertsEnabled", s.alertsEnabled)
            .putString("alertTiming", s.alertTiming.name)
            .putInt("alertBeforeMeters", s.alertBeforeMeters)
            .putInt("alertSeconds", s.alertSeconds)
            .putBoolean("alertUnnamedClimbs", s.alertUnnamedClimbs)
            .putBoolean("mapLabelsEnabled", s.mapLabelsEnabled)
            .apply()
    }

    fun update(context: Context, change: (ClimbSettings) -> ClimbSettings) = save(context, change(get(context)))

    /** Current settings, then every change. */
    fun flow(context: Context): Flow<ClimbSettings> = callbackFlow {
        val p = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(get(context)) }
        trySend(get(context))
        p.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
