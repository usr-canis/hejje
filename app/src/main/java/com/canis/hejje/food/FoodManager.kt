package com.canis.hejje.food

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.canis.hejje.R
import com.canis.hejje.data.SettingsStore
import com.canis.hejje.data.StopReminders
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.data.db.StopAskEntity
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.data.toPlace
import com.canis.hejje.domain.FoodPlace
import com.canis.hejje.domain.FoodStops
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Meal
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.domain.StopAsk
import com.canis.hejje.domain.TripTrack
import com.canis.hejje.notify.Notifier
import com.canis.hejje.recording.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max

/** What a saved stop is for: food, coffee or the user's own word; null for other places. */
fun ZoneEntity.stopPurpose(): String? = purpose ?: when (icon) {
    FoodManager.ICON_FOOD -> Meal.FOOD.word
    FoodManager.ICON_COFFEE -> Meal.COFFEE.word
    else -> null
}

fun ZoneEntity.meal(): Meal? = Meal.of(stopPurpose())

/** The nearest saved stop ahead: [aheadM] along the road when the drive had covered [atDriveM]. */
data class NextStop(val name: String, val purpose: String, val aheadM: Double, val atDriveM: Double)

/**
 * Food and coffee stops, kept quiet and simple:
 *  - 8 minutes after parking (not at your own places), one question: food or coffee, and the name.
 *  - 30 minutes later, "How was it?" (never while driving; once more that evening at most).
 *  - While driving at meal (or coffee) time, if you have not had it yet, a short spoken heads-up
 *    about 5 minutes before a place you liked.
 */
class FoodManager(
    private val app: Context,
    private val db: HejjeDb,
    private val settings: SettingsStore,
    private val trips: TripRepository,
    private val notifier: Notifier,
    private val live: StateFlow<LiveState?>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val speaker by lazy { Speaker(app) }

    // ---- After parking --------------------------------------------------------------------

    /** A drive ended and the car is parked here: ask in 8 minutes, unless it drives off before. */
    fun onParked(parkedAt: Long) {
        if (settings.current.stopReminders == StopReminders.OFF) return
        FoodAskWorker.schedule(app, parkedAt, (FoodStops.ASK_AFTER_MS - (clock() - parkedAt)).coerceAtLeast(0))
    }

    /** Called by the worker 8 minutes after parking. */
    suspend fun askIfStillParked(parkedAt: Long) {
        val s = settings.current
        if (s.stopReminders == StopReminders.OFF || live.value != null || s.parkedAt != parkedAt) return
        val lat = s.parkedLat ?: return
        val lon = s.parkedLon ?: return
        val zones = db.zones().all()
        // Back at a stop you saved: no question (and at a food or coffee place, that is the meal).
        zones.firstOrNull { it.stopPurpose() != null && Geo.distance(lat, lon, it.lat, it.lon) <= it.radiusM }?.let { z ->
            z.meal()?.let { had(it, parkedAt) }
            return
        }
        val now = clock()
        val asks = (db.stopAsks().since(now - 3 * 86_400_000L) + db.stopAsks().dismissed()).distinctBy { it.id }
            .map { StopAsk(it.at, it.lat, it.lon, it.state == StopAskEntity.DISMISSED) }
        if (!FoodStops.shouldAsk(lat, lon, now, zones.map { it.toPlace() }, asks, zone)) return
        val id = db.stopAsks().insert(StopAskEntity(at = now, parkedAt = parkedAt, lat = lat, lon = lon))
        notifier.foodQuestion(id)
    }

    /**
     * Saves a stop: what it was for ([purpose]: food, coffee or your own word) and the place's
     * name. From the notification, or later from the timeline.
     */
    suspend fun save(purpose: String, name: String, lat: Double, lon: Double, parkedAt: Long, askId: Long? = null): Long {
        val what = purpose.trim().ifEmpty { Meal.FOOD.word }
        val meal = Meal.of(what)
        val label = name.trim().ifEmpty {
            when (meal) {
                Meal.FOOD -> app.getString(R.string.food_default_food)
                Meal.COFFEE -> app.getString(R.string.food_default_coffee)
                null -> what.replaceFirstChar { it.uppercase() }
            }
        }
        val icon = when (meal) {
            Meal.FOOD -> ICON_FOOD
            Meal.COFFEE -> ICON_COFFEE
            null -> ICON_STOP
        }
        val placeId = db.zones().upsert(
            ZoneEntity(name = label, lat = lat, lon = lon, radiusM = PLACE_RADIUS_M, icon = icon, hide = false, purpose = meal?.word ?: what),
        )
        meal?.let { had(it, parkedAt) }
        val ask = askId?.let { db.stopAsks().get(it) } ?: db.stopAsks().since(parkedAt - 60_000).firstOrNull { it.parkedAt == parkedAt }
        if (ask != null) {
            db.stopAsks().update(ask.copy(state = StopAskEntity.SAVED, placeId = placeId))
            notifier.cancelFoodQuestion(ask.id)
            if (askId != null) {
                notifier.foodSaved(ask.id, label)
                FoodFeedbackWorker.schedule(app, ask.id, FoodStops.FEEDBACK_AFTER_MS)
            }
        }
        return placeId
    }

    /** The question was swiped away: not asked at this spot again. */
    suspend fun dismissed(askId: Long) {
        val ask = db.stopAsks().get(askId) ?: return
        if (ask.state == StopAskEntity.ASKED) db.stopAsks().update(ask.copy(state = StopAskEntity.DISMISSED))
    }

    /** Remind next time, or don't; from the notification or the timeline (works with reminders off too). */
    suspend fun setRemind(placeId: Long, remind: Boolean, askId: Long? = null) {
        val place = db.zones().all().firstOrNull { it.id == placeId } ?: return
        db.zones().upsert(place.copy(rating = if (remind) 1 else -1))
        val ask = askId?.let { db.stopAsks().get(it) } ?: return
        db.stopAsks().update(ask.copy(feedback = -1))
        notifier.cancelFoodFeedback(ask.id)
    }

    /** "How was it?" swiped away: no reminder. */
    suspend fun feedbackDismissed(askId: Long) {
        val ask = db.stopAsks().get(askId) ?: return
        db.stopAsks().update(ask.copy(feedback = -1))
    }

    /** "How was it?": not while driving (then after the drive), at most twice, the second time in the evening. */
    suspend fun askFeedback(askId: Long) {
        if (settings.current.stopReminders == StopReminders.OFF) return
        val ask = db.stopAsks().get(askId) ?: return
        if (ask.state != StopAskEntity.SAVED || ask.feedback !in 0..1) return
        val place = db.zones().all().firstOrNull { it.id == ask.placeId } ?: return
        if (place.rating != 0 || live.value != null) return
        notifier.foodFeedback(ask.id, place.name)
        db.stopAsks().update(ask.copy(feedback = ask.feedback + 1))
        if (ask.feedback == 0) eveningDelay(clock())?.let { FoodFeedbackWorker.schedule(app, ask.id, it) }
    }

    /** After a drive: any "How was it?" that came due while driving. */
    suspend fun onTripSaved() {
        val now = clock()
        db.stopAsks().since(now - 86_400_000L)
            .filter { it.state == StopAskEntity.SAVED && it.feedback == 0 && now - it.at >= FoodStops.FEEDBACK_AFTER_MS }
            .forEach { askFeedback(it.id) }
    }

    /** The one reminder: at 20:00, or 2 hours later if it is past 18:00, but never after 22:00. */
    private fun eveningDelay(now: Long): Long? {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val target = if (local.toLocalTime().isBefore(LocalTime.of(18, 0))) local.with(LocalTime.of(20, 0)) else local.plusHours(2)
        if (target.toLocalDate() != local.toLocalDate() || target.toLocalTime().isAfter(LocalTime.of(22, 0))) return null
        return target.toInstant().toEpochMilli() - now
    }

    private suspend fun had(meal: Meal, at: Long) = settings.update {
        if (meal == Meal.FOOD) it.copy(lastFoodAt = max(it.lastFoodAt, at)) else it.copy(lastCoffeeAt = max(it.lastCoffeeAt, at))
    }

    // ---- While driving --------------------------------------------------------------------

    private var tracks: List<TripTrack>? = null
    private var loading = false
    private var driveStart: RawPoint? = null
    private val announced = HashSet<Long>()
    private val approach = HashMap<Long, Double>()
    private var lastCheck = 0L
    private var lastSaid = 0L
    private var lastNext = 0L
    private val _next = MutableStateFlow<NextStop?>(null)
    private val shownForTest = MutableStateFlow<NextStop?>(null)

    /** The nearest saved stop ahead on your road while driving (any time of day), for the live card. */
    val next: StateFlow<NextStop?> = combine(_next, shownForTest) { found, test -> test ?: found }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @VisibleForTesting
    internal fun showNext(stop: NextStop?) {
        shownForTest.value = stop
    }

    /** Follows the recording: cancels a pending question when a drive starts, and gives heads-ups. */
    fun watch() = scope.launch {
        var driving = false
        live.collect { l ->
            if (l != null && !driving) { driving = true; FoodAskWorker.cancel(app) }
            if (l == null) {
                if (driving) endDrive()
                driving = false
                return@collect
            }
            runCatching {
                if (driveStart == null) driveStart = l.last
                nextStop(l)
                headsUp(l)
            }
        }
    }

    private fun endDrive() {
        tracks = null; loading = false; driveStart = null
        announced.clear(); approach.clear(); lastCheck = 0; lastNext = 0
        _next.value = null
        speaker.release()
    }

    private suspend fun headsUp(l: LiveState) {
        val p = l.last ?: return
        if (driveStart == null) driveStart = p
        val now = clock()
        if (now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        val s = settings.current
        if (s.stopReminders != StopReminders.VOICE || now - lastSaid < BETWEEN_MS) return
        val meals = Meal.entries.filter { FoodStops.wanted(it, now, if (it == Meal.FOOD) s.lastFoodAt else s.lastCoffeeAt, zone) }
        val start = driveStart ?: return
        val places = db.zones().all().mapNotNull { z ->
            val purpose = z.stopPurpose() ?: return@mapNotNull null
            // Food and coffee only at their times (and not if already had); your own stops any time.
            val meal = Meal.of(purpose)
            if ((meal != null && meal !in meals) || z.id in announced) return@mapNotNull null
            // The place you just set off from is not "ahead".
            if (Geo.distance(z.lat, z.lon, start.lat, start.lon) < LEFT_FROM_M) return@mapNotNull null
            FoodPlace(z.id, z.name, purpose, z.lat, z.lon, liked = z.rating >= 0)
        }
        if (places.isEmpty()) return
        val known = tracksOrLoad() ?: return
        val speed = if (p.speed.isNaN() || p.speed < 1f) DEFAULT_SPEED else p.speed
        val (place, along) = FoodStops.ahead(p.lat, p.lon, speed, places, known) ?: return
        // Only once it is clearly getting closer (two checks in a row).
        val straight = Geo.distance(p.lat, p.lon, place.lat, place.lon)
        val before = approach.put(place.id, straight)
        if (before == null || straight >= before) return
        announced += place.id
        lastSaid = now
        val generic = place.name == app.getString(R.string.food_default_food) || place.name == app.getString(R.string.food_default_coffee) ||
            place.name.equals(place.purpose, ignoreCase = true)
        speaker.say(FoodStops.announcement(if (generic) null else place.name, place.purpose, along, s.miles))
    }

    /** Your past trips, loaded once per drive in the background; null until they are. */
    private fun tracksOrLoad(): List<TripTrack>? = tracks ?: run {
        if (!loading) {
            loading = true
            scope.launch { tracks = runCatching { trips.tracks() }.getOrDefault(emptyList()) }
        }
        null
    }

    /** Same detection as the spoken heads-up (on your own road, this direction), up to 20 km ahead, any time. */
    private suspend fun nextStop(l: LiveState) {
        val p = l.last ?: return
        val now = clock()
        if (now - lastNext < NEXT_EVERY_MS) return
        lastNext = now
        val start = driveStart ?: return
        val places = db.zones().all().mapNotNull { z ->
            val purpose = z.stopPurpose() ?: return@mapNotNull null
            if (z.rating < 0 || Geo.distance(z.lat, z.lon, start.lat, start.lon) < LEFT_FROM_M) return@mapNotNull null
            FoodPlace(z.id, z.name, purpose, z.lat, z.lon)
        }
        if (places.isEmpty()) {
            _next.value = null
            return
        }
        val known = tracksOrLoad() ?: run { lastNext = 0; return }
        val hit = FoodStops.ahead(p.lat, p.lon, DEFAULT_SPEED, places, known, reach = NEXT_REACH_M, minM = NEXT_MIN_M)
        _next.value = hit?.let { (place, along) -> NextStop(place.name, place.purpose, along, l.distanceM) }
    }

    companion object {
        const val ICON_FOOD = "food"
        const val ICON_COFFEE = "coffee"
        const val ICON_STOP = "pin"
        const val PLACE_RADIUS_M = 100.0
        private const val CHECK_EVERY_MS = 30_000L
        private const val BETWEEN_MS = 15 * 60_000L
        private const val LEFT_FROM_M = 2_000.0
        private const val DEFAULT_SPEED = 12f
        private const val NEXT_EVERY_MS = 15_000L
        private const val NEXT_REACH_M = 20_000f
        private const val NEXT_MIN_M = 150.0
    }
}
