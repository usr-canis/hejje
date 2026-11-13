package com.canis.hejje.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ProcessorConfig(
    val brakeThreshold: Float = 3.0f,
    val accelThreshold: Float = 2.7f,
    val stepMs: Int = 5_000,
    val epsilonM: Double = 4.0,
)

/** Turns the raw GPS fixes of one recording into a compact [Segment]. The raw points can then be discarded. */
object TripProcessor {
    const val MAX_ACCURACY_M = 50f
    const val GAP_MS = 8_000L
    private const val MAX_SPEED = 70.0 // m/s, anything faster between fixes is a GPS jump
    private const val START_MOVING = 1.4f
    private const val STOP_MOVING = 0.6f
    private const val MAX_ROUTE_GAP_MS = 10_000L

    /** Sorts, removes duplicates, poor fixes and position jumps. */
    fun clean(raw: List<RawPoint>): List<RawPoint> {
        val out = ArrayList<RawPoint>(raw.size)
        for (p in raw.sortedBy { it.t }) {
            if (p.lat.isNaN() || p.lon.isNaN() || (p.lat == 0.0 && p.lon == 0.0)) continue
            if (!p.acc.isNaN() && p.acc > MAX_ACCURACY_M) continue
            val last = out.lastOrNull()
            if (last != null) {
                val dt = p.t - last.t
                if (dt <= 0) continue
                val d = Geo.distance(last.lat, last.lon, p.lat, p.lon)
                if (d / (dt / 1000.0) > MAX_SPEED && d > 50) continue
            }
            out += p
        }
        return out
    }

    fun process(raw: List<RawPoint>, start: Long, end: Long, cfg: ProcessorConfig = ProcessorConfig()): Segment? {
        val pts = clean(raw.filter { it.t in start..end })
        if (pts.isEmpty() || end <= start) return null
        val n = pts.size

        // Speed per point: GPS (Doppler) speed when present, otherwise derived from positions.
        val v = FloatArray(n)
        for (i in 0 until n) {
            v[i] = if (!pts[i].speed.isNaN()) max(0f, pts[i].speed) else if (i > 0) {
                val dt = (pts[i].t - pts[i - 1].t) / 1000f
                (Geo.distance(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon) / dt).toFloat()
            } else 0f
        }
        if (n > 1 && pts[0].speed.isNaN()) v[0] = v[1]
        val vs = FloatArray(n) { i ->
            HarshEventDetector.median3(v[max(0, i - 1)], v[i], v[min(n - 1, i + 1)])
        }
        val moving = BooleanArray(n)
        var m = false
        for (i in 0 until n) {
            if (!m && vs[i] > START_MOVING) m = true else if (m && vs[i] < STOP_MOVING) m = false
            moving[i] = m
        }

        val step = cfg.stepMs
        val duration = end - start
        val wn = ((duration + step - 1) / step).toInt().coerceAtLeast(1)
        val dist = FloatArray(wn)
        val mov = FloatArray(wn)
        val vmax = FloatArray(wn)
        val a2 = FloatArray(wn)
        val sensorMs = IntArray(wn)
        val saccSum = FloatArray(wn)
        val lat = FloatArray(wn)
        fun window(t: Long) = ((t - start) / step).toInt().coerceIn(0, wn - 1)

        if (moving[0]) vmax[window(pts[0].t)] = vs[0]
        for (i in 1 until n) {
            val a = pts[i - 1]
            val b = pts[i]
            val dt = b.t - a.t
            val d = Geo.distance(a.lat, a.lon, b.lat, b.lon)
            val gap = dt > GAP_MS
            // Across a GPS gap (tunnel) the straight line counts if the car clearly moved.
            val drove = if (gap) d > 25 && d / (dt / 1000.0) > 1.0 else moving[i - 1] || moving[i]
            if (!drove) continue
            // Spread the interval over the windows it overlaps.
            var t = a.t
            while (t < b.t) {
                val w = window(t)
                val wEnd = min(b.t, start + (w + 1).toLong() * step).let { if (it <= t) b.t else it }
                val share = (wEnd - t).toFloat() / dt
                dist[w] += (d * share).toFloat()
                mov[w] += (wEnd - t).toFloat()
                t = wEnd
            }
            if (!gap) {
                val w = window(b.t)
                vmax[w] = max(vmax[w], vs[i])
                val acc = (v[i] - v[i - 1]) / (dt / 1000f)
                a2[w] += acc * acc * (dt / 1000f)
                if (!b.sLon.isNaN()) {
                    sensorMs[w] += dt.toInt()
                    saccSum[w] += b.sLon * dt
                    if (!b.sLat.isNaN()) lat[w] = max(lat[w], b.sLat)
                }
            }
        }

        val (altitude, baro) = altitudes(pts)
        val altSum = DoubleArray(wn)
        val altCount = IntArray(wn)
        for (i in 0 until n) {
            if (altitude[i].isNaN()) continue
            val w = window(pts[i].t)
            altSum[w] += altitude[i]
            altCount[w]++
        }
        val alt = FloatArray(wn) { if (altCount[it] > 0) (altSum[it] / altCount[it]).toFloat() else Float.NaN }
        val sensed = sensorMs.any { it > 0 }
        val profile = Profile(
            step, dist, IntArray(wn) { min(step.toFloat(), mov[it]).toInt() }, vmax, alt, a2,
            if (sensed) IntArray(wn) { min(step, sensorMs[it]) } else IntArray(0),
            if (sensed) FloatArray(wn) { if (sensorMs[it] > 0) saccSum[it] / sensorMs[it] else 0f } else FloatArray(0),
            if (sensed) lat else FloatArray(0),
        )

        val gpsEvents = HarshEventDetector.detect(pts, start, cfg.brakeThreshold, cfg.accelThreshold)
        val events = if (sensed) SensorEvents.fuse(gpsEvents, pts, vs, start, cfg.brakeThreshold, cfg.accelThreshold) else gpsEvents
        val route = buildRoute(pts, vs, moving, start, cfg.epsilonM)
        return Segment(start, end, SegmentData(route, profile, events, baro))
    }

    /**
     * Altitude per point and whether the barometer was used. The barometer gives smooth, precise
     * height changes; its absolute level is anchored to the median GPS altitude.
     */
    private fun altitudes(pts: List<RawPoint>): Pair<FloatArray, Boolean> {
        val n = pts.size
        val withPressure = pts.count { !it.pressure.isNaN() && it.pressure > 300f }
        val baro = withPressure >= max(3, (n * 0.6).toInt())
        val raw = FloatArray(n) { Float.NaN }
        if (baro) {
            val offsets = ArrayList<Float>()
            for (i in 0 until n) {
                val p = pts[i].pressure
                if (p.isNaN() || p <= 300f) continue
                raw[i] = (44_330.8 * (1 - (p / 1013.25).pow(0.190263))).toFloat()
                if (!pts[i].alt.isNaN()) offsets += pts[i].alt - raw[i]
            }
            val offset = if (offsets.isEmpty()) 0f else offsets.sorted()[offsets.size / 2]
            for (i in 0 until n) if (!raw[i].isNaN()) raw[i] += offset
        } else {
            for (i in 0 until n) raw[i] = pts[i].alt
        }
        // Centred moving average over time to remove jitter.
        val half = if (baro) 4_000L else 12_000L
        val out = FloatArray(n) { Float.NaN }
        var lo = 0
        var hi = 0
        var sum = 0.0
        var count = 0
        for (i in 0 until n) {
            while (hi < n && pts[hi].t <= pts[i].t + half) {
                if (!raw[hi].isNaN()) { sum += raw[hi]; count++ }
                hi++
            }
            while (pts[lo].t < pts[i].t - half) {
                if (!raw[lo].isNaN()) { sum -= raw[lo]; count-- }
                lo++
            }
            if (count > 0 && !raw[i].isNaN()) out[i] = (sum / count).toFloat()
        }
        return out to baro
    }

    private fun buildRoute(pts: List<RawPoint>, vs: FloatArray, moving: BooleanArray, start: Long, epsilon: Double): Route {
        val n = pts.size
        // Candidates: drop the jitter of a parked/idling car, keep where it stopped and set off again.
        val idx = ArrayList<Int>(n)
        val forced = ArrayList<Boolean>(n)
        var lastKeptT = Long.MIN_VALUE
        for (i in 0 until n) {
            val edge = i == 0 || i == n - 1
            val transition = (i > 0 && moving[i] != moving[i - 1]) || (i < n - 1 && moving[i] != moving[i + 1])
            val gapEdge = (i > 0 && pts[i].t - pts[i - 1].t > GAP_MS) || (i < n - 1 && pts[i + 1].t - pts[i].t > GAP_MS)
            if (!edge && !transition && !gapEdge && !moving[i]) continue
            // A regular anchor keeps speed colouring and time lookups accurate on long straights.
            val anchor = pts[i].t - lastKeptT >= MAX_ROUTE_GAP_MS
            val force = edge || transition || gapEdge || anchor
            if (force) lastKeptT = pts[i].t
            idx += i
            forced += force
        }
        val lat0 = pts[0].lat
        val lon0 = pts[0].lon
        val xs = DoubleArray(idx.size) { Geo.x(pts[idx[it]].lon, lon0, lat0) }
        val ys = DoubleArray(idx.size) { Geo.y(pts[idx[it]].lat, lat0) }
        val keep = Simplifier.simplify(xs, ys, epsilon, forced.toBooleanArray())
        return Route(
            DoubleArray(keep.size) { Geo.round6(pts[idx[keep[it]]].lat) },
            DoubleArray(keep.size) { Geo.round6(pts[idx[keep[it]]].lon) },
            IntArray(keep.size) { ((pts[idx[keep[it]]].t - start) / 1000).toInt() },
            FloatArray(keep.size) { Math.round(vs[idx[keep[it]]] * 100) / 100f },
        )
    }
}
