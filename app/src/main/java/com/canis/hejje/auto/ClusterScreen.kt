package com.canis.hejje.auto

import com.canis.hejje.ui.trips.liveUncertain
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.canis.hejje.HejjeApp
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Insights
import com.canis.hejje.food.NextStop
import com.canis.hejje.recording.LiveState
import com.canis.hejje.recording.TraceState
import com.canis.hejje.ui.formatter
import com.canis.hejje.ui.trips.dialMax
import com.canis.hejje.ui.trips.headingLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The one Android Auto screen. Android Auto draws a small card (next stop, this trip, or this week
 * when not recording) and a Start/Stop button; Hejje draws the cluster in the rest of the screen.
 * Older Android Auto versions that cannot show app-drawn screens get the card as a plain pane.
 */
internal class ClusterScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val container = (carContext.applicationContext as HejjeApp).container
    private val renderer = CarClusterRenderer(carContext)
    private var surface: SurfaceContainer? = null
    private var visible: Rect? = null
    private var now = Snapshot()
    private var card: Card? = null
    private var lastCardAt = 0L
    private var cardJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // What the dials show, eased towards the latest values a little every frame.
    private var needle = 0f
    private var turn = 0f
    private var turnKnown = false
    private var odometer = 0f

    private data class Snapshot(
        val live: LiveState? = null,
        val trace: TraceState = TraceState.EMPTY,
        val next: NextStop? = null,
        val settings: SettingsData = SettingsData(),
        val weekM: Double = 0.0,
        val weekTrips: Int = 0,
    )

    private data class Card(val recording: Boolean, val rows: List<Pair<String, String>>)

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val week = container.trips.trips.map { trips ->
                        val zone = ZoneId.systemDefault()
                        val monday = Insights.weekStart(LocalDate.now(zone))
                        trips.filter { Insights.dayOf(it.trip.startTime, zone) >= monday }.let { w -> w.sumOf { it.trip.distanceM } to w.size }
                    }
                    combine(container.controller.live, container.controller.trace.state, container.food.next, container.settings.data, week) { live, trace, next, s, w ->
                        Snapshot(live, trace, next, s, w.first, w.second)
                    }.collect {
                        now = it
                        wake.trySend(Unit)
                        updateCard()
                    }
                }
                launch { drawLoop() }
            }
        }
    }

    private val f: Formatter get() = carContext.formatter(now.settings.miles)

    private fun cardFor(s: Snapshot): Card {
        val f = f
        val live = s.live ?: return Card(
            recording = false,
            rows = listOf(
                carContext.getString(R.string.this_week) to
                    "${f.distance(s.weekM)} · ${carContext.resources.getQuantityString(R.plurals.trip_count, s.weekTrips, s.weekTrips)}",
                carContext.getString(R.string.car_not_recording) to carContext.getString(
                    if (s.settings.carAddress != null && s.settings.autoRecord) R.string.summary_idle_auto else R.string.summary_idle_setup,
                ),
            ),
        )
        val rows = ArrayList<Pair<String, String>>()
        s.next?.let { next ->
            val left = (next.aheadM - (live.distanceM - next.atDriveM)).coerceAtLeast(0.0)
            rows += next.name to carContext.getString(R.string.car_next_stop, f.distance(left))
        }
        rows += carContext.getString(R.string.car_this_trip) to
            if (live.hasFix) "${f.distance(live.distanceM)} · ${f.duration(System.currentTimeMillis() - live.startedAt)}"
            else carContext.getString(R.string.notif_waiting_gps)
        return Card(recording = true, rows = rows)
    }

    /** Refreshes Android Auto's card when its text changes: at once for start/stop, else at most every few seconds. */
    private fun updateCard() {
        val fresh = cardFor(now)
        val old = card
        if (fresh == old) return
        card = fresh
        val wait = CARD_EVERY_MS - (System.currentTimeMillis() - lastCardAt)
        if (old == null || old.recording != fresh.recording || wait <= 0) {
            cardJob?.cancel()
            lastCardAt = System.currentTimeMillis()
            invalidate()
        } else if (cardJob?.isActive != true) {
            cardJob = lifecycleScope.launch {
                delay(wait)
                lastCardAt = System.currentTimeMillis()
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val c = card ?: cardFor(now).also { card = it }
        val action = Action.Builder()
            .setTitle(carContext.getString(if (c.recording) R.string.action_stop else R.string.car_start))
            .setOnClickListener { toggle(c.recording) }
            .build()
        val title = carContext.getString(R.string.app_name)
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            val list = ItemList.Builder().apply { c.rows.forEach { (t, text) -> addItem(Row.Builder().setTitle(t).addText(text).build()) } }.build()
            val content = ListTemplate.Builder()
                .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.APP_ICON).build())
                .setSingleList(list)
                .build()
            return MapWithContentTemplate.Builder()
                .setContentTemplate(content)
                .setActionStrip(ActionStrip.Builder().addAction(action).build())
                .build()
        }
        // Older Android Auto: the same information as text, without the drawn cluster.
        val pane = Pane.Builder().apply {
            c.rows.forEach { (t, text) -> addRow(Row.Builder().setTitle(t).addText(text).build()) }
            addAction(action)
        }.build()
        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(pane).setTitle(title).setHeaderAction(Action.APP_ICON).build()
    }

    private fun toggle(recording: Boolean) {
        val controller = container.controller
        if (recording) controller.manualStop()
        else if (!controller.tryManualStart()) CarToast.makeText(carContext, R.string.car_start_on_phone, CarToast.LENGTH_LONG).show()
    }

    // ---- The drawn cluster -------------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer
        wake.trySend(Unit)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        wake.trySend(Unit)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    /** Draws a frame whenever something changes, and keeps drawing (about 30 a second) while the dials move. */
    private suspend fun drawLoop() {
        while (true) {
            val moving = step()
            draw()
            if (moving) delay(FRAME_MS) else wake.receive()
        }
    }

    /** Eases the needle, compass and odometer towards the latest values; true while still moving. */
    private fun step(): Boolean {
        val live = now.live
        val perMs = if (now.settings.miles) 2.236936f else 3.6f
        val speed = live?.speed?.takeIf { live.hasFix && !live.gap }
        val target = ((speed ?: 0f) * perMs).coerceIn(0f, dialMax(now.settings.miles).toFloat())
        needle = ease(needle, target, 0.6f)
        val heading = now.trace.heading
        if (heading != null) {
            if (!turnKnown) { turn = heading; turnKnown = true }
            val delta = ((heading - turn) % 360f + 540f) % 360f - 180f
            turn = if (abs(delta) < 0.3f) turn + delta else turn + delta * 0.18f
        } else turnKnown = false
        val km = (floor((live?.distanceM ?: 0.0) / (if (now.settings.miles) 1609.344 else 1000.0) * 10) / 10).toFloat()
        odometer = if (km < odometer) km else ease(odometer, km, 0.002f)
        return needle != target || (heading != null && turn != heading) || odometer != km
    }

    private fun ease(value: Float, target: Float, snap: Float): Float =
        if (abs(target - value) < snap) target else value + (target - value) * 0.18f

    private fun draw() {
        val sc = surface ?: return
        val s = sc.surface?.takeIf { it.isValid } ?: return
        val area = visible ?: Rect(0, 0, sc.width, sc.height)
        if (area.width() <= 0 || area.height() <= 0) return
        val live = now.live
        val f = f
        val frame = CarFrame(
            needle = needle,
            digits = live?.speed?.takeIf { live.hasFix && !live.gap }?.let { f.speedValue(it) } ?: if (live == null) "0" else "–",
            unit = f.speedUnit.uppercase(),
            turn = turn,
            heading = headingLabel(now.trace.heading),
            trace = now.trace,
            here = live?.last,
            odometer = odometer,
            distanceUnit = f.distanceUnit.uppercase(),
            miles = now.settings.miles,
            dark = carContext.isDarkMode,
            uncertain = live != null && liveUncertain(live, System.currentTimeMillis()),
        )
        val canvas = runCatching { s.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            renderer.draw(canvas, sc.width, sc.height, area, sc.dpi / 160f, frame)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val FRAME_MS = 33L
        const val CARD_EVERY_MS = 5_000L
    }
}
