package com.canis.hejje.ui.detail

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canis.hejje.AppContainer
import com.canis.hejje.data.SettingsData
import com.canis.hejje.data.Undo
import com.canis.hejje.data.db.TagEntity
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.domain.PrivacyZones
import com.canis.hejje.domain.Segment
import com.canis.hejje.domain.StatsCalculator
import com.canis.hejje.domain.TripStats
import com.canis.hejje.domain.Zone
import com.canis.hejje.ui.map.MapMarker
import com.canis.hejje.ui.map.MapRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Speed-over-time series for the chart, with the place and time of every sample. */
class ChartSeries(val speeds: FloatArray, val times: LongArray, val lat: DoubleArray, val lon: DoubleArray) {
    val size: Int get() = speeds.size

    companion object {
        val EMPTY = ChartSeries(FloatArray(0), LongArray(0), DoubleArray(0), DoubleArray(0))
        private const val MAX_SAMPLES = 240

        fun of(segments: List<Segment>): ChartSeries {
            val total = segments.sumOf { it.data.profile.size }
            if (total == 0) return EMPTY
            val group = max(1, (total + MAX_SAMPLES - 1) / MAX_SAMPLES)
            val speeds = ArrayList<Float>()
            val times = ArrayList<Long>()
            val lat = ArrayList<Double>()
            val lon = ArrayList<Double>()
            for (s in segments) {
                val p = s.data.profile
                val r = s.data.route
                var i = 0
                while (i < p.size) {
                    val end = minOf(p.size, i + group)
                    var dist = 0f
                    for (k in i until end) dist += p.dist[k]
                    val mid = (i + end) / 2f * p.stepMs
                    speeds += dist / ((end - i) * p.stepMs / 1000f)
                    times += s.start + mid.toLong()
                    val idx = r.indexAt(mid / 1000f)
                    lat += r.latAt(idx)
                    lon += r.lonAt(idx)
                    i = end
                }
            }
            return ChartSeries(speeds.toFloatArray(), times.toLongArray(), lat.toDoubleArray(), lon.toDoubleArray())
        }
    }
}

data class DetailState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val item: TripWithTags? = null,
    val segments: List<Segment> = emptyList(),
    val stats: TripStats? = null,
    val chart: ChartSeries = ChartSeries.EMPTY,
    val routes: List<MapRoute> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
    val allTags: List<TagEntity> = emptyList(),
    val settings: SettingsData = SettingsData(),
    val previous: TripEntity? = null,
    val next: TripEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModel(private val container: AppContainer, private val tripId: Long) : ViewModel() {
    private val trips = container.trips

    private class Loaded(val segments: List<Segment>, val stats: TripStats?, val chart: ChartSeries, val previous: TripEntity?, val next: TripEntity?)

    private val item = trips.observeTrip(tripId)

    /** Segments are decoded again only when the trip itself changed, not for a new note or tag. */
    private val loaded = item.distinctUntilChangedBy { t -> t?.trip?.let { listOf(it.startTime, it.endTime, it.distanceM, it.segmentCount) } }
        .mapLatest { t ->
            if (t == null) null else withContext(Dispatchers.Default) {
                val segments = trips.segments(tripId)
                val (previous, next) = trips.neighbours(tripId)
                Loaded(segments, if (segments.isEmpty()) null else StatsCalculator.compute(segments), ChartSeries.of(segments), previous, next)
            }
        }

    val state: StateFlow<DetailState> = combine(
        item, loaded, trips.tags, container.settings.data, container.db.zones().observe(),
    ) { item, loaded, tags, settings, zones ->
        if (item == null) return@combine DetailState(loading = false, missing = true)
        if (loaded == null) return@combine DetailState(item = item, allTags = tags, settings = settings)
        val hidden = if (settings.zonesInApp) zones.filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) } else emptyList()
        val routes = loaded.segments.flatMap { s -> PrivacyZones.trim(s.data.route, hidden).map(MapRoute::of) }.filter { it.size > 0 }
        val markers = ArrayList<MapMarker>()
        routes.firstOrNull()?.let { markers += MapMarker(it.lat.first(), it.lon.first(), 0) }
        loaded.stats?.stops?.filter { !PrivacyZones.inside(it.lat, it.lon, hidden) }?.forEach { markers += MapMarker(it.lat, it.lon, 2) }
        routes.lastOrNull()?.let { markers += MapMarker(it.lat.last(), it.lon.last(), 1) }
        DetailState(
            loading = false, item = item, segments = loaded.segments, stats = loaded.stats, chart = loaded.chart,
            routes = routes, markers = markers, allTags = tags, settings = settings, previous = loaded.previous, next = loaded.next,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun setTag(name: String, on: Boolean) = viewModelScope.launch { trips.setTag(tripId, name, on) }

    fun setNote(note: String) = viewModelScope.launch { trips.setNote(tripId, note) }

    fun setRouteBySpeed(on: Boolean) = viewModelScope.launch { container.settings.update { it.copy(routeBySpeed = on) } }

    // Edits run outside the screen's lifetime so they finish even if the screen closes meanwhile.
    fun delete(done: (Undo?) -> Unit) = edit(done) { trips.delete(tripId) }

    fun trim(start: Long, end: Long, done: (Undo?) -> Unit) = edit(done) { trips.trim(tripId, start, end) }

    fun split(at: Long, done: (Undo?) -> Unit) = edit(done) { trips.split(tripId, at) }

    fun merge(otherId: Long, done: (Undo?) -> Unit) = edit(done) { trips.merge(tripId, otherId) }

    private fun edit(done: (Undo?) -> Unit, block: suspend () -> Undo?) {
        container.scope.launch {
            val undo = runCatching { block() }.getOrNull()
            if (undo != null) {
                com.canis.hejje.work.PlaceWorker.enqueue(container.app)
                com.canis.hejje.widget.HejjeWidget.refresh(container.app)
            }
            withContext(Dispatchers.Main) { done(undo) }
        }
    }

    fun exportGpx(uri: Uri, done: (Boolean) -> Unit) {
        container.scope.launch {
            val ok = runCatching { container.backup.exportGpx(tripId, uri) }.isSuccess
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    /** Private places, which share cards leave out. */
    suspend fun zones(): List<Zone> = container.db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
}
