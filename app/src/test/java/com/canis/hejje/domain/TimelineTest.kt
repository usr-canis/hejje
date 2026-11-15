package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test fun twoLegsWithAParkedStop_andAShortSignalWaitLeftOut() {
        // Drive 1: 10 min with a 40 s wait at a signal and a 3 min idle (e.g. waiting for someone).
        val a = DriveSim()
        a.drive(240, 14f).drive(8, 0f, accel = 3f).idle(40).drive(200, 14f).drive(8, 0f, accel = 3f).idle(180).drive(150, 12f)
        val first = a.segment()
        // Engine off for 20 minutes, then drive 2.
        val b = DriveSim(t = a.t + 20 * 60_000L, lat = a.lat, lon = a.lon).drive(300, 16f)
        val segments = listOf(first, b.segment())
        val stats = StatsCalculator.compute(segments)
        val entries = Timeline.of(segments, stats.stops)

        val kinds = entries.map { if (it is TimelineEntry.Point) it.kind.name else "LEG" }
        assertEquals(listOf("START", "LEG", "IDLE", "LEG", "PARKED", "LEG", "END"), kinds)
        val idle = entries.filterIsInstance<TimelineEntry.Point>().single { it.kind == TimelineEntry.Kind.IDLE }
        assertTrue("idle lasted ${idle.durationMs} ms", idle.durationMs in 170_000L..200_000L)
        val parked = entries.filterIsInstance<TimelineEntry.Point>().single { it.kind == TimelineEntry.Kind.PARKED }
        assertEquals(20 * 60_000.0, parked.durationMs.toDouble(), 10_000.0)
        // The legs add up to the whole trip.
        val legs = entries.filterIsInstance<TimelineEntry.Leg>()
        assertEquals(stats.distanceM, legs.sumOf { it.distanceM }, stats.distanceM * 0.02)
        assertTrue(legs.all { it.maxSpeed >= it.avgSpeed && it.lat.size >= 2 })
    }
}
