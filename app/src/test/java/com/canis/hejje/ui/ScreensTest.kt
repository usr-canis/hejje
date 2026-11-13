package com.canis.hejje.ui

import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.MainActivity
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.domain.DriveSim
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Runs the real app (activity, navigation, database) on the JVM with sample trips and walks
 * through its screens. Each screen is also saved as an image under build/screens for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class ScreensTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()

    @get:Rule(order = 0)
    val seed = object : ExternalResource() {
        override fun before() {
            WorkManagerTestInitHelper.initializeTestWorkManager(app)
            runBlocking { seedTrips() }
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun drive(startMsAgo: Long, shape: Int): List<Segment> {
        val sim = DriveSim(t = System.currentTimeMillis() - startMsAgo, pressureAtStart = 915f)
        sim.drive(240, 14f).drive(30, 9f, turnPerSec = 3.0).drive(300, 22f, climbPerSec = 0.1f).drive(8, 0f, accel = 4.2f).idle(70)
            .drive(200, 16f, turnPerSec = -0.4 * shape).drive(25, 8f, turnPerSec = -3.0).drive(260, 27f, climbPerSec = -0.05f).drive(20, 0f, accel = 1.6f)
        val first = sim.segment()
        if (shape != 2) return listOf(first)
        // A second segment after a 25-minute stop with the engine off.
        val again = DriveSim(t = sim.t + 25 * 60_000L, lat = sim.lat, lon = sim.lon, pressureAtStart = 915f)
        again.heading = 200.0
        again.drive(400, 18f, turnPerSec = 0.2).drive(20, 0f, accel = 1.5f)
        return listOf(first, again.segment())
    }

    private suspend fun seedTrips() {
        val c = app.container
        val hour = 3_600_000L
        val trips = c.trips
        trips.insertTrip("t1", drive(2 * hour, 1), "Picked up the cake", "Indiranagar, Bengaluru", "MG Road, Bengaluru", listOf("Errand"))
        trips.insertTrip("t2", drive(7 * hour, 2), "", "Koramangala, Bengaluru", "Indiranagar, Bengaluru", listOf("Commute", "Work"))
        trips.insertTrip("t3", drive(27 * hour, 3), "", null, null, emptyList())
        trips.insertTrip("t4", drive(4 * 24 * hour, 1), "", "Hebbal, Bengaluru", "MG Road, Bengaluru", listOf("Work"))
        trips.insertTrip("t5", drive(9 * 24 * hour, 2), "", "MG Road, Bengaluru", "Nandi Hills", listOf("Road trip"))
        trips.insertTrip("t6", drive(10 * 24 * hour, 3), "", "Nandi Hills", "MG Road, Bengaluru", listOf("Road trip"))
        c.db.zones().upsert(ZoneEntity(0, "Home", 12.9716, 77.5946, 300.0, icon = "home"))
        c.db.zones().upsert(ZoneEntity(0, "Office", 12.99, 77.61, 250.0, icon = "work", hide = false, tag = "Work"))
        val h = System.currentTimeMillis() - 2 * hour
        listOf(
            com.canis.hejje.data.db.LogEntity(0, h - 1_800_000, "CAR_CONNECTED", "companion"),
            com.canis.hejje.data.db.LogEntity(0, h - 1_799_000, "REC_STARTED", "auto"),
            com.canis.hejje.data.db.LogEntity(0, h - 1_793_000, "FIRST_FIX", "6"),
            com.canis.hejje.data.db.LogEntity(0, h - 1_200_000, "GPS_LOST", ""),
            com.canis.hejje.data.db.LogEntity(0, h - 1_170_000, "GPS_REGAINED", "30"),
            com.canis.hejje.data.db.LogEntity(0, h - 1_000, "CAR_DISCONNECTED", "companion"),
            com.canis.hejje.data.db.LogEntity(0, h, "TRIP_SAVED", "20700", c.trips.all().last().id),
        ).forEach { c.db.log().insert(it) }
        c.settings.update {
            it.copy(onboardingDone = true, updateAutoCheck = false, parkedLat = 12.975, parkedLon = 77.605, parkedAt = System.currentTimeMillis() - 2 * hour, carName = "My Car", carAddress = "00:11:22:33:44:55")
        }
    }

    /** Waits until [text] is on screen (also lets the splash fade and transitions finish). */
    private fun await(text: String) {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1_500)
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
    }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tripsList_showsSummaryAndGroupedTrips() {
        await("This week")
        rule.onNodeWithText("This week", substring = true, ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithContentDescription("Parked car location", substring = true).assertIsDisplayed()
        assertEquals(6, runBlocking { app.container.trips.all().size })
        shot("trips-light")
    }

    @Test @Config(qualifiers = "+night") fun tripsList_dark() {
        await("This week")
        shot("trips-dark")
    }

    @Test fun tripDetail_showsStats_andEditsCanBeUndone() {
        await("This week")
        rule.onAllNodesWithText("Home → Indiranagar").onFirst().performClick()
        await("Distance")
        shot("detail-light-1")
        scrollTo("Time by speed")
        shot("detail-light-2")
        scrollTo("Segments")
        shot("detail-light-3")
        // Delete from the menu, then bring it back with Undo.
        rule.onNodeWithContentDescription("More options").performClick()
        shot("detail-menu")
        await("Delete")
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(5_000) { runBlocking { app.container.trips.all().size } == 5 }
        await("Undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { runBlocking { app.container.trips.all().size } == 6 }
        val restored = runBlocking { app.container.trips.all().first { it.uuid == "t2" } }
        assertEquals(2, restored.segmentCount)
        assertEquals(listOf("Commute", "Work"), runBlocking { app.container.trips.tagNames(restored.id) }.sorted())
    }

    @Test @Config(qualifiers = "+night") fun tripDetail_dark() {
        await("This week")
        rule.onAllNodesWithText("Home → MG Road").onFirst().performClick()
        await("Distance")
        shot("detail-dark-1")
        scrollTo("Elevation")
        shot("detail-dark-2")
    }

    @Test fun insights_showsTotalsCalendarAndTags() {
        await("This week")
        rule.onNodeWithText("Insights").performClick()
        await("Driving calendar")
        shot("insights-light-1")
        scrollTo("By tag")
        shot("insights-light-2")
    }

    @Test @Config(qualifiers = "+night") fun insights_dark() {
        await("This week")
        rule.onNodeWithText("Insights").performClick()
        await("Driving calendar")
        shot("insights-dark")
    }

    @Test fun settings_andRecordingHealth() {
        await("This week")
        rule.onNodeWithText("Settings").performClick()
        await("Car Bluetooth device")
        shot("settings-light-1")
        scrollTo("Check for updates")
        shot("settings-light-2")
        scrollTo("Recording health")
        rule.onNodeWithText("Recording health").performClick()
        await("Battery: unrestricted")
        shot("health-light")
        scrollTo("Share log")
        shot("health-activity")
    }

    @Test fun places_listAndEditor() {
        await("This week")
        rule.onNodeWithText("Settings").performClick()
        await("Car Bluetooth device")
        scrollTo("Places")
        rule.onNodeWithText("Places").performClick()
        await("Name the places you drive to")
        shot("places")
        rule.onNodeWithText("Office").performClick()
        await("Tag trips that end here")
        shot("place-editor")
    }

    @Test fun stretch_comparesEveryDriveBetweenTwoPoints() {
        await("This week")
        rule.onNodeWithText("Insights").performClick()
        await("Pick two points")
        rule.onNodeWithText("Pick two points", substring = true).performClick()
        await("Tap where the stretch starts")
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Map of the roads", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        // Without the map engine (as in tests) the map is drawn on a canvas that frames the latest drives.
        val tracks = runBlocking { app.container.trips.tracks() }
        val routes = tracks.take(15).flatMap { t -> t.segments.map { it.data.route } }
        val lats = routes.flatMap { it.lat.asList() }
        val lons = routes.flatMap { it.lon.asList() }
        val lat0 = (lats.min() + lats.max()) / 2
        val lon0 = (lons.min() + lons.max()) / 2
        val spanX = maxOf(1.0, Geo.x(lons.max(), lon0, lat0) - Geo.x(lons.min(), lon0, lat0))
        val spanY = maxOf(1.0, Geo.y(lats.max(), lat0) - Geo.y(lats.min(), lat0))
        val map = rule.onNodeWithContentDescription("Map of the roads", substring = true)
        val size = map.fetchSemanticsNode().size
        val pad = with(rule.density) { 40.dp.toPx() }
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        fun at(lat: Double, lon: Double) = Offset(
            (size.width / 2 + Geo.x(lon, lon0, lat0) * scale).toFloat(),
            (size.height / 2 - Geo.y(lat, lat0) * scale).toFloat(),
        )
        // Every seeded drive starts with the same straight road: measure from 1 to 3 minutes into it.
        val r = tracks.last().segments.first().data.route
        val i1 = r.t.indexOfFirst { it >= 60 }
        val i2 = r.t.indexOfFirst { it >= 180 }
        map.performTouchInput { click(at(r.lat[i1], r.lon[i1])) }
        await("Now tap where it ends")
        map.performTouchInput { click(at(r.lat[i2], r.lon[i2])) }
        await("6 drives")
        rule.onNodeWithText("Typical").assertExists()
        shot("stretch")
    }

    @Test fun backGesture_looksLikeAndroidsOwn() {
        await("This week")
        rule.onAllNodesWithText("Home → Indiranagar").onFirst().performClick()
        await("Distance")
        // Midway through the back swipe: the trip shrinks, with the list waiting behind it under a scrim.
        rule.runOnUiThread {
            val back = rule.activity.onBackPressedDispatcher
            back.dispatchOnBackStarted(BackEventCompat(0f, 900f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(160f, 900f, 0.22f, BackEventCompat.EDGE_LEFT))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File("build/screens").apply { mkdirs() }.resolve("back-gesture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        await("This week")
    }

    @Test fun parkedCar_offersNavigation() {
        await("This week")
        rule.onNodeWithContentDescription("Parked car location", substring = true).performClick()
        await("Navigate back")
        shot("parked-light")
    }
}

/** First launch: the permission guide is shown instead of the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class OnboardingTest {
    @get:Rule(order = 0)
    val fresh = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<HejjeApp>()
            WorkManagerTestInitHelper.initializeTestWorkManager(app)
            // Settings are kept in one store for the whole test run, so start from a clean slate.
            runBlocking { app.container.settings.reset() }
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test fun firstLaunch_showsGuide_andCanBeSkippedThrough() {
        val dir = File("build/screens").apply { mkdirs() }
        fun await(text: String) {
            rule.mainClock.advanceTimeBy(1_500)
            rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
            rule.mainClock.advanceTimeBy(1_500)
            rule.waitForIdle()
        }
        fun shot(name: String) = File(dir, "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        await("Every drive, recorded for you")
        shot("onboarding-1")
        rule.onNodeWithText("Get started").performClick()
        await("Allow location")
        shot("onboarding-2")
        // Skip every step; the app must still be usable without any permission.
        var guard = 0
        while (rule.onAllNodesWithText("You are all set").fetchSemanticsNodes().isEmpty() && guard++ < 12) {
            val skip = listOf("Skip for now", "Choose later", "Continue").firstOrNull { rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText(skip ?: break).performClick()
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
        }
        await("You are all set")
        shot("onboarding-done")
        rule.onNodeWithText("Open the app").performClick()
        await("This week")
        rule.waitUntil(10_000) { rule.onAllNodesWithText("No trips yet", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("trips-empty")
    }
}
