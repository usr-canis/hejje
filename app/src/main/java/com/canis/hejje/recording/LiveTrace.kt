package com.canis.hejje.recording

import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.RawPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The line driven so far (thinned out) and which way the car is heading, for the live card. */
class TraceState(val lat: DoubleArray, val lon: DoubleArray, val heading: Float?) {
    val size: Int get() = lat.size

    companion object {
        val EMPTY = TraceState(DoubleArray(0), DoubleArray(0), null)
    }
}

/**
 * Keeps the drive being recorded as a light line for the live card: a point every [STEP_M] metres
 * (twice as far apart each time it grows past [MAX_POINTS]), and the heading over the last
 * [HEADING_M] metres, so standing still does not spin the compass. Main thread only.
 */
class LiveTrace {
    private val lat = ArrayList<Double>()
    private val lon = ArrayList<Double>()
    private var step = STEP_M
    private var heading: Float? = null
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private val _state = MutableStateFlow(TraceState.EMPTY)
    val state: StateFlow<TraceState> = _state.asStateFlow()

    fun reset() {
        lat.clear()
        lon.clear()
        step = STEP_M
        heading = null
        anchorLat = Double.NaN
        anchorLon = Double.NaN
        _state.value = TraceState.EMPTY
    }

    /** The points already recorded, after the app was restarted mid-drive. */
    fun load(points: List<RawPoint>) {
        reset()
        points.forEach { add(it, publish = false) }
        publish()
    }

    fun add(p: RawPoint) = add(p, publish = true)

    private fun add(p: RawPoint, publish: Boolean) {
        var changed = false
        if (anchorLat.isNaN()) {
            anchorLat = p.lat
            anchorLon = p.lon
        } else if (Geo.distance(anchorLat, anchorLon, p.lat, p.lon) >= HEADING_M) {
            heading = Geo.bearing(anchorLat, anchorLon, p.lat, p.lon)
            anchorLat = p.lat
            anchorLon = p.lon
            changed = true
        }
        val n = lat.size
        if (n == 0 || Geo.distance(lat[n - 1], lon[n - 1], p.lat, p.lon) >= step) {
            lat += p.lat
            lon += p.lon
            if (lat.size > MAX_POINTS) thin()
            changed = true
        }
        if (publish && changed) publish()
    }

    /** Every other point (always keeping the newest), and twice the spacing from now on. */
    private fun thin() {
        val last = lat.size - 1
        val keepLat = lat.filterIndexed { i, _ -> i % 2 == 0 || i == last }
        val keepLon = lon.filterIndexed { i, _ -> i % 2 == 0 || i == last }
        lat.clear(); lat.addAll(keepLat)
        lon.clear(); lon.addAll(keepLon)
        step *= 2
    }

    private fun publish() {
        _state.value = TraceState(lat.toDoubleArray(), lon.toDoubleArray(), heading)
    }

    companion object {
        const val STEP_M = 15.0
        const val HEADING_M = 20.0
        const val MAX_POINTS = 2_000
    }
}
