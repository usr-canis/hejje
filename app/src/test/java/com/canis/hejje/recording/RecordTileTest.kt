package com.canis.hejje.recording

import android.Manifest
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordTileTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking { app.container.settings.reset() }
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @Test fun tile_startsAndStopsAManualRecording() {
        val tile = Robolectric.buildService(RecordTileService::class.java).create().get()
        val controller = app.container.controller
        assertNull(controller.live.value)

        tile.onClick()
        val live = controller.live.value
        assertNotNull("recording started from the tile", live)
        assertTrue(live!!.manual)
        assertEquals(RecordingService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals("no problem reported for a start that worked", null, app.container.settings.current.lastProblem)

        tile.onClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("stopped from the tile", controller.live.value)
    }
}
