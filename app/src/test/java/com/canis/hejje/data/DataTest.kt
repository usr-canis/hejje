package com.canis.hejje.data

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.canis.hejje.HejjeApp
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.data.db.ZoneEntity
import com.canis.hejje.domain.DriveSim
import com.canis.hejje.domain.Gpx
import com.canis.hejje.domain.RawPoint
import com.canis.hejje.domain.StatsCalculator
import com.canis.hejje.update.GhRelease
import com.canis.hejje.update.Updater
import com.canis.hejje.domain.HejjeJson
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Storage tests against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataTest {
    private val app: HejjeApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: HejjeDb
    private lateinit var trips: TripRepository
    private val gap = 90 * 60_000L

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        db = HejjeDb.create(app, inMemory = true)
        trips = TripRepository(db)
        runBlocking { app.container.settings.reset() }
    }

    @After fun tearDown() = db.close()

    private fun sim(t: Long = DriveSim.T0) = DriveSim(t = t).drive(300, 15f).drive(8, 0f, accel = 4.5f).idle(40).drive(300, 20f)

    @Test fun recomputeAll_refreshesStats_andKeepsNotesAndPlaces() = runBlocking {
        val id = trips.addRecording(sim().segment(), gap, 0)!!
        trips.setNote(id, "keep me")
        trips.setPlaces(id, "Haralur", "Koramangala")
        val good = trips.trip(id)!!
        db.trips().update(good.copy(smoothness = 3, distanceM = 1.0))
        trips.recomputeAll()
        val again = trips.trip(id)!!
        assertEquals(good.smoothness, again.smoothness)
        assertEquals(good.distanceM, again.distanceM, 0.01)
        assertEquals("keep me", again.note)
        assertEquals("Haralur", again.startPlace)
        assertEquals("Koramangala", again.endPlace)
    }

    @Test fun presetTags_exist() = runBlocking {
        assertEquals(listOf("Commute", "Errand", "Road trip", "Work"), trips.tags.first().map { it.name })
        assertTrue(trips.tags.first().all { it.preset })
    }

    @Test fun recording_withinMergeGap_becomesSecondSegment_otherwiseNewTrip() = runBlocking {
        val first = sim()
        val id1 = trips.addRecording(first.segment(), gap, 0)!!
        // 30 minutes later: same trip, new segment, the pause recorded as a stop.
        val second = DriveSim(t = first.t + 30 * 60_000L, lat = first.lat, lon = first.lon).drive(200, 15f)
        assertEquals(id1, trips.addRecording(second.segment(), gap, 0))
        val merged = trips.trip(id1)!!
        assertEquals(2, merged.segmentCount)
        assertTrue(merged.stoppedMs in 29 * 60_000L..31 * 60_000L)
        // 3 hours later: a new trip.
        val third = DriveSim(t = second.t + 180 * 60_000L).drive(200, 15f)
        val id3 = trips.addRecording(third.segment(), gap, 0)!!
        assertTrue(id3 != id1)
        assertEquals(2, trips.all().size)
    }

    @Test fun minimumTripLength_dropsShortTrips_onlyWhenSet() = runBlocking {
        val short = DriveSim().drive(20, 5f).segment()
        assertNull(trips.addRecording(short, 0, minTripM = 1000))
        assertNotNull(trips.addRecording(short, 0, minTripM = 0))
    }

    @Test fun split_merge_trim_delete_areAllUndoable() = runBlocking {
        val id = trips.addRecording(sim().segment(), gap, 0)!!
        trips.setTag(id, "Work", true)
        trips.setNote(id, "note")
        val original = trips.trip(id)!!

        val split = trips.split(id, original.startTime + 200_000)!!
        assertEquals(2, trips.all().size)
        val parts = trips.all()
        assertEquals(original.distanceM, parts.sumOf { it.distanceM }, 1.0)
        assertEquals(listOf("Work"), trips.tagNames(parts[1].id))

        val merge = trips.merge(parts[0].id, parts[1].id)!!
        assertEquals(1, trips.all().size)
        assertEquals(original.distanceM, trips.all()[0].distanceM, 1.0)
        trips.undo(merge)
        assertEquals(2, trips.all().size)
        trips.undo(split)
        assertEquals(1, trips.all().size)
        assertEquals(original.distanceM, trips.trip(id)!!.distanceM, 0.001)
        assertEquals("note", trips.trip(id)!!.note)
        assertEquals(listOf("Work"), trips.tagNames(id))

        val trim = trips.trim(id, original.startTime + 60_000, original.endTime - 60_000)!!
        assertTrue(trips.trip(id)!!.distanceM < original.distanceM - 1000)
        trips.undo(trim)
        assertEquals(original.distanceM, trips.trip(id)!!.distanceM, 0.001)

        val delete = trips.delete(id)!!
        assertTrue(trips.all().isEmpty())
        trips.undo(delete)
        assertEquals(1, trips.segments(id).size)
        assertEquals(listOf("Work"), trips.tagNames(id))
    }

    @Test fun backup_exportsEverything_andImportSkipsDuplicates() = runBlocking {
        val settings = app.container.settings
        val backup = BackupManager(app, db, trips, settings)
        val id = trips.addRecording(sim().segment(), gap, 0)!!
        trips.addRecording(sim(DriveSim.T0 + 5 * 3_600_000L).segment(), gap, 0)
        trips.setTag(id, "Weekend", true)
        trips.setNote(id, "to the hills")
        db.zones().upsert(ZoneEntity(0, "Home", 12.97, 77.59, 300.0))
        settings.update { it.copy(miles = true, mergeGapMin = 45) }
        val before = trips.all()

        val bytes = ByteArrayOutputStream().also { backup.writeBackup(it) }.toByteArray()
        // Importing into the same data: everything is a duplicate.
        assertEquals(ImportResult(added = 0, duplicates = 2, failed = 0), backup.read(ByteArrayInputStream(bytes)))

        trips.deleteEverything()
        settings.reset()
        assertTrue(trips.all().isEmpty())
        assertEquals(ImportResult(added = 2, duplicates = 0, failed = 0), backup.read(ByteArrayInputStream(bytes)))
        val after = trips.all()
        assertEquals(before.map { it.uuid }, after.map { it.uuid })
        assertEquals(before.map { it.distanceM }, after.map { it.distanceM })
        val restored = after.first { it.note == "to the hills" }
        assertEquals(listOf("Weekend"), trips.tagNames(restored.id))
        assertEquals(1, db.zones().all().size)
        assertTrue(settings.current.miles)
        assertEquals(45, settings.current.mergeGapMin)
    }

    @Test fun gpx_importsAsTrip_andDetectsDuplicateOfExistingDrive() = runBlocking {
        val backup = BackupManager(app, db, trips, app.container.settings)
        val seg = sim().segment()
        val xml = Gpx.write("Morning", "test", Gpx.pointsOf(listOf(seg), emptyList()))
        assertEquals(ImportResult(added = 1), backup.read(ByteArrayInputStream(xml.toByteArray())))
        val trip = trips.all().single()
        assertEquals(StatsCalculator.compute(listOf(seg)).distanceM, trip.distanceM, trip.distanceM * 0.02)
        assertEquals("Morning", trip.note)
        assertEquals(ImportResult(duplicates = 1), backup.read(ByteArrayInputStream(xml.toByteArray())))
        assertEquals(ImportResult(failed = 1), backup.read(ByteArrayInputStream("not a track".toByteArray())))
    }

    @Test fun gpxExport_hidesPrivacyZones() = runBlocking {
        val backup = BackupManager(app, db, trips, app.container.settings)
        val id = trips.addRecording(sim().segment(), gap, 0)!!
        val t = trips.trip(id)!!
        db.zones().upsert(ZoneEntity(0, "Home", t.startLat, t.startLon, 400.0))
        val points = Gpx.parse(backup.gpx(id)).single().segments.flatten()
        assertTrue(points.isNotEmpty())
        assertTrue(points.all { com.canis.hejje.domain.Geo.distance(it.lat, it.lon, t.startLat, t.startLon) >= 399.0 })
    }

    @Test fun rawBuffer_survivesBeingKilled() = runBlocking {
        val dir = File(app.cacheDir, "raw-test").apply { deleteRecursively() }
        val points = DriveSim().drive(50, 10f).points
        val buffer = RawBuffer(dir, db.raw())
        points.take(20).forEach { buffer.append("s1", it) }
        buffer.flush()
        points.drop(20).forEach { buffer.append("s1", it) }
        assertEquals(20, db.raw().points("s1").size)
        // The app dies here: nothing was flushed. A new instance recovers the journal.
        File(dir, "s1.journal").appendBytes(byteArrayOf(1, 2, 3)) // plus a half-written record
        RawBuffer(dir, db.raw()).recover()
        val stored = db.raw().points("s1").map(RawBuffer::toRaw)
        assertEquals(points.size, stored.size)
        assertEquals(points.map { it.t }, stored.map { it.t })
        assertEquals(points.last().lat, stored.last().lat, 0.0)
        assertTrue(stored.all { it.pressure.isNaN() })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        // Recovering twice must not duplicate anything.
        RawBuffer(dir, db.raw()).recover()
        assertEquals(points.size, db.raw().points("s1").size)
    }

    @Test fun rawPoint_missingValuesRoundTripThroughTheDatabase() = runBlocking {
        db.raw().insert(listOf(RawBuffer.toEntity("s", RawPoint(5, 1.0, 2.0))))
        val p = RawBuffer.toRaw(db.raw().points("s").single())
        assertTrue(p.alt.isNaN() && p.speed.isNaN() && p.acc.isNaN())
    }

    @Test fun githubRelease_isParsedIntoAnUpdate() {
        val json = """{"tag_name":"v1.4.0","body":"- Faster maps\n- Fixes","draft":false,"prerelease":false,"html_url":"x",
            "assets":[{"name":"notes.txt","size":3,"browser_download_url":"https://e/x.txt"},
            {"name":"hejje-1.4.0.apk","size":26000000,"browser_download_url":"https://e/hejje-1.4.0.apk","digest":"sha256:abc123"}]}"""
        val info = Updater.toInfo(HejjeJson.decodeFromString(GhRelease.serializer(), json))!!
        assertEquals("1.4.0", info.version)
        assertEquals("https://e/hejje-1.4.0.apk", info.url)
        assertEquals(26_000_000L, info.size)
        assertEquals("abc123", info.sha256)
        assertTrue(info.notes.startsWith("- Faster maps"))
        // Drafts, pre-releases and releases without an APK are ignored.
        assertNull(Updater.toInfo(GhRelease(tag = "v2.0.0", prerelease = true)))
        assertNull(Updater.toInfo(GhRelease(tag = "v2.0.0")))
        assertFalse(com.canis.hejje.domain.Version.isNewer(info.version, "1.4.0"))
    }

    @Test fun placeNames_areBuiltFromNominatimAnswers() {
        val json = """{"display_name":"x","address":{"road":"12th Main","suburb":"Indiranagar","city":"Bengaluru","state":"Karnataka"}}"""
        assertEquals("Indiranagar, Bengaluru", PlaceRepository.parse(json))
        assertEquals("NH 44, Hosur", PlaceRepository.parse("""{"address":{"road":"NH 44","town":"Hosur"}}"""))
        assertEquals("", PlaceRepository.parse("""{"error":"Unable to geocode"}"""))
        assertEquals("", PlaceRepository.parse("garbage"))
    }
}
