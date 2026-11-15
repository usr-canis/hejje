package com.canis.hejje.food

import android.Manifest
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.R
import com.canis.hejje.data.StopReminders
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.data.db.StopAskEntity
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.notify.Notifier
import com.canis.hejje.recording.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The parking question and "How was it?", against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoodManagerTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: HejjeDb
    private lateinit var food: FoodManager
    private val live = MutableStateFlow<LiveState?>(null)
    private var now = System.currentTimeMillis()
    private val parkedAt get() = now - 9 * 60_000L
    private val shown get() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
    private fun titles() = shown.map { it.extras.getCharSequence("android.title").toString() }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = HejjeDb.create(app, inMemory = true)
        food = FoodManager(app, db, app.container.settings, TripRepository(db), Notifier(app), live, CoroutineScope(Dispatchers.Unconfined)) { now }
        runBlocking { app.container.settings.reset() }
    }

    @After fun tearDown() = db.close()

    private fun park(lat: Double, lon: Double) = runBlocking {
        val at = parkedAt
        app.container.settings.update { it.copy(parkedLat = lat, parkedLon = lon, parkedAt = at) }
        at
    }

    @Test fun asksAfterParking_butNotAtHome_orWhereSwipedAway() = runBlocking {
        db.zones().upsert(ZoneEntity(name = "Home", lat = 12.90, lon = 77.60, radiusM = 150.0, icon = "home"))
        food.askIfStillParked(park(12.90, 77.60))
        assertTrue("no question at home", titles().isEmpty())

        val at = park(13.00, 77.70)
        food.askIfStillParked(at)
        assertEquals(listOf(app.getString(R.string.food_question)), titles())
        val ask = db.stopAsks().since(0).single()
        food.dismissed(ask.id)
        assertEquals(StopAskEntity.DISMISSED, db.stopAsks().get(ask.id)!!.state)

        // Back at the same spot a day later: swiped away there once, so not asked again.
        now += 86_400_000L
        app.getSystemService(NotificationManager::class.java).cancelAll()
        food.askIfStillParked(park(13.00, 77.70))
        assertTrue(titles().isEmpty())
    }

    @Test fun saving_makesAPlace_countsTheMeal_andAsksHowItWasAfterTheDrive() = runBlocking {
        val at = park(13.00, 77.70)
        food.askIfStillParked(at)
        val ask = db.stopAsks().since(0).single()
        val placeId = food.save("Food", "Kamat Upachar", 13.00, 77.70, at, ask.id)
        val place = db.zones().all().single { it.id == placeId }
        assertEquals("food", place.icon)
        assertEquals("Kamat Upachar", place.name)
        assertEquals(at, app.container.settings.current.lastFoodAt)
        assertEquals(StopAskEntity.SAVED, db.stopAsks().get(ask.id)!!.state)

        // 30 minutes later the driver is on the road again: no question while driving.
        now += 31 * 60_000L
        app.getSystemService(NotificationManager::class.java).cancelAll()
        live.value = LiveState(startedAt = now, manual = false)
        food.askFeedback(ask.id)
        assertTrue("never while driving", titles().isEmpty())

        // Once the drive is saved, it asks.
        live.value = null
        food.onTripSaved()
        assertEquals(listOf(app.getString(R.string.food_feedback, "Kamat Upachar")), titles())

        food.setRemind(placeId, remind = false, askId = ask.id)
        assertEquals(-1, db.zones().all().single { it.id == placeId }.rating)
        assertEquals(-1, db.stopAsks().get(ask.id)!!.feedback)
    }

    @Test fun namingLater_fromTheTimeline_withoutAName() = runBlocking {
        val at = now - 3_600_000L
        val id = food.save("coffee", "  ", 13.10, 77.80, at)
        val place = db.zones().all().single { it.id == id }
        assertEquals(app.getString(R.string.food_default_coffee), place.name)
        assertEquals("coffee", place.icon)
        assertNotNull(place)
        assertEquals(at, app.container.settings.current.lastCoffeeAt)
    }

    @Test fun ownKindOfStop_andRemindersOff_stillLetYouNameAndChoose() = runBlocking {
        val id = food.save("Fuel", "", 13.2, 77.9, now - 3_600_000L)
        val place = db.zones().all().single { it.id == id }
        assertEquals("Fuel", place.name)
        assertEquals("Fuel", place.stopPurpose())
        assertNull("not food or coffee", place.meal())
        assertEquals(0L, app.container.settings.current.lastFoodAt)

        // Reminders off: nothing is asked, but you can still choose remind or not.
        app.container.settings.update { it.copy(stopReminders = StopReminders.OFF) }
        food.askIfStillParked(park(13.00, 77.70))
        assertTrue(titles().isEmpty())
        food.setRemind(id, remind = false)
        assertEquals(-1, db.zones().all().single { it.id == id }.rating)
    }
}
