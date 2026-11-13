package com.canis.hejje.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A trip's recorded pieces, for finding stretches. */
class TripTrack(val tripId: Long, val segments: List<Segment>)

/** One drive from A to B. [start] is when the car passed A (epoch ms). */
class StretchPass(
    val tripId: Long,
    val start: Long,
    val durationMs: Long,
    val distanceM: Double,
    val maxSpeed: Float,
    val stoppedMs: Long,
    val hardBrakes: Int,
    val lat: DoubleArray,
    val lon: DoubleArray,
) {
    /** Circuity and twistiness of this drive along the stretch. */
    val shape: RouteShape by lazy { RouteShape.of(listOf(lat to lon), distanceM, roundTrips = false) }

    /** When the car left A and reached B (epoch ms): the section of the trip this drive covers. */
    val end: Long get() = start + durationMs

    val avgSpeed: Float get() = if (durationMs > 0) (distanceM / (durationMs / 1000.0)).toFloat() else 0f
}

/** All drives along a stretch, newest first, with the typical, fastest and slowest. */
class StretchSummary(
    val passes: List<StretchPass>,
    val typicalMs: Long,
    val fastest: StretchPass,
    val slowest: StretchPass,
    val distanceM: Double,
    /** Hour of the day (0-23) with the shortest and the longest typical time, if that differs by a minute or more. */
    val bestHour: Pair<Int, Long>?,
    val worstHour: Pair<Int, Long>?,
    /** The typical (median) circuity and twistiness of the drives. */
    val shape: RouteShape = RouteShape.NONE,
)

/**
 * Finds every drive that went from point A to point B, using the stored routes, so it works for
 * all trips ever recorded. A drive counts when its route passes within [MATCH_M] of A and later of
 * B in the same recording. The time runs from leaving A (not from waiting there, e.g. at a red
 * light or before setting off) to first reaching B.
 */
object Stretches {
    const val MATCH_M = 40.0
    /** A and B must be at least this far apart. */
    const val MIN_GAP_M = 2 * MATCH_M
    private const val SAME_SPOT_M = 5.0
    private const val MAX_PASS_MS = 3 * 3_600_000L

    /** The nearest point on any driven route to ([lat], [lon]) within [maxM] metres, or null. */
    fun snap(lat: Double, lon: Double, tracks: List<TripTrack>, maxM: Double): Pair<Double, Double>? {
        var best = maxM
        var out: Pair<Double, Double>? = null
        for (track in tracks) for (seg in track.segments) {
            val r = seg.data.route
            if (r.size == 0) continue
            if (r.size == 1) {
                val d = Geo.distance(lat, lon, r.lat[0], r.lon[0])
                if (d <= best) { best = d; out = r.lat[0] to r.lon[0] }
                continue
            }
            for (e in 0 until r.size - 1) {
                val (d, u) = closest(r, e, lat, lon)
                if (d <= best) {
                    best = d
                    out = (r.lat[e] + u * (r.lat[e + 1] - r.lat[e])) to (r.lon[e] + u * (r.lon[e + 1] - r.lon[e]))
                }
            }
        }
        return out
    }

    fun find(aLat: Double, aLon: Double, bLat: Double, bLon: Double, tracks: List<TripTrack>): List<StretchPass> {
        val all = ArrayList<StretchPass>()
        for (track in tracks) for (seg in track.segments) all += passes(track.tripId, seg, aLat, aLon, bLat, bLon)
        if (all.isEmpty()) return all
        // A drive that went somewhere else between A and B is not this stretch: keep only routes of a
        // similar length to the shortest one (which still allows a different road between the two).
        val shortest = all.minOf { it.distanceM }
        val limit = max(shortest * 1.5, shortest + 1_000)
        return all.filter { it.distanceM <= limit }.sortedByDescending { it.start }
    }

    fun summarize(passes: List<StretchPass>, zone: ZoneId = ZoneId.systemDefault()): StretchSummary? {
        if (passes.isEmpty()) return null
        val byHour = passes.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).hour }
            .filterValues { it.size >= 2 }
            .mapValues { (_, list) -> median(list.map { it.durationMs }) }
        val best = byHour.minByOrNull { it.value }?.toPair()
        val worst = byHour.maxByOrNull { it.value }?.toPair()
        val spread = best != null && worst != null && worst.second - best.second >= 60_000
        return StretchSummary(
            passes = passes,
            typicalMs = median(passes.map { it.durationMs }),
            fastest = passes.minBy { it.durationMs },
            slowest = passes.maxBy { it.durationMs },
            distanceM = passes.map { it.distanceM }.sorted()[passes.size / 2],
            bestHour = if (spread) best else null,
            worstHour = if (spread) worst else null,
            shape = typicalShape(passes.map { it.shape }),
        )
    }

    private fun typicalShape(shapes: List<RouteShape>): RouteShape {
        fun mid(v: List<Double>) = v.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        val straight = shapes.map { it.straightM }.filter { it >= 0 }
        return RouteShape(if (straight.isEmpty()) -1.0 else mid(straight), mid(shapes.map { it.circuity }), mid(shapes.map { it.twistiness }))
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Distance (m) from the point to edge [e] of the route, and where along the edge (0..1) it is closest. */
    private fun closest(r: Route, e: Int, lat: Double, lon: Double): Pair<Double, Double> {
        val ax = Geo.x(r.lon[e], lon, lat)
        val ay = Geo.y(r.lat[e], lat)
        val bx = Geo.x(r.lon[e + 1], lon, lat)
        val by = Geo.y(r.lat[e + 1], lat)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val u = if (len2 <= 1e-9) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + u * dx
        val py = ay + u * dy
        return sqrt(px * px + py * py) to u
    }

    private class Visit(val edge: Int, val u: Double, val tSec: Double, val atA: Boolean)

    /**
     * Each time the route comes near the point. When the car lingers there (waiting, or parked at
     * the start), [leaving] picks the moment it moves on, otherwise the moment it arrives.
     */
    private fun visits(r: Route, lat: Double, lon: Double, leaving: Boolean): List<Visit> {
        val n = r.size - 1
        if (n < 1) return emptyList()
        val d = DoubleArray(n)
        val u = DoubleArray(n)
        for (e in 0 until n) closest(r, e, lat, lon).let { d[e] = it.first; u[e] = it.second }
        val out = ArrayList<Visit>()
        var e = 0
        while (e < n) {
            if (d[e] > MATCH_M) { e++; continue }
            var end = e
            while (end + 1 < n && d[end + 1] <= MATCH_M) end++
            var nearest = Double.MAX_VALUE
            for (i in e..end) nearest = min(nearest, d[i])
            val pick = if (leaving) (end downTo e).first { d[it] <= nearest + SAME_SPOT_M } else (e..end).first { d[it] <= nearest + SAME_SPOT_M }
            val t = r.t[pick] + u[pick] * (r.t[pick + 1] - r.t[pick])
            out += Visit(pick, u[pick], t, leaving)
            e = end + 1
        }
        return out
    }

    private fun passes(tripId: Long, seg: Segment, aLat: Double, aLon: Double, bLat: Double, bLon: Double): List<StretchPass> {
        val r = seg.data.route
        if (r.size < 2) return emptyList()
        val events = (visits(r, aLat, aLon, leaving = true) + visits(r, bLat, bLon, leaving = false)).sortedBy { it.tSec }
        if (events.none { it.atA } || events.none { !it.atA }) return emptyList()
        val cum = DoubleArray(r.size)
        for (i in 1 until r.size) cum[i] = cum[i - 1] + Geo.distance(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i])
        fun along(v: Visit) = cum[v.edge] + v.u * (cum[v.edge + 1] - cum[v.edge])

        val out = ArrayList<StretchPass>()
        var lastA: Visit? = null
        for (v in events) {
            if (v.atA) { lastA = v; continue }
            val a = lastA ?: continue
            lastA = null
            val durationMs = ((v.tSec - a.tSec) * 1000).toLong()
            val distance = along(v) - along(a)
            if (durationMs <= 0 || durationMs > MAX_PASS_MS || distance <= 0) continue
            out += build(tripId, seg, a, v, durationMs, distance)
        }
        return out
    }

    private fun build(tripId: Long, seg: Segment, a: Visit, b: Visit, durationMs: Long, distance: Double): StretchPass {
        val r = seg.data.route
        // The stretch's own line: from the point at A, through the route, to the point at B.
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        fun at(edge: Int, u: Double) {
            lat += r.lat[edge] + u * (r.lat[edge + 1] - r.lat[edge])
            lon += r.lon[edge] + u * (r.lon[edge + 1] - r.lon[edge])
        }
        at(a.edge, a.u)
        for (i in a.edge + 1..b.edge) { lat += r.lat[i]; lon += r.lon[i] }
        at(b.edge, b.u)

        // Top speed, standing time and hard braking from the 5-second record within the stretch.
        val p = seg.data.profile
        val fromMs = a.tSec * 1000
        val toMs = b.tSec * 1000
        var stopped = 0.0
        var top = 0f
        for (i in 0 until p.size) {
            val ws = i.toDouble() * p.stepMs
            val overlap = min(ws + p.stepMs, toMs) - max(ws, fromMs)
            if (overlap <= 0) continue
            stopped += (p.stepMs - p.moving[i]).coerceAtLeast(0) * (overlap / p.stepMs)
            if (overlap >= p.stepMs / 2.0) top = max(top, p.vmax[i])
        }
        for (i in a.edge + 1..b.edge) top = max(top, r.v[i])
        val brakes = seg.data.events.count { it.brake && it.tMs >= fromMs && it.tMs <= toMs }
        return StretchPass(
            tripId = tripId,
            start = seg.start + fromMs.toLong(),
            durationMs = durationMs,
            distanceM = distance,
            maxSpeed = top,
            stoppedMs = stopped.toLong(),
            hardBrakes = brakes,
            lat = lat.toDoubleArray(),
            lon = lon.toDoubleArray(),
        )
    }
}
