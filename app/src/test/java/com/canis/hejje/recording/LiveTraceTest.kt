package com.canis.hejje.recording

import com.canis.hejje.domain.DriveSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTraceTest {
    @Test fun followsTheDrive_holdsTheHeadingWhenStopped_andStaysLight() {
        val trace = LiveTrace()
        assertNull(trace.state.value.heading)
        val east = DriveSim().apply { heading = 90.0 }.drive(60, 15f)
        east.points.forEach(trace::add)
        assertEquals(90.0, trace.state.value.heading!!.toDouble(), 5.0)
        assertTrue("one point every 15 m or so: ${trace.state.value.size}", trace.state.value.size in 20..80)

        // Standing at a signal, GPS wanders tens of metres: the compass holds.
        val last = east.points.last()
        repeat(30) { trace.add(last.copy(t = last.t + it * 1_000L, lat = last.lat + (it % 3 - 1) * 2e-4), moving = false) }
        assertEquals(90.0, trace.state.value.heading!!.toDouble(), 6.0)

        // GPS gives the direction of travel with each fix: the compass follows it straight away.
        trace.add(last.copy(t = last.t + 40_000, lon = last.lon + 1e-4, bearing = 120f))
        assertEquals(120.0, trace.state.value.heading!!.toDouble(), 0.01)

        // A long drive keeps a bounded number of points.
        DriveSim().apply { heading = 0.0 }.drive(3_000, 25f).points.forEach(trace::add)
        assertTrue(trace.state.value.size <= LiveTrace.MAX_POINTS)
        assertEquals(0.0, trace.state.value.heading!!.toDouble(), 5.0)

        trace.reset()
        assertEquals(0, trace.state.value.size)
        assertNull(trace.state.value.heading)
    }
}
