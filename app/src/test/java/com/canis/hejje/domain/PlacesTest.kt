package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private val home = NamedPlace(1, "Home", 12.9716, 77.5946, 200.0, icon = "home")
    private val work = NamedPlace(2, "Work", 12.9352, 77.6245, 300.0, icon = "work", hide = false)
    private val gym = NamedPlace(3, "Gym", 12.9600, 77.6400, 100.0, icon = "gym", hide = false, tag = "Fitness")

    /** A point [north] metres north and [east] metres east of [p]. */
    private fun near(p: NamedPlace, north: Double, east: Double = 0.0) =
        p.lat + north / 110_540.0 to p.lon + east / (111_320.0 * kotlin.math.cos(Math.toRadians(p.lat)))

    @Test fun placeAt_findsTheNearestPlaceWithinItsRadius() {
        val (lat, lon) = near(home, 150.0)
        assertEquals("Home", Places.placeAt(lat, lon, listOf(home, work))?.name)
        val (fLat, fLon) = near(home, 250.0)
        assertNull(Places.placeAt(fLat, fLon, listOf(home, work)))
        // Overlapping places: the closer one wins.
        val big = home.copy(id = 9, name = "Neighbourhood", radiusM = 2_000.0, lat = home.lat + 0.005)
        assertEquals("Home", Places.placeAt(home.lat, home.lon, listOf(big, home))?.name)
    }

    @Test fun autoTags_commuteBothWays_andPlaceTags() {
        assertEquals(setOf("Commute"), Places.autoTags(home, work))
        assertEquals(setOf("Commute"), Places.autoTags(work, home))
        assertEquals(setOf("Fitness"), Places.autoTags(home, gym))
        assertTrue(Places.autoTags(home, home).isEmpty())
        assertTrue(Places.autoTags(null, null).isEmpty())
        assertEquals(setOf("Fitness"), Places.autoTags(null, gym))
    }

    @Test fun zones_onlyPrivatePlaces() {
        assertEquals(1, Places.zones(listOf(home, work, gym)).size)
        assertEquals(3, Places.zones(listOf(home, work, gym), privateOnly = false).size)
    }

    @Test fun suggest_frequentParkingSpotsThatAreNotPlacesYet() {
        val market = NamedPlace(0, "", 12.9500, 77.6000, 0.0)
        val ends = buildList {
            repeat(4) { i -> val (a, b) = near(market, i * 20.0, i * 10.0); add(Triple(a, b, if (i < 3) "Jayanagar, Bengaluru" else null)) }
            repeat(5) { i -> val (a, b) = near(home, i * 15.0); add(Triple(a, b, "Indiranagar")) } // already a place
            repeat(2) { i -> val (a, b) = near(gym, 3_000.0 + i * 10); add(Triple(a, b, null)) } // only twice
        }
        val s = Places.suggest(ends, listOf(home, work, gym))
        assertEquals(1, s.size)
        assertEquals(4, s[0].visits)
        assertEquals("Jayanagar, Bengaluru", s[0].name)
        assertTrue(Geo.distance(s[0].lat, s[0].lon, market.lat, market.lon) < 80)
    }
}
