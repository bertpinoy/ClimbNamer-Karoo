package io.github.bertpinoy.climbnamer.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bertpinoy.climbnamer.ActivityLog
import io.github.bertpinoy.climbnamer.ClimbRepository
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.Units
import io.github.bertpinoy.climbnamer.ActiveRoute
import android.content.Context
import io.github.bertpinoy.climbnamer.naming.NameCache
import io.github.bertpinoy.climbnamer.settings.AlertTiming
import io.github.bertpinoy.climbnamer.settings.ClimbSettings
import io.github.bertpinoy.climbnamer.settings.SettingsStore

private val Orange: Color = Color(0xFFFF6B1A)
private val Navy: Color = Color(0xFF0C1220)
private val Slate: Color = Color(0xFF1E2940)
private val Mist: Color = Color(0xFF94A3B8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Orange, background = Navy, surface = Slate)) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    val context: Context = LocalContext.current
    val settings: ClimbSettings by remember { SettingsStore.flow(context) }.collectAsState(initial = SettingsStore.get(context))
    val route: ActiveRoute? by ClimbRepository.route.collectAsState()
    val units: Units by ClimbRepository.units.collectAsState()
    remember { ActivityLog.init(context) }
    val activity: List<String> by ActivityLog.entries.collectAsState()
    val cache: NameCache = remember { NameCache(context) }
    var cacheSize: Int by remember { mutableIntStateOf(cache.size) }

    fun update(change: (ClimbSettings) -> ClimbSettings): Unit = SettingsStore.update(context, change)

    Column(
        Modifier
            .fillMaxSize()
            .background(Navy)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("Climb")
                withStyle(SpanStyle(color = Orange)) { append("Namer") }
            },
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text("Names for the climbs on your Karoo", color = Mist, fontSize = 13.sp)

        Section("Show names")
        Info(
            "Data field",
            "Add the \"Climb name\" field (ClimbNamer) to a ride page via the ride profiles. " +
                "It shows the current climb, or the next one.",
        )
        Toggle("Data field: show the next climb when not climbing", settings.fieldShowsNextClimb) { v ->
            update { it.copy(fieldShowsNextClimb = v) }
        }
        Toggle("Alert with the climb name", settings.alertsEnabled) { v -> update { it.copy(alertsEnabled = v) } }
        if (settings.alertsEnabled) {
            Choices(
                "When",
                listOf("At the start" to AlertTiming.AT_START, "Before the start" to AlertTiming.BEFORE_START),
                settings.alertTiming,
            ) { v -> update { it.copy(alertTiming = v) } }
            if (settings.alertTiming == AlertTiming.BEFORE_START) {
                Choices("How far before", listOf("250 m" to 250, "500 m" to 500, "1 km" to 1000), settings.alertBeforeMeters) { v ->
                    update { it.copy(alertBeforeMeters = v) }
                }
            }
            Choices("Show for", listOf("5 s" to 5, "8 s" to 8, "15 s" to 15), settings.alertSeconds) { v ->
                update { it.copy(alertSeconds = v) }
            }
            Toggle("Also alert climbs without a name", settings.alertUnnamedClimbs) { v ->
                update { it.copy(alertUnnamedClimbs = v) }
            }
        }
        Toggle("Names on the map (summit labels)", settings.mapLabelsEnabled) { v -> update { it.copy(mapLabelsEnabled = v) } }

        Section("Looking up names")
        Toggle("Look up names when a route is loaded", settings.lookupOnRouteLoad) { v ->
            update { it.copy(lookupOnRouteLoad = v) }
        }
        Text(
            "Best before the ride, on Wi-Fi or with the companion app connected. Names are stored on the Karoo, so each climb is looked up only once and works offline afterwards.",
            color = Mist,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Toggle("Use Nominatim (fastest; Overpass is the backup)", settings.nominatimFallback) { v ->
            update { it.copy(nominatimFallback = v) }
        }
        Toggle("Show progress in the Control Center", settings.syncNotifications) { v ->
            update { it.copy(syncNotifications = v) }
        }

        Section("Current route")
        val r: ActiveRoute? = route
        if (r == null) {
            Text("No route with climbs loaded.", color = Mist, fontSize = 13.sp)
        } else {
            Text("${r.name} · ${r.climbs.size} climbs", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (r.diagnostics.isNotEmpty()) Text(r.diagnostics, color = Mist, fontSize = 10.sp)
            r.climbs.forEach { c ->
                val status: String = when {
                    c.name != null -> c.name.detail.ifEmpty { c.name.source }
                    c.lookingUp -> "looking up…"
                    c.lookupFailed -> "not named yet · retrying automatically"
                    else -> "looking up…"
                }
                val g: ClimbGeo = c.geo
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("${g.index + 1}. ${c.label}", color = Color.White, fontSize = 13.sp)
                    Text(
                        "at ${units.distance(g.startDistance)} · ${units.climbStats(g.length, g.grade, g.totalElevation)}",
                        color = Orange,
                        fontSize = 11.sp,
                    )
                    Text(if (g.onApproach) "on the way to the start · $status" else status, color = Mist, fontSize = 11.sp)
                    c.lastError?.takeIf { c.name == null }?.let { Text(it, color = Mist, fontSize = 10.sp) }
                }
            }
            if (r.climbs.any { it.name == null }) {
                OutlinedButton(
                    onClick = { ClimbRepository.retryNow() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text("Retry now") }
            }
        }

        Section("Stored names")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$cacheSize climbs stored on this Karoo", color = Mist, fontSize = 13.sp, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    cache.clear()
                    cacheSize = 0
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
            ) { Text("Clear") }
        }

        Section("Activity")
        Text(
            "What ClimbNamer did and when, newest first. Useful to see what happened during a ride.",
            color = Mist,
            fontSize = 12.sp,
        )
        if (activity.isEmpty()) {
            Text("Nothing yet.", color = Mist, fontSize = 12.sp)
        } else {
            activity.forEach { line: String -> Text(line, color = Color.White, fontSize = 11.sp) }
            OutlinedButton(
                onClick = { ActivityLog.clear() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange),
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Clear activity") }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Slate)
        Text(
            "ClimbNamer is an independent project, not affiliated with Hammerhead or SRAM. " +
                "Map data © OpenStreetMap contributors (ODbL).",
            color = Mist,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title.uppercase(), color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Info(title: String, text: String) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp)
        Text(text, color = Mist, fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Orange, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun <T> Choices(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(start = 12.dp, bottom = 4.dp)) {
        Text(label, color = Mist, fontSize = 12.sp)
        // Options share the width evenly, so none gets squeezed off the narrow Karoo screen.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, value) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Orange,
                        selectedLabelColor = Color.White,
                    ),
                )
            }
        }
    }
}
