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
        val json = ReplayData.build(app, app.container, id, weatherOf = { null })!!
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
    }
}
