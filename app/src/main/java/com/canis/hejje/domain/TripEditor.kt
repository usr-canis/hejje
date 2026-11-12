package com.canis.hejje.domain

import kotlin.math.roundToInt

/** Manual edits. All of them work on stored segments and stay exact because stats come from the profile. */
object TripEditor {
    /** Splits one segment at time [at] (snapped to the profile grid). A side is null when it would be empty. */
    fun splitSegment(s: Segment, at: Long): Pair<Segment?, Segment?> {
        val p = s.data.profile
        val k = ((at - s.start).toDouble() / p.stepMs).roundToInt()
        if (k <= 0) return null to s
        if (k >= p.size) return s to null
        val cutMs = k * p.stepMs
        val cutSec = cutMs / 1000
        val cut = s.start + cutMs

        val r = s.data.route
        val idx = r.indexAt(cutSec.toFloat())
        val cutLat = Geo.round6(r.latAt(idx))
        val cutLon = Geo.round6(r.lonAt(idx))
        val i0 = idx.toInt().coerceIn(0, (r.size - 1).coerceAtLeast(0))
        val cutV = if (r.size == 0) 0f else r.v[i0] + (r.v[(i0 + 1).coerceAtMost(r.size - 1)] - r.v[i0]) * (idx - i0)
        val before = (0 until r.size).filter { r.t[it] < cutSec }
        val after = (0 until r.size).filter { r.t[it] > cutSec }
        fun route(ix: List<Int>, cutFirst: Boolean, shift: Int): Route {
            if (r.size == 0) return Route.EMPTY
            val n = ix.size + 1
            fun <T> pick(i: Int, cutValue: T, get: (Int) -> T): T =
                if (cutFirst) (if (i == 0) cutValue else get(ix[i - 1])) else (if (i == n - 1) cutValue else get(ix[i]))
            return Route(
                DoubleArray(n) { pick(it, cutLat) { j -> r.lat[j] } },
                DoubleArray(n) { pick(it, cutLon) { j -> r.lon[j] } },
                IntArray(n) { pick(it, cutSec) { j -> r.t[j] } - shift },
                FloatArray(n) { pick(it, cutV) { j -> r.v[j] } },
            )
        }
        fun profile(from: Int, to: Int) = Profile(
            p.stepMs, p.dist.copyOfRange(from, to), p.moving.copyOfRange(from, to),
            p.vmax.copyOfRange(from, to), p.alt.copyOfRange(from, to), p.a2.copyOfRange(from, to),
        )
        val first = Segment(
            s.start, cut,
            SegmentData(route(before, cutFirst = false, shift = 0), profile(0, k), s.data.events.filter { it.tMs < cutMs }, s.data.baro),
        )
        val second = Segment(
            cut, s.end,
            SegmentData(
                route(after, cutFirst = true, shift = cutSec), profile(k, p.size),
                s.data.events.filter { it.tMs >= cutMs }.map { it.copy(tMs = it.tMs - cutMs) }, s.data.baro,
            ),
        )
        return first to second
    }

    /** Splits a trip into the part before [at] and the part after it. */
    fun split(segments: List<Segment>, at: Long): Pair<List<Segment>, List<Segment>> {
        val a = ArrayList<Segment>()
        val b = ArrayList<Segment>()
        for (s in segments.sortedBy { it.start }) {
            when {
                s.end <= at -> a += s
                s.start >= at -> b += s
                else -> {
                    val (x, y) = splitSegment(s, at)
                    x?.let { a += it }
                    y?.let { b += it }
                }
            }
        }
        return a to b
    }

    /** Keeps only the part of the trip between [newStart] and [newEnd]. */
    fun trim(segments: List<Segment>, newStart: Long, newEnd: Long): List<Segment> =
        split(split(segments, newStart).second, newEnd).first

    fun merge(a: List<Segment>, b: List<Segment>): List<Segment> = (a + b).sortedBy { it.start }

    /** Whether a new recording starting at [newStart] continues the trip that ended at [lastEnd]. */
    fun continuesTrip(lastEnd: Long, newStart: Long, mergeGapMs: Long): Boolean =
        mergeGapMs > 0 && newStart >= lastEnd && newStart - lastEnd <= mergeGapMs
}
