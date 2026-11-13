package com.canis.hejje.ui.stretch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canis.hejje.AppContainer
import com.canis.hejje.R
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.PrivacyZones
import com.canis.hejje.domain.StretchSummary
import com.canis.hejje.domain.Stretches
import com.canis.hejje.domain.TripTrack
import com.canis.hejje.domain.Zone
import com.canis.hejje.ui.map.MapRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StretchState(
    val loading: Boolean = true,
    /** Every drive, drawn faintly so the roads you use show up. */
    val lines: List<MapRoute> = emptyList(),
    /** What the map frames first: the trip it was opened from, or the latest drives. */
    val frame: List<MapRoute> = emptyList(),
    val a: Pair<Double, Double>? = null,
    val b: Pair<Double, Double>? = null,
    val summary: StretchSummary? = null,
    val searching: Boolean = false,
    /** A short message for the last tap, e.g. that it was not on a road you drove. */
    val message: Int? = null,
    val fromTrip: Long? = null,
)

/**
 * Pick a start and an end on roads you drove; every drive from the first to the second is compared.
 * With [onlyTrip], only the trip [fromTrip] is used (choosing a part of one trip), and the two
 * points may be tapped in either order.
 */
class StretchViewModel(private val container: AppContainer, fromTrip: Long?, private val onlyTrip: Boolean = false) : ViewModel() {
    private var tracks: List<TripTrack> = emptyList()
    private val _state = MutableStateFlow(StretchState(fromTrip = fromTrip))
    val state: StateFlow<StretchState> = _state

    init {
        viewModelScope.launch {
            val loaded = container.trips.tracks().let { all -> if (onlyTrip) all.filter { it.tripId == fromTrip } else all }
            val settings = container.settings.current
            val hidden = if (settings.zonesInApp) {
                container.db.zones().all().filter { it.hide }.map { Zone(it.lat, it.lon, it.radiusM) }
            } else emptyList()
            val (lines, frame) = withContext(Dispatchers.Default) {
                fun draw(t: TripTrack) = t.segments.flatMap { s -> PrivacyZones.trim(s.data.route, hidden).map(MapRoute::of) }.filter { it.size > 1 }
                val lines = loaded.flatMap(::draw)
                val focus = loaded.firstOrNull { it.tripId == fromTrip }
                lines to (focus?.let(::draw) ?: loaded.take(FRAME_TRIPS).flatMap(::draw))
            }
            tracks = loaded
            _state.update { it.copy(loading = false, lines = lines, frame = frame) }
        }
    }

    /** A tap on the map; [reachM] is how far from a road a tap may land and still count. */
    fun tap(lat: Double, lon: Double, reachM: Double) {
        if (_state.value.loading) return
        val point = Stretches.snap(lat, lon, tracks, reachM)
        if (point == null) {
            _state.update { it.copy(message = R.string.stretch_not_on_road) }
            return
        }
        val s = _state.value
        when {
            s.a == null || s.b != null -> _state.update { it.copy(a = point, b = null, summary = null, message = null) }
            Geo.distance(s.a.first, s.a.second, point.first, point.second) < Stretches.MIN_GAP_M ->
                _state.update { it.copy(message = R.string.stretch_too_close) }
            else -> {
                _state.update { it.copy(b = point, message = null) }
                search()
            }
        }
    }

    fun swap() {
        val s = _state.value
        if (s.a == null || s.b == null) return
        _state.update { it.copy(a = s.b, b = s.a, summary = null) }
        search()
    }

    fun clear() = _state.update { it.copy(a = null, b = null, summary = null, message = null, searching = false) }

    private fun search() {
        val s = _state.value
        val a = s.a ?: return
        val b = s.b ?: return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            val (passes, reversed) = withContext(Dispatchers.Default) {
                val forward = Stretches.find(a.first, a.second, b.first, b.second, tracks)
                // Within one trip the order of the taps does not matter: use the order it was driven.
                if (forward.isEmpty() && onlyTrip) Stretches.find(b.first, b.second, a.first, a.second, tracks) to true else forward to false
            }
            val summary = Stretches.summarize(passes)
            // Ignore a result for points that changed meanwhile.
            _state.update {
                if (it.a != a || it.b != b) it
                else if (reversed && summary != null) it.copy(a = b, b = a, summary = summary, searching = false)
                else it.copy(summary = summary, searching = false)
            }
        }
    }

    private companion object {
        const val FRAME_TRIPS = 15
    }
}
