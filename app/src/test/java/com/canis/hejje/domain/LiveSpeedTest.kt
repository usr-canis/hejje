package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpeedTest {
    private fun fix(speed: Float, speedAcc: Float = 0.6f, acc: Float = 8f) = RawPoint(0, 13.0, 77.6, speed = speed, speedAcc = speedAcc, acc = acc)

    @Test fun standingStill_readsZero_despiteGpsNoise() {
        val s = LiveSpeed()
        // Parked: GPS reports 0.5 to 2.5 m/s (2 to 9 km/h) of noise, mostly within its own accuracy.
        listOf(fix(0.6f), fix(1.8f, speedAcc = 1.6f), fix(0.9f), fix(2.4f, speedAcc = 2.0f), fix(1.5f, acc = 45f), fix(0.4f))
            .forEach { assertEquals(0f, s.update(it)) }
        // One clear reading alone (a jump) is not enough either.
        assertEquals(0f, s.update(fix(2.2f)))
        assertEquals(0f, s.update(fix(0.5f)))
    }

    @Test fun settingOff_showsAfterTwoClearReadings_orOneFastOne_andStopsAtOnce() {
        val s = LiveSpeed()
        assertEquals(0f, s.update(fix(2.0f)))
        assertEquals(3.0f, s.update(fix(3.0f)))
        assertEquals(12f, s.update(fix(12f)))
        assertEquals(0f, s.update(fix(0.5f)))
        // Straight from 0 to 20 km/h (e.g. a fix missed): shown at once.
        assertEquals(5.6f, s.update(fix(5.6f)))
        assertTrue(s.isMoving)
    }
}
