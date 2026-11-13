package com.canis.hejje.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FluidityTest {
    private fun fluidity(sim: DriveSim) = StatsCalculator.compute(listOf(sim.segment())).smoothness

    private fun city(accel: Float, brake: Float, top: Float, cruise: Int, idle: Int, step: Long = 1000, noise: Float = 0.4f) =
        DriveSim(stepMs = step, speedNoise = noise, positionNoiseM = 2.0).apply {
            repeat(8) { drive(25, top, accel = accel).drive(cruise, top, turnPerSec = 0.5).drive(20, 0f, accel = brake).idle(idle) }
        }

    @Test fun gpsJitter_doesNotCostPoints() {
        val clean = fluidity(DriveSim().drive(600, 25f))
        val noisy = fluidity(DriveSim(speedNoise = 0.8f, positionNoiseM = 4.0).drive(600, 25f))
        assertTrue("clean $clean, noisy $noisy", clean >= 95 && noisy >= 95)
        assertTrue(abs(clean - noisy) <= 3)
    }

    @Test fun drivingStyles_rankInTheRightOrder() {
        val gentle = fluidity(city(1.0f, 1.2f, 12f, 30, 20))
        val normal = fluidity(city(1.8f, 2.2f, 14f, 20, 10))
        val aggressive = fluidity(city(3.0f, 4.0f, 17f, 8, 5))
        assertTrue("gentle $gentle, normal $normal, aggressive $aggressive", gentle > normal && normal > aggressive)
        assertTrue("gentle $gentle", gentle >= 90)
        assertTrue("normal $normal", normal in 70..92)
        assertTrue("aggressive $aggressive", aggressive <= 60)
    }

    @Test fun sameDrive_sameScore_atOneOrTwoSecondGps() {
        val oneSecond = fluidity(city(1.8f, 2.2f, 14f, 20, 10, step = 1000))
        val twoSecond = fluidity(city(1.8f, 2.2f, 14f, 20, 10, step = 2000))
        assertTrue("1 s $oneSecond, 2 s $twoSecond", abs(oneSecond - twoSecond) <= 4)
    }
}
