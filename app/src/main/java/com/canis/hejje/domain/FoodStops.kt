package com.canis.hejje.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

enum class Meal(val word: String) {
    FOOD("food"),
    COFFEE("coffee");

    companion object {
        /** Food or coffee, from what a stop is for; null for the user's own purposes. */
        fun of(purpose: String?): Meal? = entries.firstOrNull { it.word.equals(purpose?.trim(), ignoreCase = true) }
    }
}

/** A saved stop: [purpose] is food, coffee or the user's own word. [liked] false: don't remind. */
data class FoodPlace(val id: Long, val name: String, val purpose: String, val lat: Double, val lon: Double, val liked: Boolean = true) {
    val meal: Meal? get() = Meal.of(purpose)
}

/** A time Hejje asked "What did you stop for?" and what came of it. */
data class StopAsk(val at: Long, val lat: Double, val lon: Double, val dismissed: Boolean)

/**
 * The rules for food and coffee stops, kept small so they never get in the way:
 * ask after 8 minutes parked (not at your own places, not twice at a spot, at most 3 a day), and
 * while driving say when a place you liked is about 5 minutes ahead, only at its time of day and
 * only if you have not had food (or coffee) yet in that time.
 */
object FoodStops {
    const val ASK_AFTER_MS = 8 * 60_000L
    const val FEEDBACK_AFTER_MS = 30 * 60_000L
    const val SPOT_M = 150.0
    private const val SAME_SPOT_MS = 2 * 3_600_000L
    private const val MAX_ASKS_A_DAY = 3
    private const val AHEAD_S = 300f
    private const val MIN_LEAD_M = 1_500f
    private const val MAX_LEAD_M = 10_000f
    private const val TOO_CLOSE_M = 600.0

    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)
    private val FOOD_TIMES = listOf(t(7) to t(10, 30), t(12) to t(15), t(19) to t(22, 30))
    private val COFFEE_TIMES = listOf(t(6, 30) to t(11, 30), t(15) to t(18, 30))

    /** When the current breakfast, lunch, dinner (or coffee) time began; null outside them. */
    fun windowStart(meal: Meal, now: Long, zone: ZoneId): Long? {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val window = (if (meal == Meal.FOOD) FOOD_TIMES else COFFEE_TIMES).firstOrNull { (from, to) ->
            !local.toLocalTime().isBefore(from) && local.toLocalTime().isBefore(to)
        } ?: return null
        return local.with(window.first).toInstant().toEpochMilli()
    }

    /** It is time for [meal] and you have not had it yet in this time ([lastHad]: when you last did). */
    fun wanted(meal: Meal, now: Long, lastHad: Long, zone: ZoneId): Boolean {
        val start = windowStart(meal, now, zone) ?: return false
        return lastHad < start
    }

    /** Whether to ask about a stop parked at ([lat], [lon]). */
    fun shouldAsk(lat: Double, lon: Double, now: Long, places: List<NamedPlace>, asks: List<StopAsk>, zone: ZoneId): Boolean {
        if (Places.placeAt(lat, lon, places) != null) return false
        val near = asks.filter { Geo.distance(lat, lon, it.lat, it.lon) <= SPOT_M }
        if (near.any { it.dismissed || now - it.at < SAME_SPOT_MS }) return false
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return asks.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today } < MAX_ASKS_A_DAY
    }

    /** How far ahead a place should be announced: about 5 minutes at the current speed. */
    fun lead(speed: Float): Float = (speed * AHEAD_S).coerceIn(MIN_LEAD_M, MAX_LEAD_M)

    /**
     * The liked place to announce now, with its distance along the road, or null. A place counts as
     * ahead only if one of your own past trips went from here to it (so it is on this road, in this
     * direction), and it is within [lead] but not already too close to stop.
     */
    fun ahead(lat: Double, lon: Double, speed: Float, places: List<FoodPlace>, tracks: List<TripTrack>): Pair<FoodPlace, Double>? {
        val reach = lead(speed)
        return places.asSequence()
            .filter { it.liked && Geo.distance(lat, lon, it.lat, it.lon) <= reach }
            .mapNotNull { p ->
                val along = Stretches.find(lat, lon, p.lat, p.lon, tracks).minOfOrNull { it.distanceM } ?: return@mapNotNull null
                if (along < TOO_CLOSE_M || along > reach) null else p to along
            }
            .minByOrNull { it.second }
    }

    /** "Fuel" or "Fuel, Shell" typed under Other: what the stop was for, and the place's name. */
    fun splitOther(text: String): Pair<String, String> {
        val comma = text.indexOf(',')
        val what = (if (comma < 0) text else text.substring(0, comma)).trim().ifEmpty { "Stop" }
        return what to (if (comma < 0) "" else text.substring(comma + 1).trim())
    }

    /** "Kamat Upachar, 6 kilometres ahead, for food." */
    fun announcement(name: String?, purpose: String, distanceM: Double, miles: Boolean): String {
        val value = distanceM / if (miles) 1609.344 else 1000.0
        val rounded = if (value < 2) (value * 2).roundToInt() / 2.0 else value.roundToInt().toDouble()
        val number = if (rounded % 1.0 == 0.0) rounded.toInt().toString() else String.format(Locale.ENGLISH, "%.1f", rounded)
        val unit = if (miles) (if (rounded == 1.0) "mile" else "miles") else (if (rounded == 1.0) "kilometre" else "kilometres")
        val what = Meal.of(purpose)?.word ?: purpose.trim()
        return if (name.isNullOrBlank()) "${what.replaceFirstChar { it.uppercase() }} stop, $number $unit ahead."
        else "$name, $number $unit ahead, for $what."
    }
}
