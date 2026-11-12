package com.canis.hejje.domain

/** Hides the parts of a route that lie inside the user's privacy zones. */
object PrivacyZones {
    fun inside(lat: Double, lon: Double, zones: List<Zone>): Boolean =
        zones.any { Geo.distance(lat, lon, it.lat, it.lon) <= it.radiusM }

    /**
     * Returns the visible pieces of [route]. Each piece is cut exactly on the zone boundary, so
     * nothing inside a zone remains, not even the last metres towards it.
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
        fun add(la: Double, lo: Double, time: Int, speed: Float) { lat += la; lon += lo; t += time; v += speed }
        fun addCrossing(i: Int, j: Int, iInside: Boolean) {
            // Binary search for the point where the line i -> j crosses the zone boundary.
            var a = 0.0
            var b = 1.0
            repeat(24) {
                val mid = (a + b) / 2
                val la = route.lat[i] + (route.lat[j] - route.lat[i]) * mid
                val lo = route.lon[i] + (route.lon[j] - route.lon[i]) * mid
                if (inside(la, lo, zones) == iInside) a = mid else b = mid
            }
            // Take the end of the search interval that lies outside the zone.
            val f = if (iInside) b else a
            add(
                Geo.round6(route.lat[i] + (route.lat[j] - route.lat[i]) * f),
                Geo.round6(route.lon[i] + (route.lon[j] - route.lon[i]) * f),
                (route.t[i] + (route.t[j] - route.t[i]) * f).toInt(),
                route.v[i] + (route.v[j] - route.v[i]) * f.toFloat(),
            )
        }
        var prevInside = inside(route.lat[0], route.lon[0], zones)
        if (!prevInside) add(route.lat[0], route.lon[0], route.t[0], route.v[0])
        for (i in 1 until route.size) {
            val nowInside = inside(route.lat[i], route.lon[i], zones)
            if (prevInside != nowInside) {
                addCrossing(i - 1, i, prevInside)
                if (nowInside) flush()
            }
            if (!nowInside) add(route.lat[i], route.lon[i], route.t[i], route.v[i])
            prevInside = nowInside
        }
        flush()
        return pieces
    }
}
