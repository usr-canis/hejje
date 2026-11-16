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
 * Replays of the awkward moments in stop-and-go traffic. Each prints a line of the report:
 * distance error, false stops and how long a stop took to turn into a crawl.
 */
class SpeedFusionTest {
    /** What the replay says about one scenario. */
    private class Report(val name: String, val trueM: Double, val fusedM: Double, val falseStops: Int, val falseMoves: Int, val creepDelayS: Float, val stopDelayS: Float) {
        val errorM get() = fusedM - trueM
        override fun toString() = String.format(
            Locale.ROOT, "%-18s true %7.1f m  fused %7.1f m  error %+6.1f m  false stops %d  false moves %d  stop→creep %4.1f s  stop found %4.1f s",
            name, trueM, fusedM, errorM, falseStops, falseMoves, creepDelayS, stopDelayS,
        )
    }

    private fun report(name: String, sim: FusionSim, samples: List<FusionSim.Sample>): Report {
        var falseStops = 0
        var falseMoves = 0
        var wasStopped = false
        var wasMoving = false
        var motionStart = 0L
        var creepDelay = 0f
        var stopStart = 0L
        var stopDelay = 0f
        var prevTrueMoving = false
        for (s in samples) {
            val trueMoving = s.trueSpeed > 0.05f
            val stopped = s.reading.state == STOPPED
            val moving = s.reading.state == CREEPING || s.reading.state == MOVING
            if (stopped && !wasStopped && trueMoving) falseStops++
            if (moving && !wasMoving && !trueMoving) falseMoves++
            if (trueMoving && !prevTrueMoving) { motionStart = s.t; stopStart = 0 }
            if (!trueMoving && prevTrueMoving) { stopStart = s.t; motionStart = 0 }
            if (moving && motionStart != 0L) { creepDelay = max(creepDelay, (s.t - motionStart) / 1e9f); motionStart = 0 }
            if (stopped && stopStart != 0L) { stopDelay = max(stopDelay, (s.t - stopStart) / 1e9f); stopStart = 0 }
            wasStopped = stopped
            wasMoving = moving
            prevTrueMoving = trueMoving
        }
        val r = Report(name, sim.dist, samples.last().reading.distanceM, falseStops, falseMoves, creepDelay, stopDelay)
        lines += r.toString()
        println(r)
        return r
    }

    /** Debug aid: every state change, with the truth at that moment. */
    private fun trace(name: String, samples: List<FusionSim.Sample>) {
        var last: MotionState? = null
        val sb = StringBuilder("TRACE $name:")
        for (s in samples) {
            if (s.reading.state != last) {
                sb.append(String.format(Locale.ROOT, " %.1fs:%s(v%.2f/true%.2f,d%.1f/%.1f)", (s.t - 1_000_000_000L) / 1e9, s.reading.state.name.take(4), s.reading.speed, s.trueSpeed, s.reading.distanceM, s.trueDist))
                last = s.reading.state
            }
        }
        println(sb)
    }

    @Test fun parked_engineRunning_gpsJittering_readsZero() {
        val sim = FusionSim().stop(60f)
        val samples = sim.run()
        val r = report("parked", sim, samples)
        trace("parked", samples)
        assertTrue(r.toString(), r.fusedM < 1.0)
        assertEquals(0, r.falseMoves)
        val confirmed = samples.first { it.reading.state == STOPPED }.t - 1_000_000_000L
        assertTrue("stop confirmed within 5 s: ${confirmed / 1e9}", confirmed <= 5_000_000_000L)
        assertTrue(samples.drop(samples.size / 2).all { it.reading.state == STOPPED })
    }

    @Test fun steadyCrawl_0_3ms_for30s_isCountedAsNineMetres() {
        val sim = FusionSim().stop(5f).to(0.3f, 0.3f).cruise(30f).to(0f, 0.3f).stop(5f)
        val samples = sim.run()
        val r = report("crawl 0.3 m/s", sim, samples)
        assertEquals(9.3, r.trueM, 0.1)
        assertTrue(r.toString(), abs(r.errorM) < 2.0)
        assertEquals(0, r.falseStops)
        // A very gentle start (0.3 m/s²) is below the sensors' launch threshold: GPS has to see three fixes.
        assertTrue(r.toString(), r.creepDelayS < 5.5f)
        // The whole crawl is seen as creeping, not standing still.
        val crawling = samples.filter { it.trueSpeed in 0.29f..0.31f }
        val share = crawling.count { it.reading.state == CREEPING }.toFloat() / crawling.size
        assertTrue("creeping share $share", share > 0.8f)
    }

    private fun trafficAdvances(sim: FusionSim): FusionSim {
        sim.stop(6f)
        repeat(5) { sim.to(1.414f, 1.0f).to(0f, 1.0f).stop(6f) }
        return sim
    }

    @Test fun repeatedTrafficAdvances_ofTwoMetres_addUp() {
        val sim = trafficAdvances(FusionSim())
        val samples = sim.run()
        val r = report("traffic advances", sim, samples)
        trace("traffic", samples)
        assertEquals(10.0, r.trueM, 0.2)
        assertTrue(r.toString(), abs(r.errorM) < 2.5)
        assertEquals(0, r.falseStops)
        assertTrue(r.toString(), r.creepDelayS < 2.5f)
        assertTrue(r.toString(), r.stopDelayS < 5f)
        // Each stop is found: six stopped spells (the first, then one after each advance).
        var spells = 0
        var was = false
        for (s in samples) { val now = s.reading.state == STOPPED; if (now && !was) spells++; was = now }
        assertEquals(6, spells)
    }

    @Test fun stoppingOnASlope_doesNotCreep() {
        val sim = FusionSim().apply { u = 10f }.cruise(10f)
        sim.slopeLeak = 0.6f
        sim.to(0f, 2.0f)
        val stopFrom = sim.t
        sim.stop(20f)
        val stopTo = sim.t
        sim.to(8f, 1.0f)
        sim.slopeLeak = 0f
        sim.cruise(10f)
        val samples = sim.run()
        val r = report("slope stop", sim, samples)
        trace("slope", samples)
        assertTrue(r.toString(), abs(r.errorM) < 8.0)
        val duringStop = samples.filter { it.t in stopFrom..stopTo }
        val gained = duringStop.last().reading.distanceM - duringStop.first().reading.distanceM
        assertTrue("distance while stopped on the slope: $gained", gained < 1.5)
        assertTrue(duringStop.takeLast(duringStop.size / 2).all { it.reading.state == STOPPED })
        assertTrue(r.toString(), r.falseMoves <= 1)
    }

    @Test fun reversing_fallsBackToGps() {
        val sim = FusionSim().stop(5f).to(-1.6f, 0.8f).cruise(5f).to(0f, 0.8f).stop(5f)
        val samples = sim.run()
        val r = report("reversing", sim, samples)
        assertEquals(11.2, r.trueM, 0.1)
        assertTrue(r.toString(), abs(r.errorM) < 7.0)
        assertTrue(samples.any { it.trueSpeed > 1.5f && it.reading.state == CREEPING })
        assertTrue(r.toString(), r.creepDelayS < 5f)
    }

    @Test fun phonePickedUp_stillStopsAndCounts() {
        val sim = FusionSim().apply { u = 8f }.cruise(10f)
        sim.trusted = false
        sim.to(0f, 2.0f).stop(6f)
        sim.trusted = true
        sim.stop(2f).to(8f, 1.5f).cruise(5f)
        val samples = sim.run()
        val r = report("phone in hand", sim, samples)
        trace("phone", samples)
        assertTrue(r.toString(), abs(r.errorM) < 4.0)
        assertEquals(0, r.falseStops)
        assertTrue(r.toString(), r.stopDelayS < 5f)
    }

    @Test fun lateAndDuplicateFixes_countEachIntervalOnce() {
        val onTime = trafficAdvances(FusionSim())
        val late = trafficAdvances(FusionSim().apply { gpsDelayNs = 600_000_000L; duplicateFixes = true })
        val a = onTime.run().last().reading.distanceM
        val b = late.run().last().reading.distanceM
        val r = report("late fixes", late, late.run())
        assertEquals("on time $a m, late $b m", a, b, 0.3)
        assertEquals(0, r.falseStops)
    }

    @Test fun gpsLoss_coastsBriefly_thenMarksAGap_andRecovers() {
        val sim = FusionSim().apply { u = 10f }.cruise(5f)
        sim.gpsOn = false
        val lostAt = sim.t
        sim.cruise(12f)
        sim.gpsOn = true
        val backAt = sim.t
        sim.cruise(10f).to(0f, 2.0f).stop(4f)
        val samples = sim.run()
        val r = report("gps loss", sim, samples)
        val gapStart = samples.first { it.reading.gap }
        assertTrue("gap only after coasting a while: ${(gapStart.t - lostAt) / 1e9} s", gapStart.t - lostAt in 4_000_000_000L..9_000_000_000L)
        assertTrue("coasted distance close to the truth", abs(gapStart.reading.distanceM - gapStart.trueDist) < 3.0)
        assertFalse(samples.last().reading.gap)
        val recovered = samples.first { it.t > backAt + 1_500_000_000L }
        assertFalse(recovered.reading.gap)
        assertEquals(MOVING, recovered.reading.state)
        // What the gap left out is about the distance driven meanwhile; the rest is counted.
        val missing = r.trueM - r.fusedM
        val gapSeconds = (backAt - gapStart.t) / 1e9
        assertTrue("missing $missing m for a gap of $gapSeconds s", abs(missing - gapSeconds * 10) < 15.0)
    }

    companion object {
        private val lines = ArrayList<String>()

        @JvmStatic @AfterClass fun summary() {
            println("Fusion replay report")
            lines.forEach(::println)
        }
    }
}
