package com.canis.hejje.recording

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.RawBuffer
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.domain.DriveSim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * The whole automatic-recording path with simulated Bluetooth events and GPS fixes:
 * controller, crash-safe buffer, processing and storage. Only the Android service that feeds
 * real GPS fixes is left out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingFlowTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: HejjeDb
    private lateinit var trips: TripRepository
    private lateinit var dir: File
    private var now = DriveSim.T0
    private val grace = 25_000L

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db = HejjeDb.create(app, inMemory = true)
        trips = TripRepository(db)
        dir = File(app.cacheDir, "raw-flow").apply { deleteRecursively() }
        app.getSharedPreferences("recording", 0).edit().clear().commit()
        runBlocking { app.container.settings.reset() }
    }

    @After fun tearDown() = db.close()

    /** A controller as a freshly started app process would create it. */
    private fun controller() = RecordingController(
        app, app.container.settings, RawBuffer(dir, db.raw()), db.raw(),
        TripFinalizer(app, db.raw(), trips, app.container.settings),
        CoroutineScope(SupervisorJob() + Dispatchers.Default), clock = { now }, graceMs = grace,
    )

    private fun eventually(what: String, condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(15)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    /** Feeds the simulated drive to the controller as the service would. */
    private fun RecordingController.feed(sim: DriveSim, from: Int = 0) {
        for (p in sim.points.drop(from)) {
            now = p.t
            onLocation(p)
        }
    }

    private fun passTime(ms: Long) {
        now += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun tripCount() = runBlocking { trips.all().size }

    @Test fun connectDriveDisconnect_savesTrip_andParkedLocation() {
        val c = controller()
        c.carConnected()
        assertTrue(c.isRecording)
        assertNotNull(c.live.value)
        val sim = DriveSim(t = now).drive(300, 15f).drive(10, 0f, accel = 2f)
        c.feed(sim)
        assertTrue(c.live.value!!.distanceM > 4_000)
        val disconnectedAt = now
        c.carDisconnected()
        // The phone walks away from the car during the grace period; those fixes must not count.
        val walk = DriveSim(t = now, lat = sim.lat, lon = sim.lon).drive(20, 1.6f)
        c.feed(walk)
        assertTrue("still recording during the grace period", c.isRecording)
        passTime(grace)
        eventually("trip saved") { tripCount() == 1 }
        assertFalse(c.isRecording)
        assertNull(c.live.value)
        val trip = runBlocking { trips.all().single() }
        assertEquals(disconnectedAt, trip.endTime)
        assertEquals(sim.points.last().lat, trip.endLat, 1e-4)
        eventually("parked location stored") { app.container.settings.current.parkedAt == disconnectedAt }
        assertEquals(trip.endLat, app.container.settings.current.parkedLat!!, 1e-9)
        eventually("raw points discarded") { runBlocking { db.raw().sessions().isEmpty() } }
        eventually("auto-start noted for Recording health") { app.container.settings.current.lastAutoStart > 0 }
    }

    @Test fun bluetoothFlapping_keepsOneRecording() {
        val c = controller()
        c.carConnected()
        val sim = DriveSim(t = now)
        repeat(4) {
            val before = sim.points.size
            sim.drive(60, 15f)
            c.feed(sim, before)
            c.carDisconnected()
            passTime(5_000)
            sim.t = now
            c.carConnected()
            passTime(grace)
            sim.t = now
        }
        assertTrue(c.isRecording)
        assertEquals(0, tripCount())
        c.manualStop()
        eventually("one trip") { tripCount() == 1 }
        assertEquals(1, runBlocking { trips.all().single() }.segmentCount)
    }

    @Test fun reconnectWithinMergeGap_continuesSameTrip() {
        val c = controller()
        c.carConnected()
        val a = DriveSim(t = now).drive(200, 15f).drive(8, 0f, accel = 2f)
        c.feed(a)
        c.carDisconnected()
        passTime(grace)
        eventually("first trip") { tripCount() == 1 }
        passTime(20 * 60_000L)
        c.carConnected()
        val b = DriveSim(t = now, lat = a.lat, lon = a.lon).drive(200, 15f).drive(8, 0f, accel = 2f)
        c.feed(b)
        c.carDisconnected()
        passTime(grace)
        eventually("second segment") { runBlocking { trips.all().singleOrNull()?.segmentCount } == 2 }
        val trip = runBlocking { trips.all().single() }
        assertEquals(1, trip.stopCount)
        assertTrue(trip.stoppedMs > 19 * 60_000L)
    }

    @Test fun appKilledMidDrive_recordingIsRecovered_andContinues() {
        var c = controller()
        c.manualStart()
        val sim = DriveSim(t = now).drive(100, 15f)
        c.feed(sim)
        val startedAt = c.startedAt
        // Process death: the controller is gone; a new one starts from what was saved.
        c = controller()
        assertTrue(c.isRecording)
        c.recover()
        eventually("live state restored") { c.live.value != null }
        assertEquals(startedAt, c.live.value!!.startedAt)
        eventually("distance restored from saved fixes") { c.live.value!!.distanceM > 1_000 }
        val before = sim.points.size
        sim.drive(100, 15f)
        c.feed(sim, before)
        c.manualStop()
        eventually("trip saved") { tripCount() == 1 }
        val trip = runBlocking { trips.all().single() }
        // Both halves of the drive are in the one trip.
        assertEquals(15.0 * 195, trip.distanceM, 150.0)
        assertEquals(1, trip.segmentCount)
    }

    @Test fun appKilled_andCarAlreadyGone_tripEndsAtLastFix() {
        val c = controller()
        c.carConnected()
        val sim = DriveSim(t = now).drive(120, 15f)
        c.feed(sim)
        val lastFix = now
        // Killed; the car disconnects while the app is dead; the phone restarts an hour later.
        now += 3_600_000L
        val restarted = controller()
        restarted.recover()
        // The car is not connected (no Bluetooth in the test), so the drive is closed.
        eventually("trip saved") { passTime(1_000); tripCount() == 1 }
        val trip = runBlocking { trips.all().single() }
        assertEquals(lastFix, trip.endTime)
        assertFalse(restarted.isRecording)
    }

    @Test fun autoRecordOff_ignoresTheCar() {
        runBlocking { app.container.settings.update { it.copy(autoRecord = false) } }
        val c = controller()
        c.carConnected()
        assertFalse(c.isRecording)
        c.manualStart()
        assertTrue(c.isRecording)
    }

    @Test fun driveWithoutAnyGps_savesNothing_andReportsIt() {
        val c = controller()
        c.carConnected()
        passTime(5 * 60_000L)
        c.carDisconnected()
        passTime(grace)
        eventually("problem reported") { app.container.settings.current.lastProblem != null }
        assertEquals(0, tripCount())
        assertFalse(c.isRecording)
    }
}
