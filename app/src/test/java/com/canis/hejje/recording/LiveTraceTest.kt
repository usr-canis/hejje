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

        // Standing at a signal, GPS wanders a couple of metres: the compass does not spin.
        val last = east.points.last()
        repeat(30) { trace.add(last.copy(t = last.t + it * 1_000L, lat = last.lat + (it % 3 - 1) * 2e-5)) }
        assertEquals(90.0, trace.state.value.heading!!.toDouble(), 6.0)

        // A long drive keeps a bounded number of points.
        DriveSim().apply { heading = 0.0 }.drive(3_000, 25f).points.forEach(trace::add)
        assertTrue(trace.state.value.size <= LiveTrace.MAX_POINTS)
        assertEquals(0.0, trace.state.value.heading!!.toDouble(), 5.0)

        trace.reset()
        assertEquals(0, trace.state.value.size)
        assertNull(trace.state.value.heading)
    }
}
