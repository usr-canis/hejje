package com.canis.hejje.ui.trips

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class OdometerTest {
    private fun pos(v: Float, drums: Int = 3) = odometerPositions(v, drums)

    @Test fun settledValues_showPlainDigits() {
        assertArrayEquals(floatArrayOf(5f, 5f, 1f, 0f), pos(15.5f), 0.01f)
        assertArrayEquals(floatArrayOf(3f, 4f, 6f, 4f, 1f), pos(1464.3f, 4), 0.01f)
    }

    @Test fun higherDrumsTurnOnlyWhileTheOneBelowPassesNine() {
        // 19.95: tenths half way 9 -> 0, so ones and tens are half way too (9.5 and 1.5).
        assertArrayEquals(floatArrayOf(9.5f, 9.5f, 1.5f, 0f), pos(19.95f), 0.01f)
        // 15.25: tenths at 2.5, nothing above moves.
        assertArrayEquals(floatArrayOf(2.5f, 5f, 1f, 0f), pos(15.25f), 0.01f)
    }
}
