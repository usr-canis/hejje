package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class StretchesTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private fun north(m: Double) = lat0 + m / 110_540.0

    /** Drives north from the start for [seconds] at [speed]. */
    private fun northbound(id: Long, t: Long = DriveSim.T0, speed: Float = 15f, seconds: Int = 150, idleFirst: Int = 0) =
        TripTrack(id, listOf(DriveSim(t = t).apply { heading = 0.0 }.idle(idleFirst.coerceAtLeast(1)).drive(seconds, speed).segment()))

    @Test fun straightDrive_givesTimeAndDistanceBetweenThePoints() {
        val passes = Stretches.find(north(300.0), lon0, north(1_300.0), lon0, listOf(northbound(1)))
        val p = passes.single()
        assertEquals(1_000.0, p.distanceM, 15.0)
        assertEquals(1_000 / 15.0, p.durationMs / 1000.0, 2.5)
        assertEquals(15f, p.maxSpeed, 0.5f)
        assertTrue(p.lat.size >= 2)
    }

    @Test fun direction_matters() {
        val tracks = listOf(northbound(1))
        assertTrue(Stretches.find(north(1_300.0), lon0, north(300.0), lon0, tracks).isEmpty())
    }

    @Test fun outAndBack_countsEachDirectionOnce() {
        val sim = DriveSim().apply { heading = 0.0 }.drive(120, 15f).drive(10, 0f, accel = 3f).idle(30)
        sim.heading = 180.0
        sim.drive(130, 15f)
        val tracks = listOf(TripTrack(1, listOf(sim.segment())))
        assertEquals(1, Stretches.find(north(300.0), lon0, north(1_300.0), lon0, tracks).size)
        assertEquals(1, Stretches.find(north(1_300.0), lon0, north(300.0), lon0, tracks).size)
    }

    @Test fun waitingAtTheStart_isNotCounted() {
        // Parked at A for a minute before setting off: the time starts when the car leaves.
        val p = Stretches.find(lat0, lon0, north(600.0), lon0, listOf(northbound(1, idleFirst = 60))).single()
        assertTrue("took ${p.durationMs} ms", p.durationMs in 40_000L..50_000L)
    }

    @Test fun summary_findsTypicalFastestSlowest_andBestHour() {
        val day = 86_400_000L
        // 07:00 UTC drives are quick, 18:00 UTC drives are slow.
        val tracks = listOf(
            northbound(1, t = DriveSim.T0 - DriveSim.T0 % day + 7 * 3_600_000L, speed = 20f),
            northbound(2, t = DriveSim.T0 - DriveSim.T0 % day + day + 7 * 3_600_000L, speed = 19f),
            northbound(3, t = DriveSim.T0 - DriveSim.T0 % day + 18 * 3_600_000L, speed = 8f, seconds = 260),
            northbound(4, t = DriveSim.T0 - DriveSim.T0 % day + day + 18 * 3_600_000L, speed = 7f, seconds = 280),
        )
        val passes = Stretches.find(north(300.0), lon0, north(1_300.0), lon0, tracks)
        assertEquals(4, passes.size)
        val s = Stretches.summarize(passes, ZoneOffset.UTC)!!
        assertEquals(1L, s.fastest.tripId)
        assertEquals(4L, s.slowest.tripId)
        assertEquals(7, s.bestHour!!.first)
        assertEquals(18, s.worstHour!!.first)
        assertTrue(s.passes.first().start > s.passes.last().start) // newest first
    }

    @Test fun snap_findsTheNearestDrivenRoad_withinReach() {
        val tracks = listOf(northbound(1))
        val off = lon0 + 20 / (111_320.0 * Math.cos(Math.toRadians(lat0))) // 20 m east of the road
        val snapped = Stretches.snap(north(500.0), off, tracks, 50.0)
        assertNotNull(snapped)
        assertEquals(lon0, snapped!!.second, 1e-5)
        assertNull(Stretches.snap(north(500.0), lon0 + 0.01, tracks, 50.0)) // about 1 km away
    }
}
