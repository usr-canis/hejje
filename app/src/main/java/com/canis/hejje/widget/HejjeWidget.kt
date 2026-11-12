package com.canis.hejje.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.canis.hejje.HejjeApp
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.domain.Insights
import com.canis.hejje.ui.formatter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Home-screen widget: this week's distance and the last trip. Colours follow light/dark mode. */
class HejjeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as HejjeApp).container
        provideContent {
            val trips by container.trips.trips.collectAsState(initial = emptyList())
            val settings by container.settings.data.collectAsState()
            val f = context.formatter(settings.miles)
            val zone = ZoneId.systemDefault()
            val monday = Insights.weekStart(LocalDate.now(zone))
            val week = trips.filter { Insights.dayOf(it.trip.startTime, zone) >= monday }
            val last = trips.firstOrNull()?.trip
            Content(
                weekDistance = f.distance(week.sumOf { it.trip.distanceM }),
                weekDetail = context.resources.getQuantityString(R.plurals.trip_count, week.size, week.size) +
                    if (week.isNotEmpty()) " · " + f.duration(week.sumOf { it.trip.movingMs }) else "",
                lastTrip = if (settings.widgetLastTrip && last != null) {
                    context.getString(
                        R.string.widget_last_trip,
                        f.relative(last.endTime, System.currentTimeMillis(), zone), f.distance(last.distanceM), f.duration(last.totalMs),
                    )
                } else null,
                label = context.getString(R.string.this_week),
            )
        }
    }

    @Composable
    private fun Content(weekDistance: String, weekDetail: String, lastTrip: String?, label: String) {
        val background = ColorProvider(day = Color(0xFFFAF8F6), night = Color(0xFF1B1E21))
        val primary = ColorProvider(day = Color(0xFF1C1B1A), night = Color(0xFFECE9E6))
        val secondary = ColorProvider(day = Color(0xFF6B6661), night = Color(0xFFA39E99))
        val accent = ColorProvider(day = Color(0xFFB8430A), night = Color(0xFFFF8A4C))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(background).cornerRadius(24.dp).padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = TextStyle(color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Text(weekDistance, style = TextStyle(color = primary, fontSize = 28.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(weekDetail, style = TextStyle(color = secondary, fontSize = 13.sp), maxLines = 1)
            if (lastTrip != null) {
                Spacer(GlanceModifier.height(8.dp))
                Text(lastTrip, style = TextStyle(color = secondary, fontSize = 12.sp), maxLines = 2)
            }
        }
    }

    companion object {
        fun refresh(context: Context) {
            val container = (context.applicationContext as HejjeApp).container
            container.scope.launch { runCatching { HejjeWidget().updateAll(context) } }
        }
    }
}

class HejjeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HejjeWidget()
}
