package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorFusionTest {
    private fun stats(sim: DriveSim) = StatsCalculator.compute(listOf(sim.segment()))

    /** Cruising at 20 m/s while GPS speed briefly drops by 8 m/s and recovers. */
    private fun glitch(sensors: Boolean) = DriveSim(sensors = sensors).apply {
        drive(30, 20f).drive(30, 20f)
        gpsSpeedOffset = -8f
        drive(4, 20f)
        gpsSpeedOffset = 0f
        drive(30, 20f)
    }

    @Test fun gpsGlitch_isDroppedWhenTheSensorsFeltNothing() {
        val gpsOnly = stats(glitch(sensors = false))
        assertTrue("GPS alone sees the glitch", gpsOnly.hardBrakes + gpsOnly.hardAccels > 0)
        val fused = stats(glitch(sensors = true))
        assertEquals(0, fused.hardBrakes)
        assertEquals(0, fused.hardAccels)
    }

    @Test fun realHardBrake_isConfirmedBySensors() {
        val s = stats(DriveSim(sensors = true).drive(40, 20f).drive(8, 0f, accel = 5f).idle(10))
        assertEquals(1, s.hardBrakes)
        assertEquals(0, s.hardAccels)
    }

    @Test fun hardBrake_withPoorGpsSpeed_isFoundBySensorsAlone() {
        fun sim(sensors: Boolean) = DriveSim(sensors = sensors).apply {
            drive(40, 20f)
            speedAccuracy = 3f // GPS speed too uncertain to judge
            drive(8, 0f, accel = 5f)
            speedAccuracy = Float.NaN
            idle(10)
        }
        assertEquals(0, stats(sim(sensors = false)).hardBrakes)
        assertEquals(1, stats(sim(sensors = true)).hardBrakes)
    }

    @Test fun cornering_isClassified_andNeedsSensors() {
        fun cornering(degPerSec: Double, sensors: Boolean = true) = stats(
            DriveSim(sensors = sensors).apply { repeat(6) { drive(15, 10f).drive(8, 10f, turnPerSec = degPerSec) } },
        )
        val gentle = cornering(8.0)
        val moderate = cornering(15.0)
        val firm = cornering(25.0)
        assertTrue("gentle ${gentle.cornering}", gentle.cornering < StatsCalculator.GENTLE_CORNERING)
        assertTrue("moderate ${moderate.cornering}", moderate.cornering >= StatsCalculator.GENTLE_CORNERING && moderate.cornering < StatsCalculator.FIRM_CORNERING)
        assertTrue("firm ${firm.cornering}", firm.cornering >= StatsCalculator.FIRM_CORNERING)
        assertTrue("share ${firm.sensorShare}", firm.sensorShare > 0.9f)
        val gps = cornering(25.0, sensors = false)
        assertTrue(gps.cornering.isNaN())
        assertEquals(0f, gps.sensorShare, 0f)
    }

    @Test fun fluidity_withSensors_ignoresGpsNoise_andStillRanksStyles() {
        fun city(accel: Float, brake: Float, top: Float, cruise: Int, idle: Int) =
            DriveSim(speedNoise = 1.2f, positionNoiseM = 5.0, sensors = true).apply {
                repeat(8) { drive(25, top, accel = accel).drive(cruise, top, turnPerSec = 0.5).drive(20, 0f, accel = brake).idle(idle) }
            }
        val gentle = stats(city(1.0f, 1.2f, 12f, 30, 20)).smoothness
        val aggressive = stats(city(3.0f, 4.0f, 17f, 8, 5)).smoothness
        assertTrue("gentle $gentle", gentle >= 90)
        assertTrue("aggressive $aggressive", aggressive <= 60)
    }

    @Test fun splittingATrip_keepsTheSensorData() {
        val seg = DriveSim(sensors = true).drive(120, 15f).segment()
        val (a, b) = TripEditor.splitSegment(seg, seg.start + 60_000).let { it.first!! to it.second!! }
        assertTrue(a.data.profile.hasSensors && b.data.profile.hasSensors)
        assertEquals(seg.data.profile.size, a.data.profile.size + b.data.profile.size)
    }
}
