package com.canis.hejje.domain

import kotlin.math.abs

/**
 * Finds hard braking and hard acceleration from GPS speed alone.
 *
 * GPS speed is noisy, so an event is only reported when all of these hold:
 *  - the fixes are good (accuracy within limits) and evenly spaced, with no gaps;
 *  - a single outlier cannot trigger it (speeds pass through a median-of-three filter);
 *  - the change is measured over about two seconds, not between neighbouring fixes;
 *  - it is sustained over at least two consecutive measurements;
 *  - the car was actually driving (above walking pace) and the value is physically plausible.
 */
object HarshEventDetector {
    private const val MAX_ACC_M = 25f
    private const val MAX_SPEED_ACC = 2.0f
    private const val MAX_STEP_MS = 2_600L
    private const val MIN_SPAN_MS = 1_500L
    private const val MAX_SPAN_MS = 3_600L
    private const val MIN_DRIVING_SPEED = 4.2f // ~15 km/h
    private const val MAX_PLAUSIBLE = 11f // m/s^2, beyond what a road car can do
    private const val MERGE_MS = 4_000L

    fun detect(points: List<RawPoint>, start: Long, brakeThreshold: Float, accelThreshold: Float): List<DriveEvent> {
        val s = points.filter {
            !it.speed.isNaN() && (it.acc.isNaN() || it.acc <= MAX_ACC_M) &&
                (it.speedAcc.isNaN() || it.speedAcc <= MAX_SPEED_ACC)
        }
        val n = s.size
        if (n < 4) return emptyList()
        val v = FloatArray(n) { i ->
            val a = if (i > 0 && s[i].t - s[i - 1].t <= MAX_STEP_MS) s[i - 1].speed else s[i].speed
            val c = if (i < n - 1 && s[i + 1].t - s[i].t <= MAX_STEP_MS) s[i + 1].speed else s[i].speed
            median3(a, s[i].speed, c)
        }
        // Acceleration over a ~2 s baseline; NaN where there is no clean baseline.
        val acc = FloatArray(n) { Float.NaN }
        val from = FloatArray(n)
        var contiguousFrom = 0
        for (i in 1 until n) {
            if (s[i].t - s[i - 1].t > MAX_STEP_MS) contiguousFrom = i
            var j = i - 1
            while (j > contiguousFrom && s[i].t - s[j].t < MIN_SPAN_MS) j--
            val span = s[i].t - s[j].t
            if (j >= contiguousFrom && span in MIN_SPAN_MS..MAX_SPAN_MS) {
                acc[i] = (v[i] - v[j]) / (span / 1000f)
                from[i] = v[j]
            }
        }
        val events = ArrayList<DriveEvent>()
        var i = 1
        while (i < n) {
            val a = acc[i]
            val brake = !a.isNaN() && a <= -brakeThreshold && from[i] >= MIN_DRIVING_SPEED
            val accel = !a.isNaN() && a >= accelThreshold && v[i] >= MIN_DRIVING_SPEED
            if (!brake && !accel) {
                i++
                continue
            }
            var end = i
            var peakIdx = i
            while (end + 1 < n && s[end + 1].t - s[end].t <= MAX_STEP_MS && sameKind(acc[end + 1], brake, brakeThreshold, accelThreshold)) {
                end++
                if (abs(acc[end]) > abs(acc[peakIdx])) peakIdx = end
            }
            val sustained = end > i
            val peak = abs(acc[peakIdx])
            if (sustained && peak <= MAX_PLAUSIBLE) {
                val p = s[peakIdx]
                val tMs = (p.t - start).toInt()
                val last = events.lastOrNull()
                if (last != null && last.brake == brake && tMs - last.tMs <= MERGE_MS) {
                    if (peak > last.peak) events[events.size - 1] = last.copy(peak = peak)
                } else {
                    events += DriveEvent(brake, tMs, Geo.round6(p.lat), Geo.round6(p.lon), peak)
                }
            }
            i = end + 1
        }
        return events
    }

    private fun sameKind(a: Float, brake: Boolean, brakeThr: Float, accelThr: Float): Boolean =
        !a.isNaN() && if (brake) a <= -brakeThr else a >= accelThr

    fun median3(a: Float, b: Float, c: Float): Float = maxOf(minOf(a, b), minOf(maxOf(a, b), c))
}
