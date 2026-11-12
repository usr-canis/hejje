package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProcessingTest {
    private fun near(expected: Double, actual: Double, tolerance: Double) =
        assertTrue("expected $expected +/- $tolerance but was $actual", abs(expected - actual) <= tolerance)

    @Test fun steadyDrive_distanceTimeAndSpeed() {
        val sim = DriveSim().drive(600, 20f)
        val truth = sim.points.zipWithNext { a, b -> Geo.distance(a.lat, a.lon, b.lat, b.lon) }.sum()
        val stats = StatsCalculator.compute(listOf(sim.segment()))
        near(truth, stats.distanceM, truth * 0.01)
        near(20.0, stats.maxSpeed.toDouble(), 0.2)
        near(598_000.0, stats.movingMs.toDouble(), 4_000.0)
        assertEquals(0, stats.stops.size)
        assertEquals(0, stats.hardBrakes + stats.hardAccels)
        assertTrue(stats.smoothness >= 90)
    }

    @Test fun rawPointsAreReducedButShapeIsKept() {
        val sim = DriveSim().drive(120, 15f).drive(20, 8f, turnPerSec = 4.5).drive(120, 15f)
        val seg = sim.segment()
        assertTrue("route has ${seg.data.route.size} of ${sim.points.size}", seg.data.route.size < sim.points.size / 3)
        // Every raw point stays within a few metres of the simplified line.
        val r = seg.data.route
        val worst = sim.points.maxOf { p ->
            (0 until r.size - 1).minOf { i ->
                Simplifier.segmentDistance(
                    Geo.x(p.lon, r.lon[0], r.lat[0]), Geo.y(p.lat, r.lat[0]),
                    Geo.x(r.lon[i], r.lon[0], r.lat[0]), Geo.y(r.lat[i], r.lat[0]),
                    Geo.x(r.lon[i + 1], r.lon[0], r.lat[0]), Geo.y(r.lat[i + 1], r.lat[0]),
                )
            }
        }
        assertTrue("worst deviation $worst m", worst < 6.0)
    }

    @Test fun idleStop_countsAsIdleNotDistance() {
        val moving = DriveSim(positionNoiseM = 0.0).drive(120, 14f).drive(15, 0f, accel = 2f)
        val before = moving.points.size
        val sim = DriveSim(positionNoiseM = 3.0).drive(120, 14f).drive(15, 0f, accel = 2f).idle(120).drive(120, 14f)
        val stats = StatsCalculator.compute(listOf(sim.segment()))
        val idleStops = stats.stops.filter { !it.parked }
        assertEquals(1, idleStops.size)
        near(120_000.0, idleStops[0].durationMs.toDouble(), 15_000.0)
        near(120_000.0, stats.idleMs.toDouble(), 15_000.0)
        // Jitter while standing still must not add distance: compare with twice the moving part.
        val oneWay = moving.points.zipWithNext { a, b -> Geo.distance(a.lat, a.lon, b.lat, b.lon) }.sum()
        assertTrue(before > 0)
        assertTrue("distance ${stats.distanceM} vs ${oneWay * 2}", stats.distanceM < oneWay * 2 * 1.08)
    }

    @Test fun tunnel_gapIsBridged() {
        val sim = DriveSim().drive(60, 20f).tunnel(90).drive(60, 20f)
        val stats = StatsCalculator.compute(listOf(sim.segment()))
        // 20 m/s for ~200 s after the first acceleration.
        near(20.0 * 203, stats.distanceM, 150.0)
        near(205_000.0, stats.movingMs.toDouble(), 10_000.0)
        assertEquals(0, stats.hardBrakes + stats.hardAccels)
    }

    @Test fun poorFixesAndJumpsAreDropped() {
        val sim = DriveSim().drive(60, 10f)
        val pts = sim.points.toMutableList()
        pts[20] = pts[20].copy(acc = 180f, lat = pts[20].lat + 0.01)
        pts[40] = pts[40].copy(lat = pts[40].lat + 0.05) // 5 km jump with good accuracy
        val clean = TripProcessor.clean(pts)
        assertEquals(pts.size - 2, clean.size)
        val a = StatsCalculator.compute(listOf(sim.segment())).distanceM
        val b = StatsCalculator.compute(listOf(TripProcessor.process(pts, pts.first().t, pts.last().t)!!)).distanceM
        near(a, b, 5.0)
    }

    @Test fun noUsablePoints_givesNoSegment() {
        assertNull(TripProcessor.process(emptyList(), 0, 60_000))
        assertNull(TripProcessor.process(listOf(RawPoint(1000, 12.0, 77.0, acc = 400f)), 0, 60_000))
    }

    @Test fun elevation_barometerIsPreferredAndAccurate() {
        val baro = DriveSim(pressureAtStart = 915f).drive(60, 15f).drive(200, 15f, climbPerSec = 0.5f).drive(100, 15f, climbPerSec = -0.3f)
        val seg = baro.segment()
        assertTrue(seg.data.baro)
        val s = StatsCalculator.compute(listOf(seg))
        near(100.0, s.elevGain.toDouble(), 6.0)
        near(30.0, s.elevLoss.toDouble(), 6.0)
        near(900.0, s.altMin.toDouble(), 6.0)
        near(1000.0, s.altMax.toDouble(), 6.0)
    }

    @Test fun elevation_gpsFallbackIgnoresNoise() {
        val flat = DriveSim().drive(600, 15f)
        val seg = flat.segment()
        assertFalse(seg.data.baro)
        val s = StatsCalculator.compute(listOf(seg))
        assertTrue("gain on flat road was ${s.elevGain}", s.elevGain < 6f && s.elevLoss < 6f)
        val climb = StatsCalculator.compute(listOf(DriveSim().drive(60, 15f).drive(200, 15f, climbPerSec = 0.5f).segment()))
        near(100.0, climb.elevGain.toDouble(), 12.0)
    }

    @Test fun speedBands_andDayNight() {
        val sim = DriveSim().drive(300, 8f, accel = 4f).drive(300, 24f, accel = 4f)
        val s = StatsCalculator.compute(listOf(sim.segment()))
        assertEquals(s.movingMs, s.bandsMs.sum())
        near(300_000.0, s.bandsMs[0].toDouble(), 10_000.0) // ~29 km/h
        near(295_000.0, s.bandsMs[2].toDouble(), 10_000.0) // ~86 km/h
        assertEquals(0f, s.nightShare, 0.001f) // noon in India
        val night = DriveSim(t = DriveSim.T0 + 12 * 3_600_000L).drive(300, 10f)
        assertEquals(1f, StatsCalculator.compute(listOf(night.segment())).nightShare, 0.001f)
    }

    @Test fun sunAltitude_isSane() {
        assertTrue(Geo.sunAltitudeDeg(DriveSim.T0, 12.97, 77.59) > 45)
        assertTrue(Geo.sunAltitudeDeg(DriveSim.T0 + 12 * 3_600_000L, 12.97, 77.59) < -45)
    }

    @Test fun hardBraking_detected_gentleIgnored() {
        val hard = DriveSim().drive(60, 22f).drive(6, 0f, accel = 4.5f).idle(10).drive(60, 22f)
        val events = hard.segment().data.events
        assertEquals(1, events.count { it.brake })
        assertEquals(0, events.count { !it.brake })
        assertTrue(events[0].peak in 3.5f..5.5f)

        val gentle = DriveSim().drive(60, 22f).drive(15, 0f, accel = 1.8f).idle(10).drive(60, 22f, accel = 1.8f)
        assertTrue(gentle.segment().data.events.isEmpty())
    }

    @Test fun hardAcceleration_detected() {
        val sim = DriveSim().drive(30, 6f).drive(6, 26f, accel = 3.6f).drive(30, 26f)
        val events = sim.segment().data.events
        assertEquals(1, events.count { !it.brake })
        assertEquals(0, events.count { it.brake })
    }

    @Test fun noisySpeedAndSpikes_causeNoFalseEvents() {
        val sim = DriveSim(speedNoise = 0.5f).drive(900, 18f)
        val pts = sim.points.toMutableList()
        for (i in 100 until pts.size step 97) pts[i] = pts[i].copy(speed = 0f) // single-sample dropouts
        for (i in 150 until pts.size step 131) pts[i] = pts[i].copy(speed = 40f) // single-sample spikes
        val seg = TripProcessor.process(pts, pts.first().t, pts.last().t)!!
        assertTrue("false events: ${seg.data.events}", seg.data.events.isEmpty())
    }

    @Test fun twoSecondSampling_stillDetectsHardBraking() {
        val sim = DriveSim(stepMs = 2000).drive(60, 22f).drive(6, 0f, accel = 4.5f).idle(10)
        assertEquals(1, sim.segment().data.events.count { it.brake })
    }

    @Test fun sensitivity_changesThreshold() {
        val sim = DriveSim().drive(60, 22f).drive(8, 0f, accel = 3.2f).idle(10)
        assertEquals(1, sim.segment(ProcessorConfig(brakeThreshold = 2.5f)).data.events.size)
        assertEquals(0, sim.segment(ProcessorConfig(brakeThreshold = 3.6f)).data.events.size)
    }

    @Test fun simplifier_keepsCornersAndForcedPoints() {
        val xs = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 30.0, 30.0)
        val ys = doubleArrayOf(0.0, 0.5, -0.5, 0.0, 10.0, 20.0)
        assertEquals(listOf(0, 3, 5), Simplifier.simplify(xs, ys, 2.0).toList())
        val forced = BooleanArray(6).also { it[1] = true }
        assertEquals(listOf(0, 1, 3, 5), Simplifier.simplify(xs, ys, 2.0, forced).toList())
        assertEquals(listOf(0, 5), Simplifier.simplify(DoubleArray(6) { it * 5.0 }, DoubleArray(6), 2.0).toList())
        assertEquals(listOf(0, 5, 10), Simplifier.thin(11, 3).toList())
        assertEquals(listOf(0, 1), Simplifier.thin(2, 40).toList())
    }
}
