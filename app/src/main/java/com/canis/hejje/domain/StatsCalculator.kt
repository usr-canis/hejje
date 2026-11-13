package com.canis.hejje.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Computes every trip statistic from the stored segments. Used after recording and after each edit. */
object StatsCalculator {
    /** Upper limits (km/h) of the speed bands; the last band is everything above. */
    val BANDS_KMH = intArrayOf(30, 60, 90, 120)
    private const val MIN_STOP_MS = 30_000L
    private const val NIGHT_SUN_ALTITUDE = -0.833

    /** Profile acceleration (m/s²) at or below which Fluidity loses nothing, and at which it loses everything. */
    private const val CALM_RMS = 0.55
    private const val ROUGH_RMS = 1.35

    /** Sideways acceleration (m/s²) from which a window counts as a turn, and the cornering labels. */
    private const val TURN_LAT = 1.2f
    private const val MIN_TURNS = 3
    const val GENTLE_CORNERING = 2.0f
    const val FIRM_CORNERING = 3.2f

    /**
     * How unevenly the speed changed, 0 (perfectly even) to 1 (very jerky). Measured from the speed
     * of each 5-second block of the trip, so GPS jitter averages out and every trip, old or new,
     * is scored the same way. Where the phone's sensors measured both blocks, their mean
     * acceleration gives the same quantity without GPS noise.
     */
    fun roughness(segments: List<Segment>): Double {
        var sum = 0.0
        var seconds = 0.0
        for (s in segments) {
            val p = s.data.profile
            val step = p.stepMs / 1000.0
            for (i in 1 until p.size) {
                if (p.moving[i] == 0 && p.moving[i - 1] == 0) continue
                val a = if (p.sensed(i) && p.sensed(i - 1)) (p.sacc[i] + p.sacc[i - 1]) / 2.0
                else (p.dist[i] - p.dist[i - 1]) / step / step
                sum += a * a * step
                seconds += step
            }
        }
        val rms = if (seconds > 0) sqrt(sum / seconds) else 0.0
        return ((rms - CALM_RMS) / (ROUGH_RMS - CALM_RMS)).coerceIn(0.0, 1.0)
    }

    fun compute(segments: List<Segment>): TripStats {
        require(segments.isNotEmpty())
        val segs = segments.sortedBy { it.start }
        var distance = 0.0
        var moving = 0L
        var idle = 0L
        var stopped = 0L
        var maxSpeed = 0f
        var gain = 0f
        var loss = 0f
        var altMin = Float.NaN
        var altMax = Float.NaN
        var brakes = 0
        var accels = 0
        val bands = LongArray(BANDS_KMH.size + 1)
        val stops = ArrayList<Stop>()
        var nightMs = 0L
        var sampledMs = 0L

        segs.forEachIndexed { si, seg ->
            val p = seg.data.profile
            val route = seg.data.route
            var segMoving = 0L
            for (i in 0 until p.size) {
                distance += p.dist[i]
                segMoving += p.moving[i]
                maxSpeed = max(maxSpeed, p.vmax[i])
                if (p.moving[i] > 0) {
                    val kmh = p.speed(i) * 3.6f
                    bands[BANDS_KMH.indexOfFirst { kmh < it }.let { if (it < 0) BANDS_KMH.size else it }] += p.moving[i].toLong()
                }
            }
            moving += segMoving
            idle += max(0L, seg.durationMs - segMoving)

            // Elevation with hysteresis so sensor noise does not add up to false climbing.
            val threshold = if (seg.data.baro) 1.5f else 5f
            var ref = Float.NaN
            for (a in p.alt) {
                if (a.isNaN()) continue
                altMin = if (altMin.isNaN()) a else min(altMin, a)
                altMax = if (altMax.isNaN()) a else max(altMax, a)
                if (ref.isNaN()) ref = a
                else if (a - ref >= threshold) { gain += a - ref; ref = a }
                else if (ref - a >= threshold) { loss += ref - a; ref = a }
            }

            for (e in seg.data.events) if (e.brake) brakes++ else accels++

            // Idle stops: standing still with the car on, between two stretches of driving.
            val still = { i: Int -> p.moving[i] < p.stepMs / 5 }
            val firstMove = (0 until p.size).firstOrNull { !still(it) }
            val lastMove = (0 until p.size).lastOrNull { !still(it) }
            if (firstMove != null && lastMove != null) {
                var i: Int = firstMove
                val lastIdx: Int = lastMove
                while (i <= lastIdx) {
                    if (!still(i)) { i++; continue }
                    var j = i
                    while (j + 1 <= lastIdx && still(j + 1)) j++
                    val length = (j - i + 1).toLong() * p.stepMs
                    if (length >= MIN_STOP_MS) {
                        val idx = route.indexAt((i * p.stepMs + length / 2) / 1000f)
                        stops += Stop(seg.start + i.toLong() * p.stepMs, length, route.latAt(idx), route.lonAt(idx), parked = false)
                    }
                    i = j + 1
                }
            }
            // Parked stop: the gap before the next segment (Bluetooth was disconnected).
            if (si < segs.size - 1) {
                val gapMs = max(0L, segs[si + 1].start - seg.end)
                stopped += gapMs
                val last = route.size - 1
                val (lat, lon) = if (last >= 0) route.lat[last] to route.lon[last] else {
                    val next = segs[si + 1].data.route
                    if (next.size > 0) next.lat[0] to next.lon[0] else 0.0 to 0.0
                }
                stops += Stop(seg.end, gapMs, lat, lon, parked = true)
            }

            if (route.size > 0) {
                var t = seg.start
                while (t < seg.end) {
                    val slice = min(60_000L, seg.end - t)
                    if (Geo.sunAltitudeDeg(t, route.lat[0], route.lon[0]) < NIGHT_SUN_ALTITUDE) nightMs += slice
                    sampledMs += slice
                    t += slice
                }
            }
        }

        // Cornering: how hard the turns were taken, from windows the sensors measured.
        var sensedMs = 0L
        val turns = ArrayList<Float>()
        for (seg in segs) {
            val p = seg.data.profile
            if (!p.hasSensors) continue
            for (i in 0 until p.size) {
                if (!p.sensed(i)) continue
                sensedMs += min(p.moving[i], p.sensorMs[i])
                if (p.lat[i] >= TURN_LAT) turns += p.lat[i]
            }
        }
        turns.sort()
        val cornering = if (turns.size >= MIN_TURNS) turns[(turns.size * 3 / 4).coerceAtMost(turns.size - 1)] else Float.NaN

        // Top speed comes from GPS speed, the average from distance between positions; at a steady
        // speed the two can differ by about 1%. The top speed is never below the average.
        if (moving > 0) maxSpeed = max(maxSpeed, (distance / (moving / 1000.0)).toFloat())

        val events = brakes + accels
        val per10km = events / max(1.0, distance / 1000.0) * 10
        val fluidity = (100 - 55 * roughness(segs) - min(45.0, 9 * per10km)).roundToInt().coerceIn(0, 100)

        return TripStats(
            start = segs.first().start,
            end = segs.last().end,
            distanceM = distance,
            movingMs = moving,
            idleMs = idle,
            stoppedMs = stopped,
            maxSpeed = maxSpeed,
            stops = stops.sortedBy { it.start },
            elevGain = gain,
            elevLoss = loss,
            altMin = altMin,
            altMax = altMax,
            hardBrakes = brakes,
            hardAccels = accels,
            smoothness = fluidity,
            bandsMs = bands,
            nightShare = if (sampledMs > 0) nightMs.toFloat() / sampledMs else 0f,
            cornering = cornering,
            sensorShare = if (moving > 0) (sensedMs.toFloat() / moving).coerceAtMost(1f) else 0f,
            shape = RouteShape.of(segs.map { it.data.route.lat to it.data.route.lon }, distance),
        )
    }
}
