package com.canis.hejje.qa

import com.canis.hejje.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Independent acceptance checks from the 1.8.0 QA review (Codex), kept as regression tests.
 * Changed only where the API's meaning was settled since: a duplicate or older fix now reports
 * 0 m since the previous fix (nothing new) instead of NaN.
 */
class IndependentEdgeTest {
    private val ns = 1_000_000_000L
    private val epoch = 1_700_000_000_000L
    private fun fix(f: SpeedFusion, second: Int, speed: Float = 10f, accuracy: Float = 5f): FixResult =
        f.onFix((second + 1) * ns, speed, 0.4f, 12.0, 77.0 + second * 10 / 108_765.0, accuracy)

    @Test fun gpsOnlyWithoutMotionSensors_countsOneKilometre() {
        val f = SpeedFusion()
        var sum = 0.0
        for (i in 0..100) sum += fix(f, i).sinceFixM
        println("GPS_ONLY expected=1000m actual=${f.distanceM} sumOfFixDeltas=$sum speed=${f.speed}")
        assertEquals("A phone without linear-acceleration/gravity sensors must retain GPS distance", 1000.0, f.distanceM, 50.0)
    }

    @Test fun missingMotionSensors_fullPipelineRetainsTripDistance() {
        val f = SpeedFusion()
        val points = (0..100).map { i ->
            val r = fix(f, i)
            RawPoint(epoch + i * 1000, 12.0, 77.0 + i * 10 / 108_765.0,
                speed = 10f, acc = 5f, fSpeed = r.reading.speed, fDist = r.sinceFixM, fState = r.reading.state.ordinal)
        }
        val actual = TripProcessor.process(points, epoch, epoch + 100_000)!!.data.profile.dist.sum()
        println("GPS_ONLY_PROCESSED expected=1000m actual=$actual")
        assertEquals(1000.0, actual.toDouble(), 50.0)
    }

    @Test fun rejectingPoorPositionFixes_doesNotLoseTheirTravelledDistance() {
        val points = (0..10).map { i ->
            RawPoint(epoch + i * 1000, 12.0, 77.0 + i * 10 / 108_765.0,
                speed = 10f, acc = if (i == 4 || i == 5) 100f else 5f,
                fSpeed = 10f, fDist = if (i == 0) 0f else 10f, fState = MotionState.MOVING.ordinal)
        }
        val actual = TripProcessor.process(points, epoch, epoch + 10_000)!!.data.profile.dist.sum()
        println("FILTERED_FIXES expected=100m actual=$actual clean=${TripProcessor.clean(points).size}")
        assertEquals("Fused interval distances must be accumulated when intermediate positions are dropped", 100f, actual, 2f)
    }

    @Test fun lostGpsSpeed_stationaryPositions_doNotInventHundredsOfMetres() {
        val f = SpeedFusion()
        for (i in 0..5) {
            for (j in 1..25) f.onAccel(i * ns + j * 40_000_000L, 0f, false)
            f.onFix((i + 1) * ns, 10f, 0.4f, 12.0, 77.0 + i * 10 / 108_765.0, 5f)
        }
        val before = f.distanceM
        for (i in 6..65) {
            for (j in 1..25) f.onAccel(i * ns + j * 40_000_000L, 0f, false)
            f.onFix((i + 1) * ns, Float.NaN, Float.NaN, 12.0, 77.0 + 50 / 108_765.0, 5f)
        }
        val gained = f.distanceM - before
        val r = f.reading(66 * ns)
        println("MISSING_SPEED stoppedFor=60s gained=${gained}m speed=${r.speed} state=${r.state} sigma=${r.speedSigma} gap=${r.gap}")
        assertTrue("Without usable speed or trusted acceleration, old speed cannot be integrated indefinitely: $gained m", gained < 70)
    }

    @Test fun gpsLoss_withoutFurtherSensorEvents_reportsAccurateFixAge() {
        val f = SpeedFusion()
        fix(f, 0)
        val r = f.reading(30 * ns)
        println("NO_EVENTS age=${r.fixAgeMs} state=${r.state} gap=${r.gap} speed=${r.speed}")
        assertEquals("Callers can detect stale snapshots from fixAgeMs", 29_000L, r.fixAgeMs)
    }

    @Test fun delayedFix_returnsSpeedAtMeasurementTimeForStoredPoint() {
        fun sample(late: Boolean): FusedReading {
            val f = SpeedFusion()
            f.onFix(ns, 10f, 0.3f, 12.0, 77.0, 5f)
            for (j in 1..25) f.onAccel(ns + j * 40_000_000L, 2f, true)
            if (!late) return f.onFix(2 * ns, 12f, 0.3f, 12.0, 77.0001, 5f).reading
            for (j in 1..20) f.onAccel(2 * ns + j * 40_000_000L, 2f, true)
            return f.onFix(2 * ns, 12f, 0.3f, 12.0, 77.0001, 5f).reading
        }
        val timely = sample(false)
        val late = sample(true)
        println("LATE_FIX pointTime=2000000000 timelyT=${timely.t} lateT=${late.t} timelySpeed=${timely.speed} lateSpeed=${late.speed}")
        // MotionSensors writes this speed onto the older RawPoint; it needs a separate fix-time reading.
        assertEquals("Stored fix speed must describe the fix's timestamp", timely.speed, late.speed, 0.15f)
    }

    @Test fun lateFixes_matchTimelyReplay_overManySeeds() {
        var worst = 0.0
        for (seed in 1..30) {
            fun sim(delay: Long) = FusionSim(seed).apply { gpsDelayNs = delay }.stop(6f).to(12f, 1.5f).cruise(8f).to(0f, 2f).stop(6f)
            val a = sim(0).run().last().reading.distanceM
            val b = sim(600_000_000).run().last().reading.distanceM
            worst = maxOf(worst, abs(a - b))
        }
        println("LATE_REPLAY seeds=30 worstDifference=${worst}m")
        assertTrue("Late and timely deliveries of the same trace differ by $worst m", worst < 2.0)
    }

    @Test fun stopGo_variedNoiseSeeds_remainsWithinUsefulAccuracy() {
        var worst = 0.0
        var failures = 0
        for (seed in 1..50) {
            val sim = FusionSim(seed).stop(6f)
            repeat(5) { sim.to(1.414f, 1f).to(0f, 1f).stop(6f) }
            val samples = sim.run()
            val error = abs(samples.last().reading.distanceM - sim.dist)
            worst = maxOf(worst, error)
            if (error > 2.5) {
                failures++
                println("TRAFFIC_OUTLIER seed=$seed truth=${sim.dist} fused=${samples.last().reading.distanceM} error=$error")
            }
        }
        println("TRAFFIC_SWEEP seeds=50 unacceptable=$failures worstError=${worst}m trueDistance=10m")
        assertEquals("The advertised two-metre advances must work beyond one fixed noise seed", 0, failures)
    }

    @Test fun parked_variedNoiseSeeds_doesNotAccumulateDistance() {
        var worst = 0.0
        var failures = 0
        for (seed in 1..50) {
            val sim = FusionSim(seed).stop(60f)
            val dist = sim.run().last().reading.distanceM
            worst = maxOf(worst, dist)
            if (dist > 2.0) failures++
        }
        println("PARKED_SWEEP seeds=50 unacceptable=$failures worstDistance=${worst}m")
        assertEquals(0, failures)
    }

    @Test fun slowCrawl_variedNoiseSeeds_isNotDiscardedAsStationary() {
        var worst = 0.0
        var failures = 0
        for (seed in 1..50) {
            val sim = FusionSim(seed).stop(5f).to(0.3f, 0.3f).cruise(30f).to(0f, 0.3f).stop(5f)
            val error = abs(sim.run().last().reading.distanceM - sim.dist)
            worst = maxOf(worst, error)
            if (error > 2.0) failures++
        }
        println("CRAWL_SWEEP seeds=50 unacceptable=$failures worstError=${worst}m trueDistance=9.3m")
        assertEquals(0, failures)
    }

    @Test fun duplicateAndOlderFixes_doNotChangeDistance() {
        val f = SpeedFusion()
        fix(f, 0)
        for (j in 1..25) f.onAccel(ns + j * 40_000_000L, 0f, false)
        fix(f, 1)
        val before = f.distanceM
        assertEquals(0f, fix(f, 1).sinceFixM, 0f)
        assertEquals(0f, fix(f, 0).sinceFixM, 0f)
        assertEquals(before, f.distanceM, 0.0)
    }

    @Test fun reset_clearsPriorTripAndHistory() {
        val f = SpeedFusion()
        fix(f, 0)
        f.onAccel(ns + 40_000_000, 1f, true)
        f.reset()
        assertEquals(0.0, f.distanceM, 0.0)
        assertEquals(0f, f.speed, 0f)
        assertEquals(MotionState.UNKNOWN, f.state)
        assertEquals(Long.MAX_VALUE, f.reading(5 * ns).fixAgeMs)
    }

    @Test fun actualEstimatorColdStart_shortTrafficAdvancesStillCount() {
        val sim = FusionSim(7).stop(6f)
        repeat(5) { sim.to(1.414f, 1f).to(0f, 1f).stop(6f) }
        val fusion = SpeedFusion()
        val estimator = MotionEstimator()
        for (e in sim.events) {
            if (e.isFix) {
                estimator.onFix(e.t, e.speed)
                fusion.onFix(e.t, e.speed, e.speedAcc, e.lat, e.lon, e.acc)
            } else {
                val forward = estimator.onMotion(e.t, floatArrayOf(e.a, 0f, 0f), floatArrayOf(0f, 0f, 9.81f), floatArrayOf(0f, 0f, 0f), true)
                fusion.onAccel(e.t, forward, !forward.isNaN())
            }
        }
        println("ESTIMATOR_COLD_START truth=${sim.dist}m fused=${fusion.distanceM}m calibrated=${estimator.isCalibrated}")
        assertEquals("Do not assume the forward axis is already known at the start of a drive", sim.dist, fusion.distanceM, 2.5)
    }

    @Test fun highwayCruise_variedSeeds_distanceWithinFivePercent() {
        var worst = 0.0
        for (seed in 1..30) {
            val sim = FusionSim(seed).apply { u = 30f }.cruise(120f)
            val error = abs(sim.run().last().reading.distanceM - sim.dist)
            worst = maxOf(worst, error)
        }
        println("HIGHWAY_SWEEP seeds=30 trueDistance=3600m worstError=${worst}m")
        assertTrue(worst < 180)
    }

    @Test fun privacyZone_lineCrossingWithBothEndpointsOutside_doesNotExposeZone() {
        val zone = Zone(12.0, 77.0, 100.0)
        val route = Route(doubleArrayOf(12.0, 12.0), doubleArrayOf(76.998, 77.002), intArrayOf(0, 30), floatArrayOf(15f, 15f))
        val pieces = PrivacyZones.trim(route, listOf(zone))
        val leaks = pieces.any { p -> (1..99).any { i ->
            val at = (p.size - 1) * i / 100f
            PrivacyZones.inside(p.latAt(at), p.lonAt(at), listOf(zone))
        } }
        println("PRIVACY_CROSSING pieces=${pieces.size} connectsAcrossHiddenZone=$leaks")
        assertFalse("A simplified route segment crossing a hidden zone must be split, even if both endpoints are outside", leaks)
    }
}
