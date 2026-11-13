package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Combines hard braking/acceleration found from GPS speed with what the phone's sensors felt.
 *
 *  - A GPS event the sensors measured but did not feel is GPS noise and is dropped.
 *  - A GPS event without sensor data around it is kept (GPS only, as before).
 *  - A hard stop or launch the sensors felt clearly for two fixes in a row, that GPS missed
 *    (for example in a tunnel or with a poor fix), is added.
 */
object SensorEvents {
    private const val CONFIRM_MS = 3_000L
    private const val CONFIRM_SHARE = 0.7f
    private const val MIN_SENSED_FIXES = 3
    private const val NEARBY_MS = 4_000
    private const val MAX_STEP_MS = 2_600L
    private const val MIN_DRIVING_SPEED = 4.2f
    private const val MAX_PLAUSIBLE = 11f

    fun fuse(gps: List<DriveEvent>, pts: List<RawPoint>, speeds: FloatArray, start: Long, brakeThr: Float, accelThr: Float): List<DriveEvent> {
        val kept = gps.filter { e ->
            val t = start + e.tMs
            val near = pts.filter { abs(it.t - t) <= CONFIRM_MS && !it.sMin.isNaN() }
            if (near.size < MIN_SENSED_FIXES) true
            else if (e.brake) near.minOf { it.sMin } <= -CONFIRM_SHARE * brakeThr
            else near.maxOf { it.sMax } >= CONFIRM_SHARE * accelThr
        }
        val found = ArrayList<DriveEvent>()
        var i = 0
        while (i < pts.size) {
            val kind = kindAt(pts[i], speeds[i], brakeThr, accelThr)
            if (kind == null) { i++; continue }
            var end = i
            while (end + 1 < pts.size && pts[end + 1].t - pts[end].t <= MAX_STEP_MS &&
                kindAt(pts[end + 1], speeds[end + 1], brakeThr, accelThr) == kind) end++
            if (end > i) {
                var peakIdx = i
                for (j in i..end) if (peak(pts[j], kind) > peak(pts[peakIdx], kind)) peakIdx = j
                val p = pts[peakIdx]
                val tMs = (p.t - start).toInt()
                val value = min(peak(p, kind), MAX_PLAUSIBLE)
                if (gps.none { it.brake == kind && abs(it.tMs - tMs) <= NEARBY_MS }) {
                    found += DriveEvent(kind, tMs, Geo.round6(p.lat), Geo.round6(p.lon), value)
                }
            }
            i = end + 1
        }
        return (kept + found).sortedBy { it.tMs }
    }

    /** true = braking, false = accelerating, null = neither, judged on the mean over the interval. */
    private fun kindAt(p: RawPoint, speed: Float, brakeThr: Float, accelThr: Float): Boolean? = when {
        p.sLon.isNaN() || max(speed, if (p.speed.isNaN()) 0f else p.speed) < MIN_DRIVING_SPEED -> null
        p.sLon <= -brakeThr -> true
        p.sLon >= accelThr -> false
        else -> null
    }

    private fun peak(p: RawPoint, brake: Boolean) = if (brake) -p.sMin else p.sMax
}
