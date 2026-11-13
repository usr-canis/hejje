package com.canis.hejje.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.db.HejjeDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Upgrading a real version-1 database (what 1.0.x phones have) must keep every trip and place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HejjeDb::class.java)

    @Test fun version1To2_keepsTripsZonesAndUnsavedPoints() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO trips (id, uuid, startTime, endTime, distanceM, movingMs, idleMs, stoppedMs, maxSpeed, stopCount, stopMs, " +
                    "elevGain, elevLoss, altMin, altMax, hardBrakes, hardAccels, smoothness, bands, nightShare, startLat, startLon, endLat, " +
                    "endLon, startPlace, endPlace, note, thumb, segmentCount, baro) VALUES (7, 'u-7', 1000, 2000, 12400.0, 900000, 0, 0, " +
                    "20.0, 0, 0, 0, 0, NULL, NULL, 0, 0, 90, '0,0,0,0,0', 0, 12.9, 77.6, 12.95, 77.65, 'Haralur', NULL, 'kept', X'', 1, 0)",
            )
            execSQL("INSERT INTO zones (id, name, lat, lon, radiusM) VALUES (3, 'Home', 12.9, 77.6, 300.0)")
            execSQL("INSERT INTO raw_points (session, t, lat, lon, alt, speed, acc, pressure, speedAcc) VALUES ('s1', 5, 12.9, 77.6, NULL, 3.0, 5.0, NULL, NULL)")
            close()
        }
        // Room checks that the upgraded tables exactly match what version 2 expects.
        helper.runMigrationsAndValidate(name, 2, true, HejjeDb.MIGRATION_1_2).close()

        val app = ApplicationProvider.getApplicationContext<HejjeApp>()
        val db = Room.databaseBuilder(app, HejjeDb::class.java, name).addMigrations(HejjeDb.MIGRATION_1_2).allowMainThreadQueries().build()
        runBlocking {
            val trip = db.trips().trip(7)!!
            assertEquals("kept", trip.note)
            assertEquals(12400.0, trip.distanceM, 0.0)
            val zone = db.zones().all().single()
            assertEquals("Home", zone.name)
            assertEquals("pin", zone.icon)
            assertTrue("old privacy zones stay hidden on shared maps", zone.hide)
            assertNull(zone.tag)
            val point = db.raw().points("s1").single()
            assertNull(point.sLon)
            assertEquals(3.0f, point.speed!!, 0f)
            assertTrue(db.log().recent(0).isEmpty())
        }
        db.close()
    }
}
