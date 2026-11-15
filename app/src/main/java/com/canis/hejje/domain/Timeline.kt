package com.canis.hejje.domain

/** One line of a trip's timeline. */
sealed interface TimelineEntry {
    /**
     * A place in the drive: where it started, a stop, or where it ended. [durationMs] is how long a
     * stop lasted.
     */
    data class Point(val kind: Kind, val time: Long, val lat: Double, val lon: Double, val durationMs: Long = 0) : TimelineEntry

    /** The driving between two points, with its own numbers and its line on the map. */
    class Leg(
        val start: Long,
        val end: Long,
        val distanceM: Double,
        val movingMs: Long,
        val avgSpeed: Float,
        val maxSpeed: Float,
        val circuity: Double,
        val lat: DoubleArray,
        val lon: DoubleArray,
    ) : TimelineEntry

    enum class Kind {
        START,

        /** The phone disconnected from the car (engine off) and later connected again. */
        PARKED,

        /** Standing still while the phone stayed connected (engine on: traffic, a signal, a quick drop-off). */
        IDLE,
        END,
    }
}

/**
 * A trip told in order: start, each leg, each stop, end. Every parked stop is listed; idle stops
 * only from [MIN_IDLE_MS], so short waits at signals do not clutter it.
 */
object Timeline {
    const val MIN_IDLE_MS = 120_000L
    private const val MIN_LEG_M = 20.0

    fun of(segments: List<Segment>, stops: List<Stop>): List<TimelineEntry> {
        val segs = segments.sortedBy { it.start }.filter { it.data.route.size > 0 }
        if (segs.isEmpty()) return emptyList()
        val first = segs.first().data.route
        val last = segs.last().data.route
        val out = ArrayList<TimelineEntry>()
        out += TimelineEntry.Point(TimelineEntry.Kind.START, segs.first().start, first.lat[0], first.lon[0])
        var legStart = segs.first().start
        for (stop in stops.filter { it.parked || it.durationMs >= MIN_IDLE_MS }.sortedBy { it.start }) {
            leg(segs, legStart, stop.start)?.let { out += it }
            out += TimelineEntry.Point(
                if (stop.parked) TimelineEntry.Kind.PARKED else TimelineEntry.Kind.IDLE, stop.start, stop.lat, stop.lon, stop.durationMs,
            )
            legStart = stop.start + stop.durationMs
        }
        leg(segs, legStart, segs.last().end)?.let { out += it }
        out += TimelineEntry.Point(TimelineEntry.Kind.END, segs.last().end, last.lat[last.size - 1], last.lon[last.size - 1])
        return out
    }

    private fun leg(segments: List<Segment>, from: Long, to: Long): TimelineEntry.Leg? {
        if (to <= from) return null
        val part = TripEditor.trim(segments, from, to)
        if (part.isEmpty()) return null
        val s = StatsCalculator.compute(part)
        if (s.distanceM < MIN_LEG_M) return null
        val lat = part.flatMap { it.data.route.lat.asList() }.toDoubleArray()
        val lon = part.flatMap { it.data.route.lon.asList() }.toDoubleArray()
        return TimelineEntry.Leg(from, to, s.distanceM, s.movingMs, s.avgMovingSpeed, s.maxSpeed, s.shape.circuity, lat, lon)
    }
}
