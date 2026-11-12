package com.canis.hejje.auto

import com.canis.hejje.domain.Formatter
import com.canis.hejje.recording.LiveState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Words for the car card. Filled from string resources in the app; the defaults serve tests. */
data class CardText(
    val live: String = "%1\$s · %2\$s",
    val recording: String = "Recording · %1\$s",
    val waiting: String = "Waiting for GPS…",
    val idle: String = "Not recording",
    val idleAuto: String = "Starts when your car connects",
    val idleManual: String = "Start a drive on your phone",
    val details: String = "%1\$s · ±%2\$s · %3\$s",
)

/**
 * What the Android Auto card shows. [gauge] drives the progress bar: it is a speed bar that fills
 * from 0 to [GAUGE_MAX_KMH], the same scale the app uses to colour routes.
 */
data class CardContent(
    val title: String,
    val subtitle: String,
    val details: String,
    val recording: Boolean,
    /** 0..1 */
    val gauge: Float,
) {
    val positionMs: Long get() = (gauge * DURATION_MS).roundToLong()

    companion object {
        const val GAUGE_MAX_KMH = 120f
        const val DURATION_MS = 120_000L
    }
}

object DriveCard {
    /** A fix older than this no longer counts as "current speed". */
    private const val STALE_MS = 15_000L

    fun build(live: LiveState?, now: Long, f: Formatter, t: CardText, autoReady: Boolean): CardContent {
        if (live == null) {
            return CardContent(t.idle, if (autoReady) t.idleAuto else t.idleManual, "", recording = false, gauge = 0f)
        }
        val elapsed = String.format(Locale.getDefault(), t.recording, f.duration(now - live.startedAt))
        val p = live.last
        if (!live.hasFix || p == null) {
            return CardContent(t.waiting, elapsed, "", recording = true, gauge = 0f)
        }
        val fresh = now - p.t <= STALE_MS
        val speed = if (fresh && !p.speed.isNaN()) p.speed.coerceAtLeast(0f) else 0f
        val title = String.format(Locale.getDefault(), t.live, f.speed(speed), f.distance(live.distanceM))
        val details = String.format(
            Locale.getDefault(), t.details,
            coordinates(p.lat, p.lon),
            if (p.acc.isNaN()) "–" else f.height(p.acc),
            if (p.alt.isNaN()) "–" else f.height(p.alt),
        )
        return CardContent(title, elapsed, details, recording = true, gauge = (speed * 3.6f / CardContent.GAUGE_MAX_KMH).coerceIn(0f, 1f))
    }

    fun coordinates(lat: Double, lon: Double): String = String.format(
        Locale.US, "%.5f° %s, %.5f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
    )
}
