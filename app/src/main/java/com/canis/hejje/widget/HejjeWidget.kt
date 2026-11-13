package com.canis.hejje.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.canis.hejje.HejjeApp
import com.canis.hejje.MainActivity
import com.canis.hejje.R
import com.canis.hejje.domain.Insights
import com.canis.hejje.ui.formatter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Home-screen widget: the Trips-tab dashboard (this week on the odometer, trips, parked car). Follows light/dark mode. */
class HejjeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as HejjeApp).container
        provideContent {
            val trips by container.trips.trips.collectAsState(initial = emptyList())
            val settings by container.settings.data.collectAsState()
            val f = context.formatter(settings.miles)
            val zone = ZoneId.systemDefault()
            val monday = Insights.weekStart(LocalDate.now(zone))
            val week = trips.filter { Insights.dayOf(it.trip.startTime, zone) >= monday }
            val size = LocalSize.current
            val night = (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val km = week.sumOf { it.trip.distanceM }
            val value = ((km / if (f.miles) 1609.344 else 1000.0).coerceIn(0.0, 9999.9) * 10).roundToInt() / 10f
            val parked = if (settings.parkedLat != null && settings.parkedLon != null) f.relative(settings.parkedAt, System.currentTimeMillis(), zone) else null
            val (wPx, hPx) = DashboardBitmap.pixels(context, size.width.value, size.height.value)
            val bitmap = DashboardBitmap.draw(
                context, wPx, hPx, night, value, (if (f.miles) f.labels.mi else f.labels.km).uppercase(),
                context.getString(R.string.this_week).uppercase(), context.getString(R.string.cluster_trip), week.size, parked,
            )
            Image(
                ImageProvider(bitmap),
                contentDescription = context.getString(R.string.this_week) + ": " + f.distance(km) + ", " +
                    context.resources.getQuantityString(R.plurals.trip_count, week.size, week.size),
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
            )
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

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.schedule(context)
    }
}

/**
 * Redraws the widget every 15 minutes while one is on the home screen, so the parked time keeps
 * counting and the week starts again on Monday even without a new trip.
 */
object WidgetRefresh {
    private const val NAME = "widget-refresh"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val placed = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, HejjeWidgetReceiver::class.java)).isNotEmpty()
        if (placed) {
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build())
        } else {
            wm.cancelUniqueWork(NAME)
        }
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { HejjeWidget().updateAll(applicationContext) }
        return Result.success()
    }
}
