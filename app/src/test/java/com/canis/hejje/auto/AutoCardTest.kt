package com.canis.hejje.auto

import android.os.Looper
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.domain.DriveSim
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.recording.LiveState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

class DriveCardTest {
    private val f = Formatter(miles = false, locale = Locale.US)
    private val t = CardText()
    private val now = DriveSim.T0 + 24 * 60_000L

    @Test fun idle_explainsWhatHappensNext() {
        val auto = DriveCard.build(null, now, f, t, autoReady = true)
        assertEquals("Not recording", auto.title)
        assertEquals("Starts when your car connects", auto.subtitle)
        assertFalse(auto.recording)
        assertEquals(0L, auto.positionMs)
        assertEquals("Start a drive on your phone", DriveCard.build(null, now, f, t, autoReady = false).subtitle)
    }

    @Test fun recording_withoutGps_saysSo() {
        val card = DriveCard.build(LiveState(DriveSim.T0, manual = false), now, f, t, autoReady = true)
        assertEquals("Waiting for GPS…", card.title)
        assertEquals("Recording · 24 m", card.subtitle)
        assertTrue(card.recording)
    }

    @Test fun recording_showsSpeedDistancePositionAccuracyElevation_andSpeedBar() {
        val fix = RawPoint(now - 1_000, 12.9716, 77.5946, alt = 912.4f, speed = 62f / 3.6f, acc = 4.2f)
        val card = DriveCard.build(LiveState(DriveSim.T0, false, distanceM = 12_400.0, hasFix = true, lastFixAt = fix.t, last = fix), now, f, t, true)
        assertEquals("62 km/h · 12.4 km", card.title)
        assertEquals("Recording · 24 m", card.subtitle)
        assertEquals("12.97160° N, 77.59460° E · ±4 m · 912 m", card.details)
        assertEquals(62f / 120f, card.gauge, 0.001f)
        assertEquals(62_000L, card.positionMs)
    }

    @Test fun speedBar_isCapped_andStaleSpeedCountsAsZero() {
        val fast = RawPoint(now, -33.86, -151.2, speed = 50f, acc = 3f)
        val live = LiveState(DriveSim.T0, false, 1.0, true, fast.t, fast)
        assertEquals(1f, DriveCard.build(live, now, f, t, true).gauge, 0f)
        assertTrue(DriveCard.build(live, now, f, t, true).details.startsWith("33.86000° S, 151.20000° W"))
        assertEquals(0f, DriveCard.build(live, now + 60_000, f, t, true).gauge, 0f)
        assertTrue(DriveCard.build(live, now, f, t, true).details.endsWith("· –"))
    }

    @Test fun miles_followTheSetting() {
        val fix = RawPoint(now, 12.0, 77.0, alt = 100f, speed = 20f, acc = 3f)
        val card = DriveCard.build(LiveState(DriveSim.T0, false, 16_093.0, true, now, fix), now, Formatter(miles = true, locale = Locale.US), t, true)
        assertEquals("45 mph · 10.0 mi", card.title)
        assertTrue(card.details.endsWith("±10 ft · 328 ft"))
    }
}

/** The card as Android Auto sees it, with the real app wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrivePlayerTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()

    @Test fun neverPlaying_noPlayPause_andFollowsTheRecording() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.getSharedPreferences("recording", 0).edit().clear().commit()
        runBlocking { app.container.settings.reset() }
        val player = DrivePlayer(app, app.container)
        player.refresh()
        assertEquals("Not recording", player.mediaMetadata.title.toString())
        assertFalse(player.playWhenReady)
        assertFalse(player.isPlaying)
        assertFalse(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals(120_000L, player.duration)

        val c = app.container.controller
        c.manualStart()
        val fix = RawPoint(System.currentTimeMillis(), 12.9716, 77.5946, alt = 900f, speed = 15f, acc = 5f)
        c.onLocation(fix)
        shadowOf(Looper.getMainLooper()).idle()
        player.refresh()
        assertEquals("54 km/h · 0.0 km", player.mediaMetadata.title.toString())
        assertTrue(player.mediaMetadata.albumTitle.toString().startsWith("12.97160° N, 77.59460° E"))
        assertEquals(54_000L, player.currentPosition)
        assertFalse(player.isPlaying)
        c.manualStop()
        shadowOf(Looper.getMainLooper()).idle()
        player.refresh()
        assertEquals("Not recording", player.mediaMetadata.title.toString())
        player.release()
    }

    @Test fun onlyAndroidAutoAndHejjeMayConnect() {
        val trusted = HejjeAutoService.trusted(app)
        assertTrue("com.google.android.projection.gearhead" in trusted)
        assertTrue(app.packageName in trusted)
        assertFalse("com.example.tracker" in trusted)
    }
}
