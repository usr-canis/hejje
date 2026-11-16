package com.canis.hejje.domain

import com.canis.hejje.domain.MotionState.CREEPING
import com.canis.hejje.domain.MotionState.MOVING
import com.canis.hejje.domain.MotionState.STOPPED
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Replays of the awkward moments in stop-and-go traffic, each over many noise patterns (seeds), so
 * a result is not one lucky run. Each scenario adds a line to the report: distance error (mean and
 * worst), false stops, missed detections, and the worst delays from setting off to being shown as
 * moving and from stopping to being shown as stopped. A transition that is never detected counts
 * as missed, never as a zero delay. Limits sit a little above what the replays measure; they say
 * what the simulator supports, not what a road will do.
 */
class SpeedFusionTest {
    private class Sweep(
        val name: String,
        val errors: List<Double>,
        val trueM: Double,
        val falseStops: Int,
        val falseMoves: Int,
        val missedLaunches: Int,
        val missedStops: Int,
        val worstLaunchS: Double,
        val worstStopS: Double,
    ) {
        val worstErrorM get() = errors.maxOf { abs(it) }
        override fun toString() = String.format(
            Locale.ROOT,
            "%-18s %2d seeds  ~%6.1f m  error mean %+5.2f worst %5.2f m  false stops %d  false moves %d  missed %d/%d  set off→shown %4.1f s  stop→shown %4.1f s",
            name, errors.size, trueM, errors.average(), worstErrorM, falseStops, falseMoves, missedLaunches, missedStops, worstLaunchS, worstStopS,
        )
    }

    private fun sweep(name: String, seeds: IntRange = 1..30, make: (Int) -> FusionSim): Sweep {
        val errors = ArrayList<Double>()
        var trueM = 0.0
        var falseStops = 0
        var falseMoves = 0
        var missedLaunches = 0
        var missedStops = 0
        var worstLaunch = 0.0
        var worstStop = 0.0
        for (seed in seeds) {
            val sim = make(seed)
            val samples = sim.run()
            errors += samples.last().reading.distanceM - sim.dist
            trueM = sim.dist
            var wasStopped = false
            var wasShownMoving = false
            var prevMoving = false
            var moveStart = 0L
            var stopStart = 0L
            for (s in samples) {
                val moving = s.trueSpeed > 0.05f
                val stopped = s.reading.state == STOPPED
                val shownMoving = s.reading.state == CREEPING || s.reading.state == MOVING
                if (stopped && !wasStopped && moving) falseStops++
                if (shownMoving && !wasShownMoving && !moving) falseMoves++
                if (moving && !prevMoving) {
                    if (stopStart != 0L) missedStops++
                    stopStart = 0
                    moveStart = s.t
                }
                if (!moving && prevMoving) {
                    if (moveStart != 0L) missedLaunches++
                    moveStart = 0
                    stopStart = s.t
                }
                if (shownMoving && moveStart != 0L) { worstLaunch = max(worstLaunch, (s.t - moveStart) / 1e9); moveStart = 0 }
                if (stopped && stopStart != 0L) { worstStop = max(worstStop, (s.t - stopStart) / 1e9); stopStart = 0 }
                wasStopped = stopped
                wasShownMoving = shownMoving
                prevMoving = moving
            }
            if (moveStart != 0L) missedLaunches++
            if (stopStart != 0L) missedStops++
        }
        return Sweep(name, errors, trueM, falseStops, falseMoves, missedLaunches, missedStops, worstLaunch, worstStop).also {
            lines += it.toString()
            println(it)
        }
    }

    @Test fun parked_engineRunning_gpsJittering_readsZero() {
        val r = sweep("parked 60 s") { FusionSim(it).stop(60f) }
        assertTrue(r.toString(), r.worstErrorM < 1.0)
        assertEquals(r.toString(), 0, r.falseMoves)
        for (seed in 1..30) {
            val samples = FusionSim(seed).stop(60f).run()
            val confirmed = samples.first { it.reading.state == STOPPED }.t - 1_000_000_000L
            assertTrue("seed $seed: stop confirmed after ${confirmed / 1e9} s", confirmed <= 6_000_000_000L)
            assertTrue("seed $seed stays stopped", samples.drop(samples.size / 3).all { it.reading.state == STOPPED })
        }
    }

    @Test fun steadyCrawl_0_3ms_for30s_isCountedAsNineMetres() {
        val crawl = { seed: Int -> FusionSim(seed).stop(5f).to(0.3f, 0.3f).cruise(30f).to(0f, 0.3f).stop(5f) }
        val r = sweep("crawl 0.3 m/s", make = crawl)
        assertEquals(9.3, r.trueM, 0.1)
        assertTrue(r.toString(), r.worstErrorM < 2.0)
        // A crawl this slow sits inside GPS's own noise: now and then three readings look like a stop.
        assertTrue(r.toString(), r.falseStops <= 5)
        assertEquals(r.toString(), 0, r.missedLaunches)
        // A start this gentle (0.3 m/s²) is below the sensors' launch threshold, and at 0.3 m/s GPS
        // needs several readings to tell it from a standing car's noise: shown as unclear meanwhile.
        assertTrue(r.toString(), r.worstLaunchS < 9.5)
        for (seed in 1..30) {
            val crawling = crawl(seed).run().filter { it.trueSpeed in 0.29f..0.31f }
            val share = crawling.count { it.reading.state == CREEPING }.toFloat() / crawling.size
            assertTrue("seed $seed: creeping share $share", share > 0.6f)
        }
    }

    @Test fun walkingPaceCrawl_1ms_isNeverTakenForAStop() {
        val r = sweep("crawl 1 m/s") { FusionSim(it).stop(5f).to(1f, 0.5f).cruise(30f).to(0f, 0.5f).stop(5f) }
        assertTrue(r.toString(), r.worstErrorM < 2.5)
        assertEquals(r.toString(), 0, r.falseStops)
        assertTrue(r.toString(), r.worstLaunchS < 3.5)
    }

    private fun trafficAdvances(sim: FusionSim): FusionSim {
        sim.stop(6f)
        repeat(5) { sim.to(1.414f, 1.0f).to(0f, 1.0f).stop(6f) }
        return sim
    }

    @Test fun repeatedTrafficAdvances_ofTwoMetres_addUp() {
        val r = sweep("traffic advances") { trafficAdvances(FusionSim(it)) }
        assertEquals(10.0, r.trueM, 0.2)
        assertTrue(r.toString(), r.worstErrorM < 2.5)
        assertTrue(r.toString(), r.falseStops <= 2)
        assertEquals(r.toString(), 0, r.missedLaunches)
        // 150 stops of 6 s: now and then one is not confirmed before the next advance (shown as
        // unclear meanwhile), and a stationary reading flickers to "creeping" for half a second.
        assertTrue(r.toString(), r.missedStops <= 6)
        assertTrue(r.toString(), r.falseMoves <= 6)
        assertTrue(r.toString(), r.worstLaunchS < 2.0)
        assertTrue(r.toString(), r.worstStopS < 7.0)
    }

    @Test fun stoppingOnASlope_doesNotCreep() {
        var stopFrom = 0L
        var stopTo = 0L
        val slope = { seed: Int ->
            val sim = FusionSim(seed).apply { u = 10f }.cruise(10f)
            sim.slopeLeak = 0.6f
            sim.to(0f, 2.0f)
            stopFrom = sim.t
            sim.stop(20f)
            stopTo = sim.t
            sim.to(8f, 1.0f)
            sim.slopeLeak = 0f
            sim.cruise(10f)
        }
        val r = sweep("slope stop", make = slope)
        assertTrue(r.toString(), r.worstErrorM < 3.5)
        assertEquals(r.toString(), 0, r.falseMoves)
        val shares = (1..30).map { seed ->
            val samples = slope(seed).run()
            val duringStop = samples.filter { it.t in stopFrom..stopTo }
            val gained = duringStop.last().reading.distanceM - duringStop.first().reading.distanceM
            assertTrue("seed $seed: distance while stopped on the slope: $gained", gained < 1.5)
            val lastHalf = duringStop.takeLast(duringStop.size / 2)
            lastHalf.count { it.reading.state == STOPPED }.toFloat() / lastHalf.size
        }
        println("SLOPE stopped share of the stop's second half, per seed: " + shares.joinToString { String.format(Locale.ROOT, "%.2f", it) })
        // The slope's pull can look like setting off once; GPS then says otherwise, the pull is
        // learned, and the stop is confirmed again: a few seconds shown as unclear, no distance.
        assertTrue("fully stopped in ${shares.count { it == 1f }} of 30", shares.count { it == 1f } >= 27)
        assertTrue("worst ${shares.min()}", shares.min() >= 0.5f)
    }

    @Test fun reversing_fallsBackToGps() {
        val r = sweep("reversing") { FusionSim(it).stop(5f).to(-1.6f, 0.8f).cruise(5f).to(0f, 0.8f).stop(5f) }
        assertEquals(11.2, r.trueM, 0.1)
        assertTrue(r.toString(), r.worstErrorM < 2.0)
        assertEquals(r.toString(), 0, r.missedLaunches)
        assertTrue(r.toString(), r.worstLaunchS < 2.5)
    }

    @Test fun phonePickedUp_stillStopsAndCounts() {
        val r = sweep("phone in hand") { seed ->
            val sim = FusionSim(seed).apply { u = 8f }.cruise(10f)
            sim.trusted = false
            sim.to(0f, 2.0f).stop(6f)
            sim.trusted = true
            sim.stop(2f).to(8f, 1.5f).cruise(5f)
        }
        assertTrue(r.toString(), r.worstErrorM < 3.0)
        assertEquals(r.toString(), 0, r.falseStops)
        assertEquals(r.toString(), 0, r.missedStops)
        assertTrue(r.toString(), r.worstStopS < 6.0)
    }

    @Test fun lateAndDuplicateFixes_countEachIntervalOnce() {
        for (seed in 1..20) {
            val a = trafficAdvances(FusionSim(seed)).run().last().reading.distanceM
            val b = trafficAdvances(FusionSim(seed).apply { gpsDelayNs = 600_000_000L; duplicateFixes = true }).run().last().reading.distanceM
            assertEquals("seed $seed: on time $a m, late $b m", a, b, 0.3)
        }
        val r = sweep("late + duplicate", 1..20) { trafficAdvances(FusionSim(it).apply { gpsDelayNs = 600_000_000L; duplicateFixes = true }) }
        assertTrue(r.toString(), r.falseStops <= 2)
    }

    @Test fun gpsLoss_coastsBriefly_thenMarksAGap_andRecovers() {
        for (seed in 1..10) {
            val sim = FusionSim(seed).apply { u = 10f }.cruise(5f)
            sim.gpsOn = false
            val lostAt = sim.t
            sim.cruise(12f)
            sim.gpsOn = true
            val backAt = sim.t
            sim.cruise(10f).to(0f, 2.0f).stop(4f)
            val samples = sim.run()
            val gapStart = samples.first { it.reading.gap }
            assertTrue("seed $seed: gap only after coasting a while: ${(gapStart.t - lostAt) / 1e9} s", gapStart.t - lostAt in 4_000_000_000L..9_000_000_000L)
            assertTrue("seed $seed: coasted distance close to the truth", abs(gapStart.reading.distanceM - gapStart.trueDist) < 3.0)
            assertFalse(samples.last().reading.gap)
            val recovered = samples.first { it.t > backAt + 1_500_000_000L }
            assertFalse(recovered.reading.gap)
            assertEquals(MOVING, recovered.reading.state)
            // What the gap left out is about the distance driven meanwhile; the rest is counted.
            val missing = sim.dist - samples.last().reading.distanceM
            val gapSeconds = (backAt - gapStart.t) / 1e9
            assertTrue("seed $seed: missing $missing m for a gap of $gapSeconds s", abs(missing - gapSeconds * 10) < 15.0)
        }
        lines += "gps loss           10 seeds  coasts 4–6 s at 10 m/s, then a gap until GPS speed returns (by design)"
    }

    companion object {
        private val lines = ArrayList<String>()

        @JvmStatic @AfterClass fun summary() {
            println("Fusion replay report")
            lines.forEach(::println)
        }
    }
}
