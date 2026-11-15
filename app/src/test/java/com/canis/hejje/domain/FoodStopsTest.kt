package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class FoodStopsTest {
    private val zone = ZoneOffset.UTC
    private fun at(h: Int, m: Int = 0) = LocalDate.of(2026, 11, 16).atTime(LocalTime.of(h, m)).toInstant(zone).toEpochMilli()

    @Test fun mealTimes_andNotAgainAfterEating() {
        assertTrue(FoodStops.wanted(Meal.FOOD, at(13), lastHad = 0, zone))
        assertFalse("not lunch time", FoodStops.wanted(Meal.FOOD, at(16), lastHad = 0, zone))
        assertFalse("already had lunch", FoodStops.wanted(Meal.FOOD, at(14), lastHad = at(12, 40), zone))
        assertTrue("breakfast does not count for dinner", FoodStops.wanted(Meal.FOOD, at(20), lastHad = at(9), zone))
        assertTrue(FoodStops.wanted(Meal.COFFEE, at(16), lastHad = at(10), zone))
        assertFalse("2 am: no food time", FoodStops.wanted(Meal.FOOD, at(2), lastHad = 0, zone))
    }

    @Test fun askRules_quietAtYourPlaces_atDismissedSpots_andAfterThreeADay() {
        val home = NamedPlace(1, "Home", 12.9, 77.6, 150.0)
        assertFalse(FoodStops.shouldAsk(12.9, 77.6, at(13), listOf(home), emptyList(), zone))
        assertTrue(FoodStops.shouldAsk(13.0, 77.7, at(13), listOf(home), emptyList(), zone))
        // Swiped away at this spot once: never asked there again.
        assertFalse(FoodStops.shouldAsk(13.0, 77.7, at(13), emptyList(), listOf(StopAsk(at(9) - 86_400_000L * 30, 13.0, 77.7, dismissed = true)), zone))
        // Same spot within 2 hours (car moved, restarted): not twice.
        assertFalse(FoodStops.shouldAsk(13.0, 77.7, at(13), emptyList(), listOf(StopAsk(at(12), 13.0, 77.7, dismissed = false)), zone))
        val three = listOf(StopAsk(at(8), 13.2, 77.1, false), StopAsk(at(10), 13.3, 77.2, false), StopAsk(at(11), 13.4, 77.3, false))
        assertFalse("three asked today already", FoodStops.shouldAsk(13.0, 77.7, at(13), emptyList(), three, zone))
    }

    @Test fun announcement_isShortAndSpoken() {
        assertEquals("Kamat Upachar, 6 kilometres ahead, for food.", FoodStops.announcement("Kamat Upachar", Meal.FOOD, 6_240.0, miles = false))
        assertEquals("Café Coffee Day, 1.5 kilometres ahead, for coffee.", FoodStops.announcement("Café Coffee Day", Meal.COFFEE, 1_400.0, miles = false))
        assertEquals("Food stop, 1 kilometre ahead.", FoodStops.announcement(null, Meal.FOOD, 1_100.0, miles = false))
        assertEquals("Kamat Upachar, 4 miles ahead, for food.", FoodStops.announcement("Kamat Upachar", Meal.FOOD, 6_240.0, miles = true))
    }

    @Test fun ahead_onlyOnYourOwnRoad_inThisDirection_aboutFiveMinutesAway() {
        val lat0 = 12.9716
        val lon0 = 77.5946
        fun north(m: Double) = lat0 + m / 110_540.0
        // An earlier trip drove north 10 km and stopped for food at the end.
        val earlier = TripTrack(1, listOf(DriveSim().apply { heading = 0.0 }.drive(500, 20f).segment()))
        val end = earlier.segments.single().data.route
        // Saved where that trip parked.
        val kamat = FoodPlace(7, "Kamat Upachar", Meal.FOOD, end.lat.last(), end.lon.last())
        val parkedAt = (end.lat.last() - lat0) * 110_540.0
        val tracks = listOf(earlier)
        // 4 km before it at 20 m/s (72 km/h): 5 minutes covers 6 km, so it is announced.
        val hit = FoodStops.ahead(north(parkedAt - 4_000.0), lon0, 20f, listOf(kamat), tracks)
        assertNotNull(hit)
        assertEquals(4_000.0, hit!!.second, 120.0)
        // 8 km away at 20 m/s: not yet.
        assertNull(FoodStops.ahead(north(parkedAt - 8_000.0), lon0, 20f, listOf(kamat), tracks))
        // A place you said "not again" about: never.
        assertNull(FoodStops.ahead(north(parkedAt - 4_000.0), lon0, 20f, listOf(kamat.copy(liked = false)), tracks))
        // A place nearby but not on any road you drove to it: silent.
        val offRoad = FoodPlace(8, "Somewhere", Meal.FOOD, north(9_000.0), lon0 + 0.02)
        assertNull(FoodStops.ahead(north(parkedAt - 4_000.0), lon0, 20f, listOf(offRoad), tracks))
    }
}
