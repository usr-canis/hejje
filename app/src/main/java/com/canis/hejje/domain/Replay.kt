package com.canis.hejje.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a trip's replay video shows, worked out from the trip alone: the visible route (private
 * places cut out, as in share cards and GPX files), where the sun was, and a few highlights that
 * only appear when the recording supports them.
 */
object Replay {
    /** One route point. [d] is metres along the visible route; [piece] changes where a private place was cut out. */
    class Pt(val lat: Double, val lon: Double, val time: Long, val ele: Float, val piece: Int, val d: Double) {
        var speed = 0.0 // m/s, over about 40 s
        var sunEl = 0.0
        var sunAz = 0.0
    }

    enum class Kind { SUNRISE, SUNSET, TOP, HIGH, STEADY, SLOW }

    /** A highlight at [d] metres. [value] is m/s for speeds, metres for heights; [lengthM] for a steady stretch. */
    data class Highlight(val kind: Kind, val d: Double, val lat: Double, val lon: Double, val time: Long, val value: Double, val lengthM: Double = 0.0)

    const val MAX_HIGHLIGHTS = 4
    private const val MAX_POINTS = 4000
    private const val MIN_CLIMB_M = 100.0
    private const val STEADY_MIN_KMH = 45.0
    private const val STEADY_BAND_KMH = 6.0
    private const val STEADY_MIN_M = 5000.0
    private const val SLOW_WINDOW_M = 1000.0
    private const val SLOW_MAX_KMH = 20.0
    private const val SUN_HORIZON = -0.833

    /** The visible route, thinned to at most a few thousand points, with speed and sun position. */
    fun points(pieces: List<List<Gpx.Point>>): List<Pt> {
        val raw = ArrayList<Pt>()
        var d = 0.0
        pieces.filter { it.size >= 2 }.forEachIndexed { k, piece ->
            piece.forEachIndexed { i, p ->
                if (i > 0) d += Geo.distance(piece[i - 1].lat, piece[i - 1].lon, p.lat, p.lon)
                raw += Pt(p.lat, p.lon, p.time, p.ele, k, d)
            }
        }
        if (raw.size < 2) return raw
        val step = max(20.0, d / MAX_POINTS)
        val out = ArrayList<Pt>()
        for ((i, p) in raw.withIndex()) {
            val last = out.lastOrNull()
            if (last == null || i == raw.lastIndex || p.piece != last.piece || p.d - last.d >= step || raw[i + 1].piece != p.piece) out += p
        }
        for (i in out.indices) {
            val p = out[i]
            p.speed = windowSpeed(out, i)
            val (el, az) = Sun.position(p.lat, p.lon, p.time)
            p.sunEl = el; p.sunAz = az
        }
        return out
    }

    private fun windowSpeed(pts: List<Pt>, i: Int, halfSec: Long = 20): Double {
        var a = i
        var b = i
        val p = pts[i]
        while (a > 0 && pts[a - 1].piece == p.piece && p.time - pts[a - 1].time <= halfSec * 1000) a--
        while (b < pts.lastIndex && pts[b + 1].piece == p.piece && pts[b + 1].time - p.time <= halfSec * 1000) b++
        if (a == b) {
            // Thinned points can be far apart: use the neighbours.
            a = if (i > 0 && pts[i - 1].piece == p.piece) i - 1 else i
            b = if (i < pts.lastIndex && pts[i + 1].piece == p.piece) i + 1 else i
            if (a == b) return 0.0
        }
        val dt = (pts[b].time - pts[a].time) / 1000.0
        return if (dt > 0) (pts[b].d - pts[a].d) / dt else 0.0
    }

    /**
     * The highlights worth showing, at most [MAX_HIGHLIGHTS], ordered along the route. [topSpeed]
     * and the height range come from the trip's own statistics so the video agrees with the app.
     */
    fun highlights(pts: List<Pt>, topSpeed: Float, altMin: Float, altMax: Float): List<Highlight> {
        if (pts.size < 2) return emptyList()
        val found = ArrayList<Highlight>()
        // Sunrise or sunset where the car was when the sun crossed the horizon.
        for (i in 1 until pts.size) {
            val a = pts[i - 1].sunEl
            val b = pts[i].sunEl
            val p = pts[i]
            if (a < SUN_HORIZON && b >= SUN_HORIZON) found += Highlight(Kind.SUNRISE, p.d, p.lat, p.lon, sunCrossing(pts[i - 1], p), 0.0)
            if (a >= SUN_HORIZON && b < SUN_HORIZON) found += Highlight(Kind.SUNSET, p.d, p.lat, p.lon, sunCrossing(pts[i - 1], p), 0.0)
        }
        val fastest = pts.maxBy { it.speed }
        if (topSpeed > 5f && fastest.speed > 3.0) found += Highlight(Kind.TOP, fastest.d, fastest.lat, fastest.lon, fastest.time, topSpeed.toDouble())
        highest(pts, altMin, altMax)?.let { found += it }
        steady(pts)?.let { found += it }
        slowest(pts)?.let { found += it }
        val chosen = found.groupBy { it.kind }.map { it.value.first() }.sortedBy { it.kind.ordinal }.take(MAX_HIGHLIGHTS)
        return chosen.sortedBy { it.d }
    }

    private fun sunCrossing(a: Pt, b: Pt): Long {
        val f = ((SUN_HORIZON - a.sunEl) / (b.sunEl - a.sunEl)).coerceIn(0.0, 1.0)
        return a.time + ((b.time - a.time) * f).toLong()
    }

    private fun highest(pts: List<Pt>, altMin: Float, altMax: Float): Highlight? {
        val ele = pts.map { it.ele.toDouble() }
        if (ele.count { !it.isNaN() } < 5) return null
        // Median of 7 drops single GPS spikes.
        val smooth = ele.indices.map { i -> ele.subList(max(0, i - 3), min(ele.size, i + 4)).filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] } }
        val lo = if (altMin.isFinite()) altMin.toDouble() else smooth.filter { !it.isNaN() }.min()
        val hi = if (altMax.isFinite()) altMax.toDouble() else smooth.filter { !it.isNaN() }.max()
        if (hi - lo < MIN_CLIMB_M) return null
        val i = smooth.indices.filter { !smooth[it].isNaN() }.maxBy { smooth[it] }
        return Highlight(Kind.HIGH, pts[i].d, pts[i].lat, pts[i].lon, pts[i].time, hi)
    }

    private fun steady(pts: List<Pt>): Highlight? {
        var best: Triple<Int, Int, Double>? = null
        var i = 0
        while (i < pts.size) {
            if (pts[i].speed * 3.6 < STEADY_MIN_KMH) { i++; continue }
            var j = i
            var sum = 0.0
            var n = 0
            while (j < pts.size && pts[j].piece == pts[i].piece) {
                val v = pts[j].speed * 3.6
                val mean = (sum + v) / (n + 1)
                if (abs(v - mean) > STEADY_BAND_KMH || v < STEADY_MIN_KMH - 5) break
                sum += v; n++; j++
            }
            if (n > 1 && (best == null || pts[j - 1].d - pts[i].d > pts[best.second].d - pts[best.first].d)) best = Triple(i, j - 1, sum / n)
            i = max(i + 1, j)
        }
        val (a, b, kmh) = best ?: return null
        val length = pts[b].d - pts[a].d
        if (length < STEADY_MIN_M) return null
        val mid = pts[(a + b) / 2]
        return Highlight(Kind.STEADY, mid.d, mid.lat, mid.lon, mid.time, kmh / 3.6, length)
    }

    private fun slowest(pts: List<Pt>): Highlight? {
        var best: Triple<Double, Int, Int>? = null
        for (i in pts.indices) {
            if (pts[i].speed * 3.6 <= 3) continue
            var j = i
            while (j < pts.lastIndex && pts[j].d - pts[i].d < SLOW_WINDOW_M && pts[j + 1].piece == pts[i].piece) j++
            if (pts[j].d - pts[i].d < SLOW_WINDOW_M) continue
            if ((i..j).any { pts[it].speed * 3.6 <= 3 }) continue
            val dur = (pts[j].time - pts[i].time) / 1000.0
            if (dur < 120) continue
            val v = (pts[j].d - pts[i].d) / dur
            if (best == null || v < best.first) best = Triple(v, i, j)
        }
        val (v, a, b) = best ?: return null
        if (v * 3.6 > SLOW_MAX_KMH) return null
        val mid = pts[(a + b) / 2]
        return Highlight(Kind.SLOW, mid.d, mid.lat, mid.lon, mid.time, v)
    }
}

/** Where the sun is (NOAA's formulas): elevation and compass azimuth in degrees. Offline. */
object Sun {
    fun position(lat: Double, lon: Double, epochMs: Long): Pair<Double, Double> {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(125.04 - 1934.136 * t)
        val eps = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60 + 0.00256 * cos(125.04 - 1934.136 * t)
        val decl = asinDeg(sin(eps) * sin(lambda))
        val y = Math.tan(Math.toRadians(eps / 2)).let { it * it }
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m),
        )
        val minutes = (epochMs.mod(86_400_000L)) / 60_000.0
        val trueSolar = (minutes + eqTime + 4 * lon).mod(1440.0)
        val hourAngle = trueSolar / 4 - 180
        val zenith = Math.toDegrees(Math.acos((sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)))
        val az = Math.toDegrees(Math.atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - Math.tan(Math.toRadians(decl)) * cos(lat))) + 180
        return Pair(90 - zenith, az.mod(360.0))
    }

    /** Sine and cosine of an angle in degrees. */
    private fun sin(deg: Double) = Math.sin(Math.toRadians(deg))
    private fun cos(deg: Double) = Math.cos(Math.toRadians(deg))
    private fun asinDeg(x: Double) = Math.toDegrees(Math.asin(x))
}
