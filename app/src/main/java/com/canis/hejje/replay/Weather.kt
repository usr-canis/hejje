package com.canis.hejje.replay

import com.canis.hejje.domain.Replay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The weather the trip really had, hour by hour, from Open-Meteo (free, no key). Only the date
 * and the car's rough area each hour (rounded to 0.1 degree, about 10 km) are sent. Returns null
 * when it cannot be had, and the video then shows no weather rather than guessing.
 */
object Weather {
    class Hour(val time: Long, val cloud: Double, val rain: Double, val visibility: Double, val code: Int)

    private const val RECENT_DAYS = 80L
    private const val MAX_PLACES = 30
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hourly(http: OkHttpClient, pts: List<Replay.Pt>): List<Hour>? = withContext(Dispatchers.IO) {
        runCatching { fetch(http, pts) }.getOrNull()
    }

    /** Where the car was in the middle of each hour, rounded; the request; and which place each hour uses. */
    fun plan(pts: List<Replay.Pt>, nowMs: Long = System.currentTimeMillis()): Pair<String, List<Pair<Long, Int>>>? {
        if (pts.isEmpty()) return null
        val first = pts.first().time / 3_600_000
        val last = pts.last().time / 3_600_000
        val hours = (first..last).toList()
        fun near(ms: Long) = pts.minBy { kotlin.math.abs(it.time - ms) }
        fun key(p: Replay.Pt) = Pair((p.lat * 10).roundToInt() / 10.0, (p.lon * 10).roundToInt() / 10.0)
        var places = hours.map { key(near(it * 3_600_000 + 1_800_000)) }.distinct()
        if (places.size > MAX_PLACES) places = List(MAX_PLACES) { places[it * places.size / MAX_PLACES] }
        val perHour = hours.map { h ->
            val k = key(near(h * 3_600_000 + 1_800_000))
            Pair(h * 3600, places.indices.minBy { i -> sq(places[i].first - k.first) + sq(places[i].second - k.second) })
        }
        val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)
        val recent = nowMs - pts.last().time < RECENT_DAYS * 86_400_000
        val host = if (recent) "https://api.open-meteo.com/v1/forecast" else "https://archive-api.open-meteo.com/v1/archive"
        val url = host + "?latitude=" + places.joinToString(",") { it.first.toString() } +
            "&longitude=" + places.joinToString(",") { it.second.toString() } +
            "&hourly=weather_code,cloud_cover,precipitation,visibility&timezone=UTC" +
            "&start_date=" + day.format(Instant.ofEpochMilli(pts.first().time)) + "&end_date=" + day.format(Instant.ofEpochMilli(pts.last().time))
        return Pair(url, perHour)
    }

    private fun fetch(http: OkHttpClient, pts: List<Replay.Pt>): List<Hour>? {
        val (url, perHour) = plan(pts) ?: return null
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        return parse(body, perHour)
    }

    /** Picks each hour's values from the reply (one object, or a list for several places). */
    fun parse(body: String, perHour: List<Pair<Long, Int>>): List<Hour>? {
        val root: JsonElement = json.parseToJsonElement(body)
        val places: List<JsonObject> = if (root is JsonArray) root.map { it.jsonObject } else listOf(root.jsonObject)
        val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.US).withZone(ZoneOffset.UTC)
        val out = perHour.mapNotNull { (sec, place) ->
            val h = places.getOrNull(place)?.get("hourly")?.jsonObject ?: return@mapNotNull null
            val i = h["time"]?.jsonArray?.indexOfFirst { it.jsonPrimitive.content == iso.format(Instant.ofEpochSecond(sec)) } ?: -1
            if (i < 0) return@mapNotNull null
            fun num(name: String) = h[name]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull
            val cloud = num("cloud_cover") ?: return@mapNotNull null
            Hour(sec, cloud, num("precipitation") ?: 0.0, num("visibility") ?: 20_000.0, h["weather_code"]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.intOrNull ?: 0)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun sq(x: Double) = x * x
}
