package com.canis.hejje.domain

import com.canis.hejje.replay.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReplayTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ist).toInstant().toEpochMilli()

    @Test fun sun_risesInBengaluruAtTheRightTime() {
        // Sunrise in Bengaluru in mid-November is about 06:20.
        val (noon, _) = Sun.position(12.97, 77.59, at(2026, 11, 14, 12, 0))
        val (night, _) = Sun.position(12.97, 77.59, at(2026, 11, 14, 0, 0))
        assertTrue("noon $noon", noon in 55.0..65.0)
        assertTrue("midnight $night", night < -40)
        val rise = (0 until 24 * 60).first { Sun.position(12.97, 77.59, at(2026, 11, 14, 0, 0) + it * 60_000L).first >= -0.833 }
        assertTrue("sunrise at minute $rise", rise in (6 * 60 + 10)..(6 * 60 + 30))
        val (_, az) = Sun.position(12.97, 77.59, at(2026, 11, 14, 6, 30))
        assertTrue("morning sun in the east-south-east, $az", az in 100.0..125.0)
    }

    /** A drive due north: [legs] of (metres, km/h, metres climbed), one point every 10 s. */
    private fun drive(start: Long, vararg legs: Triple<Double, Double, Double>): List<Gpx.Point> {
        val out = ArrayList<Gpx.Point>()
        var lat = 12.9
        var ele = 800.0
        var t = start
        out += Gpx.Point(lat, 77.6, ele.toFloat(), t)
        for ((metres, kmh, climb) in legs) {
            val step = kmh / 3.6 * 10
            val n = (metres / step).toInt()
            repeat(n) { lat += step / 111_320.0; ele += climb / n; t += 10_000; out += Gpx.Point(lat, 77.6, ele.toFloat(), t) }
        }
        return out
    }

    @Test fun highlights_showOnlyWhatTheTripReallyHad() {
        val pts = Replay.points(listOf(drive(at(2026, 11, 14, 11, 0), Triple(2000.0, 10.0, 0.0), Triple(20_000.0, 90.0, 50.0), Triple(2000.0, 122.0, 100.0))))
        val h = Replay.highlights(pts, topSpeed = 122 / 3.6f, altMin = 800f, altMax = 950f)
        val kinds = h.map { it.kind }.toSet()
        assertEquals(setOf(Replay.Kind.TOP, Replay.Kind.HIGH, Replay.Kind.STEADY, Replay.Kind.SLOW), kinds)
        assertEquals(122.0, h.first { it.kind == Replay.Kind.TOP }.value * 3.6, 0.5)
        val steady = h.first { it.kind == Replay.Kind.STEADY }
        assertEquals(90.0, steady.value * 3.6, 3.0)
        assertTrue("steady ${steady.lengthM}", steady.lengthM > 15_000)
        assertEquals(10.0, h.first { it.kind == Replay.Kind.SLOW }.value * 3.6, 2.0)
        assertTrue("ordered along the route", h.zipWithNext().all { (a, b) -> a.d <= b.d })
    }

    @Test fun highlights_skipSmallClimbsAndKeepFourAtMost() {
        // Flat, and through sunrise: sunrise takes the place of the slowest stretch.
        val pts = Replay.points(listOf(drive(at(2026, 11, 14, 5, 50), Triple(2000.0, 10.0, 0.0), Triple(30_000.0, 90.0, 20.0), Triple(2000.0, 122.0, 0.0))))
        val h = Replay.highlights(pts, topSpeed = 122 / 3.6f, altMin = 800f, altMax = 820f)
        val kinds = h.map { it.kind }
        assertTrue(Replay.Kind.SUNRISE in kinds)
        assertFalse("20 m is no climb", Replay.Kind.HIGH in kinds)
        assertTrue(h.size <= Replay.MAX_HIGHLIGHTS)
    }

    @Test fun points_keepPrivateCutsAsSeparatePieces() {
        val a = drive(at(2026, 11, 14, 11, 0), Triple(3000.0, 60.0, 0.0))
        val b = drive(at(2026, 11, 14, 11, 10), Triple(3000.0, 60.0, 0.0)).map { Gpx.Point(it.lat + .1, it.lon, it.ele, it.time) }
        val pts = Replay.points(listOf(a, b))
        assertEquals(setOf(0, 1), pts.map { it.piece }.toSet())
        // The hidden gap adds no distance.
        assertEquals(2 * 17 * (60 / 3.6 * 10), pts.last().d, 30.0)
    }

    @Test fun weather_readsOneOrManyPlaces_andGivesNothingWhenEmpty() {
        val one = """{"hourly":{"time":["2026-11-13T15:00","2026-11-13T16:00"],"weather_code":[51,3],"cloud_cover":[64,96],"precipitation":[0.1,0.0],"visibility":[9560,11260]}}"""
        val hours = Weather.parse(one, listOf(Pair(1_794_582_000L, 0), Pair(1_794_585_600L, 0)))
        assertNotNull(hours)
        assertEquals(2, hours!!.size)
        assertEquals(64.0, hours[0].cloud, 0.0)
        assertEquals(51, hours[0].code)
        assertNull(Weather.parse("""{"hourly":{"time":[],"cloud_cover":[]}}""", listOf(Pair(1_794_582_000L, 0))))
    }

    @Test fun weather_asksForRoughAreasOnly() {
        val pts = Replay.points(listOf(drive(at(2026, 11, 14, 11, 0), Triple(40_000.0, 60.0, 0.0))))
        val (url, perHour) = Weather.plan(pts, nowMs = at(2026, 11, 17, 9, 0))!!
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        // Coordinates are rounded to 0.1 degree.
        val lats = Regex("latitude=([^&]+)").find(url)!!.groupValues[1].split(",")
        assertTrue(lats.all { it.substringAfter('.', "").length <= 1 })
        assertTrue(perHour.isNotEmpty())
        val old = Weather.plan(pts, nowMs = at(2027, 6, 1, 9, 0))!!.first
        assertTrue(old.startsWith("https://archive-api.open-meteo.com/v1/archive?"))
    }
}
