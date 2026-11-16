package com.canis.hejje.domain

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Hides the parts of a route that lie inside the user's privacy zones. */
object PrivacyZones {
    fun inside(lat: Double, lon: Double, zones: List<Zone>): Boolean =
        zones.any { Geo.distance(lat, lon, it.lat, it.lon) <= it.radiusM }

    /** Cuts sit this far outside a zone's edge, so rounding never leaves a line just inside it. */
    private const val MARGIN_M = 1.0

    /**
     * Returns the visible pieces of [route]. Every stretch between two points is clipped against
     * every zone, so a straight stretch that passes through a zone is cut even when both of its
     * ends lie outside, and no piece's line runs inside a zone. Overlapping zones count as one
     * hidden area; cuts sit just outside the boundary.
     */
    fun trim(route: Route, zones: List<Zone>): List<Route> {
        if (zones.isEmpty() || route.size == 0) return listOf(route)
        val pieces = ArrayList<Route>()
        var lat = ArrayList<Double>()
        var lon = ArrayList<Double>()
        var t = ArrayList<Int>()
        var v = ArrayList<Float>()
        fun flush() {
            if (lat.size >= 2) pieces += Route(lat.toDoubleArray(), lon.toDoubleArray(), t.toIntArray(), v.toFloatArray())
            lat = ArrayList(); lon = ArrayList(); t = ArrayList(); v = ArrayList()
        }
        /** The point at fraction [f] of the stretch from point [i] to the next. */
        fun add(i: Int, f: Double) {
            when (f) {
                0.0 -> { lat += route.lat[i]; lon += route.lon[i]; t += route.t[i]; v += route.v[i] }
                1.0 -> { lat += route.lat[i + 1]; lon += route.lon[i + 1]; t += route.t[i + 1]; v += route.v[i + 1] }
                else -> {
                    lat += Geo.round6(route.lat[i] + (route.lat[i + 1] - route.lat[i]) * f)
                    lon += Geo.round6(route.lon[i] + (route.lon[i + 1] - route.lon[i]) * f)
                    t += (route.t[i] + (route.t[i + 1] - route.t[i]) * f).toInt()
                    v += route.v[i] + (route.v[i + 1] - route.v[i]) * f.toFloat()
                }
            }
        }
        for (i in 0 until route.size - 1) {
            val visible = visibleParts(route, i, zones)
            if (visible.isEmpty()) {
                flush()
                continue
            }
            for ((a, b) in visible) {
                if (a > 0.0) {
                    // Coming out of a hidden part: a new piece starts at the boundary.
                    flush()
                    add(i, a)
                } else if (lat.isEmpty()) {
                    add(i, 0.0)
                }
                if (b < 1.0) {
                    // Going into a hidden part: this piece ends at the boundary.
                    add(i, b)
                    flush()
                } else {
                    add(i, 1.0)
                }
            }
        }
        flush()
        return pieces
    }

    /**
     * The visible fractions [a, b] (0 = point [i], 1 = the next point) of one stretch: what is left
     * after taking out where the straight line between the points runs inside any zone.
     */
    private fun visibleParts(route: Route, i: Int, zones: List<Zone>): List<Pair<Double, Double>> {
        val hidden = ArrayList<Pair<Double, Double>>()
        for (z in zones) {
            // On a flat map around the zone's centre (metres, on the same sphere as [inside]),
            // solve |p0 + f (p1 - p0)| = r for f.
            val kx = METRES_PER_DEGREE * cos(Math.toRadians(z.lat))
            val x0 = (route.lon[i] - z.lon) * kx
            val y0 = (route.lat[i] - z.lat) * METRES_PER_DEGREE
            val dx = (route.lon[i + 1] - route.lon[i]) * kx
            val dy = (route.lat[i + 1] - route.lat[i]) * METRES_PER_DEGREE
            val r = z.radiusM + MARGIN_M
            val a = dx * dx + dy * dy
            val c = x0 * x0 + y0 * y0 - r * r
            if (a < 1e-9) {
                if (c <= 0) hidden += 0.0 to 1.0
                continue
            }
            val b = 2 * (x0 * dx + y0 * dy)
            val disc = b * b - 4 * a * c
            // Missing the zone, or only touching its edge, hides nothing.
            if (disc <= 0) continue
            val root = sqrt(disc)
            val lo = max(0.0, (-b - root) / (2 * a))
            val hi = min(1.0, (-b + root) / (2 * a))
            if (lo < hi) hidden += lo to hi
        }
        if (hidden.isEmpty()) return listOf(0.0 to 1.0)
        hidden.sortBy { it.first }
        val visible = ArrayList<Pair<Double, Double>>()
        var from = 0.0
        for ((lo, hi) in hidden) {
            if (lo > from + EPSILON) visible += from to lo
            from = max(from, hi)
        }
        if (from < 1.0 - EPSILON) visible += from to 1.0
        return visible
    }

    private const val EPSILON = 1e-9

    /** Metres per degree of latitude on the sphere [Geo.distance] uses. */
    private const val METRES_PER_DEGREE = 6_371_000.0 * Math.PI / 180
}
