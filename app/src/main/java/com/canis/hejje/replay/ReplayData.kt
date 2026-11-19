package com.canis.hejje.replay

import android.content.Context
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.domain.Gpx
import com.canis.hejje.domain.Insights
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
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Gathers what the replay page draws: the visible route (or part of it), highlights, the weather and the total. */
object ReplayData {
    /** A trip ready for choosing a part: its visible route, highlights, outline and suggested parts. */
    class Trip(
        val id: Long,
        val pts: List<Replay.Pt>,
        val highlights: List<Replay.Highlight>,
        /** The route drawn small (x, y pairs in 0..1) and the distance along the route of each drawn point. */
        val outline: FloatArray,
        val outlineD: FloatArray,
        val lengthM: Double,
        val tripDistanceM: Double,
        val day: String,
        val startName: String?,
        val endName: String?,
        val suggestions: List<Suggestion>,
    )

    /** A ready-made part to pick: a label and a stretch of the visible route, in metres. */
    class Suggestion(val label: String, val from: Double, val to: Double)

    /** The page's data, and the route's shape for the progress screen. */
    class Prepared(val json: String, val outline: FloatArray, val title: String)

    /** The trip's visible route and what can be shown of it, or null when there is none. */
    suspend fun load(context: Context, container: AppContainer, tripId: Long): Trip? = withContext(Dispatchers.Default) {
        val trip = container.trips.trip(tripId) ?: return@withContext null
        val segments = container.trips.segments(tripId)
        if (segments.isEmpty()) return@withContext null
        val zones = container.db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
        val pts = Replay.points(Gpx.pointsOf(segments, zones))
        if (pts.size < 2 || pts.last().d < 50) return@withContext null
        val stats = StatsCalculator.compute(segments)
        val highlights = Replay.highlights(pts, stats.maxSpeed, stats.altMin, stats.altMax)
        val f = context.formatter()
        val zone = ZoneId.systemDefault()
        val length = pts.last().d
        val (outline, outlineD) = outline(pts)
        // Each highlight suggests the stretch around it; the whole trip comes first.
        val half = (length * .08).coerceIn(3_000.0, 15_000.0)
        val suggestions = listOf(Suggestion(context.getString(R.string.replay_whole_trip), 0.0, length)) +
            highlights.map { Suggestion(context.getString(keyOf(it.kind)), (it.d - half).coerceAtLeast(0.0), (it.d + half).coerceAtMost(length)) }
        Trip(
            tripId, pts, highlights, outline, outlineD, length, trip.distanceM,
            f.dayLabel(Insights.dayOf(trip.startTime, zone), LocalDate.now(zone)),
            shortName(trip.startPlace), shortName(trip.endPlace), suggestions,
        )
    }

    /** The page's data for the part [from]..[to] (metres along the visible route) of [trip]. */
    suspend fun build(
        context: Context,
        container: AppContainer,
        trip: Trip,
        from: Double = 0.0,
        to: Double = trip.lengthM,
        weatherOf: suspend (List<Replay.Pt>) -> List<Weather.Hour>? = { Weather.hourly(container.http, it) },
    ): Prepared = withContext(Dispatchers.Default) {
        val whole = from <= 1 && to >= trip.lengthM - 1
        val pts = if (whole) trip.pts else part(trip.pts, from, to)
        // The whole trip uses the trip's own statistics, so the video agrees with the app.
        val highlights = if (whole) trip.highlights else Replay.highlights(
            pts, pts.maxOf { it.speed }.toFloat(),
            pts.map { it.ele }.filter { !it.isNaN() }.minOrNull() ?: Float.NaN,
            pts.map { it.ele }.filter { !it.isNaN() }.maxOrNull() ?: Float.NaN,
        )
        val weather = weatherOf(pts)
        val f = context.formatter()
        val distance = if (whole) trip.tripDistanceM else pts.last().d
        val json = buildJsonObject {
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
            putJsonObject("total") { put("key", context.getString(R.string.replay_total)); put("value", f.distance(distance)) }
            // Where it starts and ends, when the app knows the names; otherwise the page reads them from the map.
            if (from <= 1) trip.startName?.let { put("startName", it) }
            if (to >= trip.lengthM - 1) trip.endName?.let { put("endName", it) }
            put("brand", context.getString(R.string.brand_name))
            put("near", context.getString(R.string.replay_near))
            put("credit", context.getString(R.string.replay_credit))
        }.toString()
        Prepared(json, outline(pts).first, "${trip.day} · ${f.distance(distance)}")
    }

    /** The points from [from] to [to] metres along the route, measured again from the part's start. */
    fun part(pts: List<Replay.Pt>, from: Double, to: Double): List<Replay.Pt> {
        val kept = pts.filter { it.d in from..to }.ifEmpty { pts.take(2) }
        val base = kept.first().d
        return kept.map { p -> Replay.Pt(p.lat, p.lon, p.time, p.ele, p.piece, p.d - base).also { it.speed = p.speed; it.sunEl = p.sunEl; it.sunAz = p.sunAz } }
    }

    /** "Indiranagar, Bengaluru" becomes "Indiranagar"; blank names give null. */
    private fun shortName(place: String?): String? = place?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

    /** The route drawn small (up to 400 points, fitted into a unit square, north up) and each point's distance. */
    fun outline(pts: List<Replay.Pt>): Pair<FloatArray, FloatArray> {
        if (pts.size < 2) return Pair(FloatArray(0), FloatArray(0))
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
        return Pair(out, FloatArray(kept.size) { kept[it].d.toFloat() })
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
