package io.github.bertpinoy.climbnamer.extension

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.bertpinoy.climbnamer.ActiveRoute
import io.github.bertpinoy.climbnamer.ClimbRepository
import io.github.bertpinoy.climbnamer.route.ClimbGeo
import io.github.bertpinoy.climbnamer.NamedClimb
import io.github.bertpinoy.climbnamer.Progress
import io.github.bertpinoy.climbnamer.Units
import io.github.bertpinoy.climbnamer.settings.ClimbSettings
import io.github.bertpinoy.climbnamer.settings.SettingsStore
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** What the field shows. */
data class FieldContent(val header: String, val title: String, val detail: String)

/**
 * 1. The "Climb name" data field: name of the current climb (with distance to go),
 * or of the next climb when not climbing (can be switched off in settings).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class ClimbNameDataType(extension: String) : DataTypeImpl(extension, "climb-name") {
    private val glance: GlanceRemoteViews = GlanceRemoteViews()

    /** Streams the current climb number (0 when not on a climb), for completeness. */
    override fun startStream(emitter: Emitter<StreamState>) {
        val job: Job = CoroutineScope(Dispatchers.IO).launch {
            combine(ClimbRepository.route, ClimbRepository.progress) { r, p -> ClimbRepository.currentClimb(r, p) }
                .collect { climb ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to ((climb?.geo?.index ?: -1) + 1).toDouble())),
                        ),
                    )
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val job: Job = CoroutineScope(Dispatchers.IO).launch {
            if (config.preview) {
                render(context, config, emitter, FieldContent("CLIMB 3/3", "Kemmelberg", "1.2 km to go · 3.8%"))
                return@launch
            }
            combine(
                ClimbRepository.route,
                ClimbRepository.progress,
                SettingsStore.flow(context),
                ClimbRepository.units,
            ) { route, progress, settings, units -> content(route, progress, settings, units) }
                .collect { render(context, config, emitter, it) }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun content(route: ActiveRoute?, progress: Progress, settings: ClimbSettings, units: Units): FieldContent {
        if (route == null) return FieldContent("CLIMB", "No climbs", "Load a route with climbs")
        val total: Int = route.climbs.size
        val current: NamedClimb? = ClimbRepository.currentClimb(route, progress)
        val d: Double? = progress.distanceAlongRoute
        if (current != null && d != null) {
            val g: ClimbGeo = current.geo
            return FieldContent(
                "CLIMB ${g.index + 1}/$total",
                current.label,
                "${units.distance(g.endDistance - d)} to go · ${units.grade(g.grade)}",
            )
        }
        if (!settings.fieldShowsNextClimb) return FieldContent("CLIMB", "—", "Not on a climb")
        val next: NamedClimb = ClimbRepository.nextClimb(route, progress)
            ?: return FieldContent("CLIMB", "No more climbs", "")
        val g: ClimbGeo = next.geo
        val inText: String = d?.let { "in ${units.distance(g.startDistance - it)}" } ?: "${g.index + 1}/$total"
        return FieldContent(
            "NEXT CLIMB · $inText",
            next.label,
            units.climbStats(g.length, g.grade, g.totalElevation),
        )
    }

    private suspend fun render(context: Context, config: ViewConfig, emitter: ViewEmitter, c: FieldContent) {
        val density: Float = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val result: androidx.glance.appwidget.RemoteViewsCompositionResult = glance.compose(context, DpSize.Unspecified) { FieldView(c, config, density) }
        emitter.updateView(result.remoteViews)
    }
}

private val Orange: Color = Color(0xFFFF6B1A)

@Composable
private fun FieldView(c: FieldContent, config: ViewConfig, density: Float) {
    val align: Alignment.Horizontal = when (config.alignment) {
        ViewConfig.Alignment.LEFT -> Alignment.Horizontal.Start
        ViewConfig.Alignment.CENTER -> Alignment.Horizontal.CenterHorizontally
        ViewConfig.Alignment.RIGHT -> Alignment.Horizontal.End
    }
    val textAlign: TextAlign = when (config.alignment) {
        ViewConfig.Alignment.LEFT -> TextAlign.Start
        ViewConfig.Alignment.CENTER -> TextAlign.Center
        ViewConfig.Alignment.RIGHT -> TextAlign.End
    }
    val main: androidx.glance.unit.ColorProvider = ColorProvider(day = Color.Black, night = Color.White)
    val muted: androidx.glance.unit.ColorProvider = ColorProvider(day = Color(0xFF555F6B), night = Color(0xFFB0BAC6))
    // Text sizes follow the field's height (not the Karoo's numeric textSize, which is far too
    // large for a name): three lines must fit, with firm limits so a full-width field stays tidy.
    val heightDp: Float = config.viewSize.second / density
    val titleSp: Float = (heightDp * 0.30f).coerceIn(13f, 22f)
    val smallSp: Float = (heightDp * 0.17f).coerceIn(10f, 13f)
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = align,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = c.header,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(day = Orange, night = Orange), fontSize = smallSp.sp, fontWeight = FontWeight.Bold, textAlign = textAlign),
        )
        Text(
            text = c.title,
            maxLines = 1,
            style = TextStyle(color = main, fontSize = titleSp.sp, fontWeight = FontWeight.Bold, textAlign = textAlign),
        )
        if (c.detail.isNotEmpty()) {
            Text(
                text = c.detail,
                maxLines = 1,
                style = TextStyle(color = muted, fontSize = smallSp.sp, textAlign = textAlign),
            )
        }
    }
}
