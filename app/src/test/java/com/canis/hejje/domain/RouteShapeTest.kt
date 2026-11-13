package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShapeTest {
    private fun shape(sim: DriveSim): RouteShape {
        val seg = sim.segment()
        val r = seg.data.route
        return RouteShape.of(listOf(r.lat to r.lon), seg.data.profile.dist.sum().toDouble())
    }

    @Test fun highway_isStraight_andDirect() {
        val s = shape(DriveSim().drive(600, 25f, turnPerSec = 0.03))
        assertTrue("twistiness ${s.twistiness}", s.twistiness < RouteShape.STRAIGHT_MAX)
        assertEquals(1.0, s.circuity, 0.05)
    }

    @Test fun gpsWobble_doesNotCountAsTurning() {
        for (speed in listOf(6f, 12f, 25f)) {
            val s = shape(DriveSim(positionNoiseM = 4.0, speedNoise = 0.5f).drive(400, speed))
            assertTrue("at $speed m/s: ${s.twistiness} °/km", s.twistiness < RouteShape.STRAIGHT_MAX)
        }
    }

    @Test fun cityGrid_isModerate_andLongerThanTheStraightLine() {
        val sim = DriveSim(positionNoiseM = 2.0)
        repeat(8) { i ->
            sim.drive(35, 11f).drive(8, 6f, turnPerSec = if (i % 2 == 0) 11.25 else -11.25)
        }
        val s = shape(sim)
        assertTrue("twistiness ${s.twistiness}", s.twistiness >= RouteShape.STRAIGHT_MAX && s.twistiness < RouteShape.MODERATE_MAX)
        // A staircase of right angles: about √2 times the straight line.
        assertEquals(1.41, s.circuity, 0.12)
    }

    @Test fun ghatRoad_isWinding() {
        val sim = DriveSim(positionNoiseM = 2.0)
        repeat(10) { i -> sim.drive(12, 8f).drive(10, 6f, turnPerSec = if (i % 2 == 0) 18.0 else -18.0) }
        val s = shape(sim)
        assertTrue("twistiness ${s.twistiness}", s.twistiness >= RouteShape.MODERATE_MAX)
    }

    @Test fun roundTrip_hasNoCircuity() {
        val sim = DriveSim().drive(120, 12f)
        sim.heading += 180.0
        sim.drive(120, 12f)
        val s = shape(sim)
        assertTrue(s.circuity.isNaN())
        assertTrue(s.roundTrip)
    }
}
