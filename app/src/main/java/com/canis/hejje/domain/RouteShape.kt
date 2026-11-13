package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How directly and how twistily a route goes.
 *
 * - Circuity: distance driven ÷ straight line from start to end (1.00 = dead straight). Not given
 *   for a round trip, where start and end are close together.
 * - Twistiness: how much the road turns, in degrees per km. Measured on the route resampled every
 *   20 m and smoothed over about 180 m, so GPS wobble does not count as turning.
 */
data class RouteShape(val straightM: Double, val circuity: Double, val twistiness: Double) {
    val roundTrip: Boolean get() = circuity.isNaN() && straightM >= 0

    companion object {
        /** Twistiness labels: up to [STRAIGHT_MAX] °/km is straight, up to [MODERATE_MAX] moderate, beyond that winding. */
        const val STRAIGHT_MAX = 80.0
        const val MODERATE_MAX = 300.0
        private const val STEP_M = 20.0
        private const val SMOOTH = 4
        private const val MIN_TWIST_LENGTH_M = 300.0
        private const val ROUND_TRIP_M = 300.0
        private const val ROUND_TRIP_SHARE = 0.15

        val NONE = RouteShape(-1.0, Double.NaN, Double.NaN)

        /**
         * [pieces]: the route's continuous pieces as (lat, lon); [drivenM]: the distance driven.
         * [roundTrips] false: always compare with the straight line (a stretch between two chosen points).
         */
        fun of(pieces: List<Pair<DoubleArray, DoubleArray>>, drivenM: Double, roundTrips: Boolean = true): RouteShape {
            val drawn = pieces.filter { it.first.isNotEmpty() }
            if (drawn.isEmpty()) return NONE
            val (firstLat, firstLon) = drawn.first()
            val (lastLat, lastLon) = drawn.last()
            val straight = Geo.distance(firstLat[0], firstLon[0], lastLat[lastLat.size - 1], lastLon[lastLon.size - 1])
            val round = if (roundTrips) straight < max(ROUND_TRIP_M, ROUND_TRIP_SHARE * drivenM) else straight < 1.0
            val circuity = if (round) Double.NaN else max(1.0, drivenM / straight)
            return RouteShape(straight, circuity, twistiness(drawn))
        }

        private fun twistiness(pieces: List<Pair<DoubleArray, DoubleArray>>): Double {
            var turned = 0.0
            var length = 0.0
            for ((lat, lon) in pieces) {
                if (lat.size < 2) continue
                val lat0 = lat[0]
                val lon0 = lon[0]
                val xs = DoubleArray(lat.size) { Geo.x(lon[it], lon0, lat0) }
                val ys = DoubleArray(lat.size) { Geo.y(lat[it], lat0) }
                val (rx, ry) = resample(xs, ys)
                if (rx.size < 3) continue
                val n = rx.size
                val sx = DoubleArray(n)
                val sy = DoubleArray(n)
                for (i in 0 until n) {
                    val from = max(0, i - SMOOTH)
                    val to = min(n - 1, i + SMOOTH)
                    var ax = 0.0
                    var ay = 0.0
                    for (j in from..to) { ax += rx[j]; ay += ry[j] }
                    sx[i] = ax / (to - from + 1)
                    sy[i] = ay / (to - from + 1)
                }
                var lastHeading = Double.NaN
                for (i in 1 until n) {
                    val dx = sx[i] - sx[i - 1]
                    val dy = sy[i] - sy[i - 1]
                    if (dx * dx + dy * dy < 1e-6) continue
                    val h = Math.toDegrees(atan2(dy, dx))
                    if (!lastHeading.isNaN()) {
                        var d = h - lastHeading
                        while (d > 180) d -= 360
                        while (d < -180) d += 360
                        turned += abs(d)
                    }
                    lastHeading = h
                }
                length += (n - 1) * STEP_M
            }
            return if (length >= MIN_TWIST_LENGTH_M) turned / (length / 1000.0) else Double.NaN
        }

        /** Points every [STEP_M] along the line. */
        private fun resample(xs: DoubleArray, ys: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val ox = ArrayList<Double>()
            val oy = ArrayList<Double>()
            ox += xs[0]; oy += ys[0]
            var carry = 0.0 // distance along the current edge where the next sample falls
            for (i in 1 until xs.size) {
                val dx = xs[i] - xs[i - 1]
                val dy = ys[i] - ys[i - 1]
                val len = sqrt(dx * dx + dy * dy)
                if (len <= 0) continue
                var at = STEP_M - carry
                while (at <= len) {
                    ox += xs[i - 1] + dx * at / len
                    oy += ys[i - 1] + dy * at / len
                    at += STEP_M
                }
                carry = len - (at - STEP_M)
            }
            return ox.toDoubleArray() to oy.toDoubleArray()
        }
    }
}
