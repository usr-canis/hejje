package com.canis.hejje.domain

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Direction from the first point to the second, in degrees clockwise from north (0 until 360). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Metres east of [lon0] on a flat local projection (good for short distances). */
    fun x(lon: Double, lon0: Double, lat0: Double): Double =
        (lon - lon0) * 111_320.0 * cos(Math.toRadians(lat0))

    /** Metres north of [lat0] on a flat local projection. */
    fun y(lat: Double, lat0: Double): Double = (lat - lat0) * 110_540.0

    fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6

    /**
     * Height of the sun above the horizon in degrees (negative at night).
     * Low-precision astronomical formula, accurate to well under a degree.
     */
    fun sunAltitudeDeg(epochMs: Long, lat: Double, lon: Double): Double {
        val d = epochMs / 86_400_000.0 + 2_440_587.5 - 2_451_545.0
        val g = Math.toRadians(norm360(357.529 + 0.98560028 * d))
        val q = norm360(280.459 + 0.98564736 * d)
        val l = Math.toRadians(norm360(q + 1.915 * sin(g) + 0.020 * sin(2 * g)))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstHours = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24
        val hourAngle = Math.toRadians(gmstHours * 15 + lon) - ra
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(hourAngle)))
    }

    private fun norm360(v: Double) = ((v % 360) + 360) % 360
}
