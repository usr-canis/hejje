package com.canis.hejje.auto

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.canis.hejje.HejjeApp
import com.canis.hejje.R
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Meal
import com.canis.hejje.food.NextStop
import com.canis.hejje.food.stopPurpose
import com.canis.hejje.recording.LiveState
import com.canis.hejje.recording.TraceState
import com.canis.hejje.ui.formatter
import com.canis.hejje.ui.trips.elapsed
import com.canis.hejje.ui.trips.headingLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * The one Android Auto screen: a places app for your own stops. Android Auto's card lists the next
 * saved stop on your road first, then your other saved stops by distance; tapping one starts
 * navigation there. Beside it Hejje draws the compass around the line driven so far, the driving
 * time and the trip odometer. Older Android Auto versions that cannot show app-drawn screens get
 * the stops on Android Auto's own map instead.
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

    // What the drawing shows, eased towards the latest values a little every frame.
    private var turn = 0f
    private var turnKnown = false
    private var odometer = 0f

    private data class Snapshot(
        val live: LiveState? = null,
        val trace: TraceState = TraceState.EMPTY,
        val next: NextStop? = null,
        val settings: SettingsData = SettingsData(),
        val zones: List<ZoneEntity> = emptyList(),
    )

    /** One line of the card: a stop to navigate to ([lat], [lon]), or a note (NaN). */
    private data class StopRow(val title: String, val text: String, val lat: Double = Double.NaN, val lon: Double = Double.NaN) {
        val place: Boolean get() = !lat.isNaN() && !lon.isNaN()
    }

    private data class Card(val recording: Boolean, val rows: List<StopRow>)

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        container.controller.live, container.controller.trace.state, container.food.next,
                        container.settings.data, container.db.zones().observe(),
                    ) { live, trace, next, s, zones -> Snapshot(live, trace, next, s, zones) }
                        .collect {
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

    private fun purposeLabel(purpose: String): String = when (Meal.of(purpose)) {
        Meal.FOOD -> carContext.getString(R.string.food_action_food)
        Meal.COFFEE -> carContext.getString(R.string.food_action_coffee)
        null -> purpose.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun cardFor(s: Snapshot): Card {
        val f = f
        val live = s.live
        val rows = ArrayList<StopRow>()
        // The next stop on this road, as the voice reminder finds it.
        val next = s.next?.takeIf { live != null && !it.lat.isNaN() }
        if (next != null && live != null) {
            val left = (next.aheadM - (live.distanceM - next.atDriveM)).coerceAtLeast(0.0)
            rows += StopRow(next.name, carContext.getString(R.string.car_next_stop, f.distance(left)), next.lat, next.lon)
        }
        // Then the other saved stops you want reminders for, nearest first.
        val hereLat = live?.last?.lat ?: s.settings.parkedLat
        val hereLon = live?.last?.lon ?: s.settings.parkedLon
        val stops = s.zones.mapNotNull { z -> z.stopPurpose()?.takeIf { z.rating >= 0 }?.let { z to it } }
            .filterNot { (z, _) -> next != null && Geo.distance(z.lat, z.lon, next.lat, next.lon) < 1.0 }
            .map { (z, purpose) ->
                val d = if (hereLat != null && hereLon != null) Geo.distance(hereLat, hereLon, z.lat, z.lon) else Double.NaN
                Triple(z, purpose, d)
            }
            .sortedWith(compareBy({ it.third.isNaN() }, { it.third }, { it.first.name }))
        for ((z, purpose, d) in stops.take(MAX_ROWS - rows.size)) {
            val what = purposeLabel(purpose)
            rows += StopRow(z.name, if (d.isNaN()) what else carContext.getString(R.string.car_stop_away, what, f.distance(d)), z.lat, z.lon)
        }
        if (rows.isEmpty()) rows += StopRow(carContext.getString(R.string.car_no_stops), carContext.getString(R.string.car_no_stops_text))
        return Card(recording = live != null, rows = rows)
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
        val strip = ActionStrip.Builder().addAction(action).build()
        val title = carContext.getString(R.string.car_title)
        val withMap = carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_7
        val list = ItemList.Builder().apply {
            c.rows.forEach { r ->
                addItem(
                    Row.Builder().setTitle(r.title).addText(r.text).apply {
                        if (r.place) {
                            setOnClickListener { navigate(r) }
                            // On Android Auto's own map (older versions), each stop gets a marker.
                            if (!withMap) {
                                setMetadata(
                                    Metadata.Builder().setPlace(
                                        Place.Builder(CarLocation.create(r.lat, r.lon)).setMarker(PlaceMarker.Builder().build()).build(),
                                    ).build(),
                                )
                            }
                        }
                    }.build(),
                )
            }
        }.build()
        if (withMap) {
            val content = ListTemplate.Builder()
                .setHeader(Header.Builder().setTitle(title).setStartHeaderAction(Action.APP_ICON).build())
                .setSingleList(list)
                .build()
            return MapWithContentTemplate.Builder().setContentTemplate(content).setActionStrip(strip).build()
        }
        // Older Android Auto: the stops on its own map, no drawing of ours.
        @Suppress("DEPRECATION")
        return PlaceListMapTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setItemList(list)
            .setActionStrip(strip)
            .build()
    }

    /** Hands the stop to the car's navigation app. */
    private fun navigate(r: StopRow) {
        val uri = "geo:${r.lat},${r.lon}?q=${r.lat},${r.lon}(${Uri.encode(r.title)})".toUri()
        runCatching { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri)) }
            .onFailure { CarToast.makeText(carContext, R.string.car_navigate_failed, CarToast.LENGTH_LONG).show() }
    }

    private fun toggle(recording: Boolean) {
        val controller = container.controller
        if (recording) controller.manualStop()
        else if (!controller.tryManualStart()) CarToast.makeText(carContext, R.string.car_start_on_phone, CarToast.LENGTH_LONG).show()
    }

    // ---- The drawn part -----------------------------------------------------------------------

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

    /** Draws a frame whenever something changes, and keeps drawing (about 30 a second) while the compass or odometer moves. */
    private suspend fun drawLoop() {
        while (true) {
            val moving = step()
            draw()
            if (moving) delay(FRAME_MS) else wake.receive()
        }
    }

    /** Eases the compass and odometer towards the latest values; true while still moving. */
    private fun step(): Boolean {
        val live = now.live
        val heading = now.trace.heading
        if (heading != null) {
            if (!turnKnown) { turn = heading; turnKnown = true }
            val delta = ((heading - turn) % 360f + 540f) % 360f - 180f
            turn = if (abs(delta) < 0.3f) turn + delta else turn + delta * 0.18f
        } else turnKnown = false
        val km = (floor((live?.distanceM ?: 0.0) / (if (now.settings.miles) 1609.344 else 1000.0) * 10) / 10).toFloat()
        odometer = if (km < odometer) km else ease(odometer, km, 0.002f)
        return (heading != null && turn != heading) || odometer != km
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
        val locale = Locale.getDefault()
        val frame = CarFrame(
            turn = turn,
            heading = headingLabel(now.trace.heading),
            trace = now.trace,
            here = live?.last,
            odometer = odometer,
            distanceUnit = f.distanceUnit.uppercase(locale),
            elapsed = live?.let { elapsed(System.currentTimeMillis() - it.startedAt) } ?: "–",
            timeLabel = carContext.getString(R.string.car_time).uppercase(locale),
            tripLabel = carContext.getString(R.string.cluster_trip).uppercase(locale),
            dark = carContext.isDarkMode,
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
        /** Android Auto shows up to six rows in a list; five keeps it short. */
        const val MAX_ROWS = 5
    }
}
