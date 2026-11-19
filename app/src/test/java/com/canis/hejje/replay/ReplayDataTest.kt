package com.canis.hejje.replay

import androidx.test.core.app.ApplicationProvider
import com.canis.hejje.HejjeApp
import com.canis.hejje.domain.DriveSim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The data the replay page reads, built from a recorded trip in the real database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReplayDataTest {
    @Test fun build_givesThePageEverythingItNeeds() = runBlocking {
        val app: HejjeApp = ApplicationProvider.getApplicationContext()
        val sim = DriveSim(t = System.currentTimeMillis() - 3_600_000, lat = 12.95, lon = 77.6).apply { heading = 20.0 }
        sim.drive(300, 14f).drive(200, 30f, climbPerSec = .6f).drive(120, 6f).drive(20, 0f, accel = 1.5f)
        val id = app.container.trips.insertTrip("r1", listOf(sim.segment()), "", null, null, emptyList())
        val trip = ReplayData.load(app, app.container, id)!!
        val prepared = ReplayData.build(app, app.container, trip, weatherOf = { null })
        val json = prepared.json
        assertTrue(prepared.outline.size >= 4 && prepared.outline.all { it in 0f..1f })
        val root = Json.parseToJsonElement(json).jsonObject
        val route = root["route"]!!.jsonArray
        assertTrue("points ${route.size}", route.size > 50)
        assertEquals(7, route[0].jsonArray.size)
        // Without weather data the page is told so, rather than given made-up weather.
        assertEquals(JsonNull, root["weather"])
        assertEquals("Total", root["total"]!!.jsonObject["key"]!!.jsonPrimitive.content)
        assertTrue(root["total"]!!.jsonObject["value"]!!.jsonPrimitive.content.endsWith(" km"))
        assertEquals("ಹೆಜ್ಜೆ", root["brand"]!!.jsonPrimitive.content)
        val kinds = root["highlights"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertTrue("top speed always shows: $kinds", "TOP" in kinds)
        assertTrue(root["highlights"]!!.jsonArray.all { it.jsonObject["value"]!!.jsonPrimitive.content.isNotBlank() })
        // The whole trip comes first among the suggested parts, then one around each highlight.
        assertEquals(trip.highlights.size + 1, trip.suggestions.size)
        assertEquals(0.0, trip.suggestions[0].from, 0.0)
        assertEquals(trip.lengthM, trip.suggestions[0].to, 0.0)
    }

    @Test fun build_aPartStartsAtZeroAndCountsOnlyItsOwnDistance() = runBlocking {
        val app: HejjeApp = ApplicationProvider.getApplicationContext()
        val sim = DriveSim(t = System.currentTimeMillis() - 3_600_000, lat = 12.95, lon = 77.6).apply { heading = 20.0 }
        sim.drive(600, 20f)
        val id = app.container.trips.insertTrip("r2", listOf(sim.segment()), "", null, null, emptyList())
        val trip = ReplayData.load(app, app.container, id)!!
        val from = trip.lengthM * .25
        val to = trip.lengthM * .75
        val root = Json.parseToJsonElement(ReplayData.build(app, app.container, trip, from, to, weatherOf = { null }).json).jsonObject
        val route = root["route"]!!.jsonArray
        assertEquals(0.0, route.first().jsonArray[2].jsonPrimitive.content.toDouble(), 0.0)
        val partLength = route.last().jsonArray[2].jsonPrimitive.content.toDouble()
        assertTrue("part $partLength of ${trip.lengthM}", partLength in (to - from) * .95..(to - from) * 1.01)
        // A part in the middle doesn't claim the trip's start or end place.
        assertTrue("startName" !in root && "endName" !in root)
    }
}
