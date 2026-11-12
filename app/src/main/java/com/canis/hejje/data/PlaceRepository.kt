package com.canis.hejje.data

import com.canis.hejje.data.db.PlaceDao
import com.canis.hejje.data.db.PlaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Place names from OpenStreetMap's Nominatim service, following its usage policy:
 * at most one request per second (we wait longer), an identifying User-Agent (set on the shared
 * HTTP client), every answer cached on the phone, and only a handful of lookups per trip.
 */
class PlaceRepository(
    private val dao: PlaceDao,
    private val http: OkHttpClient,
    private val trips: TripRepository,
    private val settings: SettingsStore,
) {
    private val gate = Mutex()
    private var lastRequest = 0L
    private var lastFailed = false

    fun key(lat: Double, lon: Double): String = String.format(Locale.US, "%.3f,%.3f", lat, lon)

    suspend fun cached(lat: Double, lon: Double): String? = dao.get(key(lat, lon))?.name?.takeIf { it.isNotEmpty() }

    /** Cached name, or a fresh lookup when allowed. Null when unknown or offline. */
    suspend fun name(lat: Double, lon: Double): String? {
        if (lat == 0.0 && lon == 0.0) return null
        val k = key(lat, lon)
        dao.get(k)?.let { hit ->
            if (hit.name.isNotEmpty()) return hit.name
            if (System.currentTimeMillis() - hit.fetchedAt < RETRY_EMPTY_MS) return null
        }
        if (!settings.current.placeLookup) return null
        return gate.withLock {
            val wait = lastRequest + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
            val name = fetch(lat, lon)
            lastFailed = name == null
            if (name == null) return@withLock null
            dao.put(PlaceEntity(k, name, System.currentTimeMillis()))
            name.takeIf { it.isNotEmpty() }
        }
    }

    /** @return the name, "" when Nominatim knows nothing here, null when the request failed. */
    private suspend fun fetch(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=16&addressdetails=1" +
                "&lat=${String.format(Locale.US, "%.5f", lat)}&lon=${String.format(Locale.US, "%.5f", lon)}"
            val request = Request.Builder().url(url).header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let(::parse)
            }
        }.getOrNull()
    }

    /** Fills in missing place names of recent trips. Stops at the first failure (probably offline). */
    suspend fun fillMissing(limit: Int = 20) {
        if (!settings.current.placeLookup) return
        lastFailed = false
        for (t in trips.withoutPlaces(limit)) {
            val start = t.startPlace ?: name(t.startLat, t.startLon)
            val end = t.endPlace ?: name(t.endLat, t.endLon)
            if (start != null || end != null) trips.setPlaces(t.id, start, end)
            if (lastFailed) return
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 1_500L
        private const val RETRY_EMPTY_MS = 7 * 86_400_000L

        /** Builds a short name such as "Indiranagar, Bengaluru" from a Nominatim answer. */
        fun parse(json: String): String {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return ""
            val address = root["address"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return ""
            fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> address[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }
            val local = pick("neighbourhood", "suburb", "quarter", "residential", "hamlet", "village", "road")
            val city = pick("city", "town", "municipality", "village", "county", "state_district", "state")
            return listOfNotNull(local, city).distinct().joinToString(", ")
        }
    }
}
