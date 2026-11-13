package com.canis.hejje.ui.trips

import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.domain.Formatter
import com.canis.hejje.domain.NamedPlace
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TripTitleTest {
    private val f = Formatter(locale = Locale.US)
    private val home = NamedPlace(1, "Home", 12.9716, 77.5946, 200.0, icon = "home")
    private val work = NamedPlace(2, "Work", 12.9352, 77.6245, 200.0, icon = "work")

    private fun trip(start: Pair<Double, Double>, end: Pair<Double, Double>, from: String?, to: String?) = TripEntity(
        uuid = "u", startTime = 0, endTime = 1, distanceM = 0.0, movingMs = 0, idleMs = 0, stoppedMs = 0, maxSpeed = 0f,
        stopCount = 0, stopMs = 0, elevGain = 0f, elevLoss = 0f, altMin = null, altMax = null, hardBrakes = 0, hardAccels = 0,
        smoothness = 0, bands = "", nightShare = 0f, startLat = start.first, startLon = start.second, endLat = end.first,
        endLon = end.second, startPlace = from, endPlace = to, thumb = ByteArray(0), segmentCount = 1, baro = false,
    )

    @Test fun namedPlacesWin_streetNamesLoseTheirCity_coordinatesStayWhole() {
        val elsewhere = 12.8967 to 77.6627
        assertEquals("Home → Work", tripTitle(trip(12.9716 to 77.5946, 12.9352 to 77.6245, "Indiranagar, Bengaluru", "HSR, Bengaluru"), f, listOf(home, work)))
        assertEquals("Home → Koramangala", tripTitle(trip(12.9716 to 77.5946, elsewhere, null, "Koramangala, Bengaluru"), f, listOf(home)))
        assertEquals("Home → 12.8967, 77.6627", tripTitle(trip(12.9716 to 77.5946, elsewhere, null, null), f, listOf(home)))
        assertEquals("Indiranagar → Koramangala", tripTitle(trip(12.97 to 77.64, elsewhere, "Indiranagar, Bengaluru", "Koramangala, Bengaluru"), f))
    }
}
