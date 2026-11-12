package com.canis.hejje.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Unit words and phrases. The app fills these from string resources; the defaults serve tests. */
data class Labels(
    val km: String = "km",
    val mi: String = "mi",
    val kmh: String = "km/h",
    val mph: String = "mph",
    val metres: String = "m",
    val feet: String = "ft",
    val hours: String = "h",
    val minutes: String = "m",
    val seconds: String = "s",
    val justNow: String = "Just now",
    val minutesAgo: String = "%d min ago",
    val hoursAgo: String = "%d h ago",
    val today: String = "Today",
    val yesterday: String = "Yesterday",
)

/** Friendly numbers: "42.6 km", "1 h 24 m", "2 h ago". */
class Formatter(val miles: Boolean = false, val labels: Labels = Labels(), private val locale: Locale = Locale.getDefault()) {
    val distanceUnit: String get() = if (miles) labels.mi else labels.km
    val speedUnit: String get() = if (miles) labels.mph else labels.kmh
    val heightUnit: String get() = if (miles) labels.feet else labels.metres

    fun distanceValue(metres: Double): String {
        val v = metres / if (miles) 1609.344 else 1000.0
        return if (v >= 100) String.format(locale, "%d", v.roundToLong()) else String.format(locale, "%.1f", v)
    }

    fun distance(metres: Double): String = "${distanceValue(metres)} $distanceUnit"

    fun speedValue(ms: Float): String = (ms * if (miles) 2.236936f else 3.6f).roundToInt().toString()

    fun speed(ms: Float): String = "${speedValue(ms)} $speedUnit"

    fun heightValue(metres: Float): String = (if (miles) metres * 3.28084f else metres).roundToInt().toString()

    fun height(metres: Float): String = "${heightValue(metres)} $heightUnit"

    fun duration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "$h ${labels.hours} $m ${labels.minutes}"
            m > 0 -> "$m ${labels.minutes}"
            else -> "$totalSec ${labels.seconds}"
        }
    }

    /** Speed-band label such as "30-60" in the current unit. */
    fun band(index: Int): String {
        val limits = StatsCalculator.BANDS_KMH.map { if (miles) (it / 1.609344 / 5).roundToInt() * 5 else it }
        return when (index) {
            0 -> "0–${limits[0]}"
            limits.size -> "${limits.last()}+"
            else -> "${limits[index - 1]}–${limits[index]}"
        }
    }

    fun relative(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = now - epochMs
        val minutes = diff / 60_000
        return when {
            diff < 60_000 -> labels.justNow
            minutes < 60 -> String.format(locale, labels.minutesAgo, minutes)
            minutes < 12 * 60 -> String.format(locale, labels.hoursAgo, minutes / 60)
            else -> dayLabel(Insights.dayOf(epochMs, zone), Insights.dayOf(now, zone)) + ", " + time(epochMs, zone)
        }
    }

    /** "Today", "Yesterday" or "Mon, 10 Nov" (with the year when it differs). */
    fun dayLabel(day: LocalDate, today: LocalDate): String = when {
        day == today -> labels.today
        day == today.minusDays(1) -> labels.yesterday
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
        else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale))
    }

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", locale))

    fun coordinates(lat: Double, lon: Double): String = String.format(Locale.US, "%.4f, %.4f", lat, lon)
}
