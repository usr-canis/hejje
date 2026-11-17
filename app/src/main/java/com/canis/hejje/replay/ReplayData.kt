package com.canis.hejje.replay

import android.content.Context
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.domain.Gpx
import com.canis.hejje.domain.Replay
import com.canis.hejje.domain.StatsCalculator
import com.canis.hejje.domain.Zone
import com.canis.hejje.ui.formatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToInt

/** Gathers what the replay page draws: the visible route, highlights, the weather and the total. */
object ReplayData {
    /** The page's data, and the route's shape (x, y pairs in 0..1) for the progress screen. */
    class Prepared(val json: String, val outline: FloatArray, val title: String)

    /** The JSON the page reads, or null when the trip has no route to show. */
    suspend fun build(
        context: Context,
        container: AppContainer,
        tripId: Long,
        weatherOf: suspend (List<Replay.Pt>) -> List<Weather.Hour>? = { Weather.hourly(container.http, it) },
    ): Prepared? = withContext(Dispatchers.Default) {
        val trip = container.trips.trip(tripId) ?: return@withContext null
        val segments = container.trips.segments(tripId)
        if (segments.isEmpty()) return@withContext null
        val zones = container.db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
        val pts = Replay.points(Gpx.pointsOf(segments, zones))
        if (pts.size < 2 || pts.last().d < 50) return@withContext null
        val stats = StatsCalculator.compute(segments)
        val highlights = Replay.highlights(pts, stats.maxSpeed, stats.altMin, stats.altMax)
        val weather = weatherOf(pts)
        val f = context.formatter()
        buildJsonObject {
            putJsonArray("route") {
                for (p in pts) addJsonArray {
                    add(round6(p.lon)); add(round6(p.lat)); add(round1(p.d)); add(p.time / 1000.0)
                    add(p.piece); add(round1(p.sunEl, 100)); add(round1(p.sunAz, 10))
                }
            }
            putJsonArray("highlights") {
                for (h in highlights) addJsonObject {
                    put("kind", h.kind.name); put("d", round1(h.d)); put("lon", round6(h.lon)); put("lat", round6(h.lat))
                    put("key", context.getString(keyOf(h.kind)))
                    put(
                        "value",
                        when (h.kind) {
                            Replay.Kind.TOP, Replay.Kind.SLOW -> f.speed(h.value.toFloat())
                            Replay.Kind.HIGH -> f.height(h.value.toFloat())
                            Replay.Kind.STEADY -> "${f.speed(h.value.toFloat())} · ${f.distance(h.lengthM)}"
                            Replay.Kind.SUNRISE, Replay.Kind.SUNSET -> f.time(h.time)
                        },
                    )
                }
            }
            if (weather == null) put("weather", JsonNull) else putJsonArray("weather") {
                for (w in weather) addJsonObject { put("t", w.time); put("cloud", w.cloud); put("rain", w.rain); put("vis", w.visibility); put("code", w.code) }
            }
            putJsonObject("total") { put("key", context.getString(R.string.replay_total)); put("value", f.distance(trip.distanceM)) }
            put("brand", context.getString(R.string.brand_name))
            put("credit", context.getString(R.string.replay_credit))
        }.toString().let {
            val zone = java.time.ZoneId.systemDefault()
            val day = f.dayLabel(com.canis.hejje.domain.Insights.dayOf(trip.startTime, zone), java.time.LocalDate.now(zone))
            Prepared(it, outline(pts), "$day · ${f.distance(trip.distanceM)}")
        }
    }

    /** The route drawn small: up to 400 points, fitted into a unit square, north up. */
    fun outline(pts: List<Replay.Pt>): FloatArray {
        if (pts.size < 2) return FloatArray(0)
        val step = maxOf(1, pts.size / 400)
        val kept = pts.filterIndexed { i, _ -> i % step == 0 || i == pts.lastIndex }
        val cos = Math.cos(Math.toRadians(kept.map { it.lat }.average()))
        val xs = kept.map { it.lon * cos }
        val ys = kept.map { -it.lat }
        val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), 1e-9)
        val ox = (xs.min() + xs.max()) / 2
        val oy = (ys.min() + ys.max()) / 2
        val out = FloatArray(kept.size * 2)
        for (i in kept.indices) { out[2 * i] = (.5 + (xs[i] - ox) / span).toFloat().coerceIn(0f, 1f); out[2 * i + 1] = (.5 + (ys[i] - oy) / span).toFloat().coerceIn(0f, 1f) }
        return out
    }

    private fun keyOf(kind: Replay.Kind) = when (kind) {
        Replay.Kind.TOP -> R.string.replay_top_speed
        Replay.Kind.HIGH -> R.string.replay_highest
        Replay.Kind.STEADY -> R.string.replay_steady
        Replay.Kind.SLOW -> R.string.replay_slowest
        Replay.Kind.SUNRISE -> R.string.replay_sunrise
        Replay.Kind.SUNSET -> R.string.replay_sunset
    }

    private fun round6(x: Double) = JsonPrimitive(Math.round(x * 1e6) / 1e6)
    private fun round1(x: Double, k: Int = 10) = JsonPrimitive((x * k).roundToInt() / k.toDouble())
}
