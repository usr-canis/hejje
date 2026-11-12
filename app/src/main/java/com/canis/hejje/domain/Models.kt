package com.canis.hejje.domain

import kotlinx.serialization.Serializable

/** One GPS fix as it arrives from the phone. Missing values are NaN. */
data class RawPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Float = Float.NaN,
    val speed: Float = Float.NaN,
    val acc: Float = Float.NaN,
    val pressure: Float = Float.NaN,
    val speedAcc: Float = Float.NaN,
)

/** Simplified route line. [t] is seconds from the segment start, [v] is speed in m/s. */
@Serializable
class Route(val lat: DoubleArray, val lon: DoubleArray, val t: IntArray, val v: FloatArray) {
    val size: Int get() = lat.size

    /** Fractional index of the position at [tSec] (linear interpolation between points). */
    fun indexAt(tSec: Float): Float {
        if (size == 0) return 0f
        if (tSec <= t[0]) return 0f
        if (tSec >= t[size - 1]) return (size - 1).toFloat()
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (t[mid] <= tSec) lo = mid else hi = mid
        }
        val span = (t[hi] - t[lo]).coerceAtLeast(1)
        return lo + (tSec - t[lo]) / span
    }

    fun latAt(index: Float): Double = lerp(lat, index)
    fun lonAt(index: Float): Double = lerp(lon, index)

    private fun lerp(a: DoubleArray, index: Float): Double {
        if (a.isEmpty()) return 0.0
        val i = index.toInt().coerceIn(0, a.size - 1)
        val j = (i + 1).coerceAtMost(a.size - 1)
        val f = (index - i).coerceIn(0f, 1f)
        return a[i] + (a[j] - a[i]) * f
    }

    companion object {
        val EMPTY = Route(DoubleArray(0), DoubleArray(0), IntArray(0), FloatArray(0))
    }
}

/**
 * Fixed-interval summary of a segment. Every statistic is recomputed from this, so edits
 * (trim, split, merge) stay exact without keeping the raw GPS points.
 * Per window: metres driven, milliseconds moving, top speed, altitude (NaN if unknown) and
 * the sum of squared acceleration x seconds (for the smoothness score).
 */
@Serializable
class Profile(
    val stepMs: Int,
    val dist: FloatArray,
    val moving: IntArray,
    val vmax: FloatArray,
    val alt: FloatArray,
    val a2: FloatArray,
) {
    val size: Int get() = dist.size

    /** Average moving speed in window [i], m/s. */
    fun speed(i: Int): Float = if (moving[i] > 0) dist[i] / (moving[i] / 1000f) else 0f
}

@Serializable
data class DriveEvent(val brake: Boolean, val tMs: Int, val lat: Double, val lon: Double, val peak: Float)

@Serializable
class SegmentData(
    val route: Route,
    val profile: Profile,
    val events: List<DriveEvent> = emptyList(),
    val baro: Boolean = false,
)

/** One continuous recording (Bluetooth connect to disconnect). Times are epoch milliseconds. */
class Segment(val start: Long, val end: Long, val data: SegmentData) {
    val durationMs: Long get() = end - start
}

data class Stop(val start: Long, val durationMs: Long, val lat: Double, val lon: Double, val parked: Boolean)

data class TripStats(
    val start: Long,
    val end: Long,
    val distanceM: Double,
    val movingMs: Long,
    val idleMs: Long,
    val stoppedMs: Long,
    val maxSpeed: Float,
    val stops: List<Stop>,
    val elevGain: Float,
    val elevLoss: Float,
    val altMin: Float,
    val altMax: Float,
    val hardBrakes: Int,
    val hardAccels: Int,
    val smoothness: Int,
    val bandsMs: LongArray,
    val nightShare: Float,
) {
    val totalMs: Long get() = end - start
    val avgMovingSpeed: Float get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
    val stopMs: Long get() = stops.sumOf { it.durationMs }
}

data class Zone(val lat: Double, val lon: Double, val radiusM: Double)
