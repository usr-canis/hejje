package com.canis.hejje.qa

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.recording.MotionSensors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerIntegrationTest {
    @Test fun phoneWithoutMotionSensors_workerDoesNotSilentlyEraseDistance() {
        val app = ApplicationProvider.getApplicationContext<HejjeApp>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val points = ArrayList<RawPoint>()
        val worker = MotionSensors(app, { points += it }, {})
        try {
            assertFalse("Robolectric phone has no motion sensors", worker.start())
            for (i in 0..100) worker.fix(RawPoint(1_700_000_000_000L + i * 1000,
                12.0, 77.0 + i * 10 / 108_765.0, speed = 10f, acc = 5f, speedAcc = 0.4f), (i + 1) * 1_000_000_000L)
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (points.size < 101) Thread.sleep(10)
            }
            assertEquals("All GPS fixes must arrive", 101, points.size)
            val deltas = points.sumOf { it.fDist.toDouble() }
            println("WORKER_NO_SENSORS callbacks=${points.size} fusedDistance=${deltas}m")
            assertTrue("Worker must leave fDist missing for position fallback, or supply correct distance; got $deltas m",
                points.all { it.fDist.isNaN() } || deltas > 950.0)
        } finally {
            worker.stop()
        }
    }
}
