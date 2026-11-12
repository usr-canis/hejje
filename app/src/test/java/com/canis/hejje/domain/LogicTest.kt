package com.canis.hejje.domain

import com.canis.hejje.domain.RecordingStateMachine.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class LogicTest {
    @Test fun version_compare() {
        assertTrue(Version.isNewer("v1.2.0", "1.1.9"))
        assertTrue(Version.isNewer("1.10.0", "1.9.9"))
        assertTrue(Version.isNewer("2.0", "1.99.99"))
        assertFalse(Version.isNewer("v1.2.0", "1.2.0"))
        assertFalse(Version.isNewer("1.2", "1.2.0"))
        assertFalse(Version.isNewer("1.1.0", "1.2.0"))
        assertTrue(Version.isNewer("1.2.0", "1.2.0-beta1"))
        assertFalse(Version.isNewer("1.2.0-beta1", "1.2.0"))
        assertTrue(Version.isNewer("1.2.1", "1.2.0-debug"))
        assertFalse(Version.isNewer("garbage", "0.1.0"))
    }

    private val grace = 25_000L

    @Test fun bluetooth_connectStarts_disconnectStopsAfterGrace() {
        val sm = RecordingStateMachine(graceMs = grace)
        assertEquals(listOf(Effect.Start(1000, false)), sm.onCarConnected(1000, autoRecord = true))
        assertEquals(listOf(Effect.Schedule(60_000 + grace)), sm.onCarDisconnected(60_000))
        assertTrue(sm.state.recording)
        // Timer firing early re-arms itself; nothing stops yet.
        assertEquals(listOf(Effect.Schedule(60_000 + grace)), sm.onTimer(70_000))
        // The recording ends at the disconnect, not when the grace period runs out.
        assertEquals(listOf(Effect.Stop(60_000)), sm.onTimer(60_000 + grace))
        assertFalse(sm.state.recording)
    }

    @Test fun bluetooth_flappingDoesNotSplitTheDrive() {
        val sm = RecordingStateMachine(graceMs = grace)
        sm.onCarConnected(0, true)
        repeat(5) { i ->
            val t = 10_000L + i * 30_000
            assertEquals(1, sm.onCarDisconnected(t).size)
            assertEquals(emptyList<Effect>(), sm.onCarDisconnected(t + 500)) // duplicate event
            assertEquals(listOf(Effect.CancelSchedule), sm.onCarConnected(t + 5_000, true))
            assertEquals(emptyList<Effect>(), sm.onTimer(t + grace))
        }
        assertTrue(sm.state.recording)
        assertEquals(0L, sm.state.startedAt)
    }

    @Test fun autoRecordOff_ignoresCar_butManualWorks() {
        val sm = RecordingStateMachine(graceMs = grace)
        assertTrue(sm.onCarConnected(0, autoRecord = false).isEmpty())
        assertFalse(sm.state.recording)
        assertEquals(listOf(Effect.Start(5, true)), sm.onManualStart(5))
        // The car was connected during this manual drive, so its disconnect ends it.
        sm.onCarDisconnected(100)
        assertEquals(listOf(Effect.Stop(100)), sm.onTimer(100 + grace))
    }

    @Test fun manualDrive_withoutCar_runsUntilStopped() {
        val sm = RecordingStateMachine(graceMs = grace)
        sm.onManualStart(0)
        assertTrue(sm.onManualStart(1).isEmpty())
        assertTrue(sm.onCarDisconnected(50).isEmpty())
        assertTrue(sm.onTimer(1_000_000).isEmpty())
        assertEquals(listOf(Effect.CancelSchedule, Effect.Stop(2_000)), sm.onManualStop(2_000))
        assertTrue(sm.onManualStop(3_000).isEmpty())
    }

    @Test fun manualStop_whileConnected_doesNotRestartUntilNextConnect() {
        val sm = RecordingStateMachine(graceMs = grace)
        sm.onCarConnected(0, true)
        sm.onManualStop(500)
        assertFalse(sm.state.recording)
        assertTrue(sm.state.carConnected)
        assertTrue(sm.onCarDisconnected(600).isEmpty())
        assertEquals(listOf(Effect.Start(700, false)), sm.onCarConnected(700, true))
    }

    @Test fun stateSurvivesRestart() {
        val sm = RecordingStateMachine(graceMs = grace)
        sm.onCarConnected(0, true)
        sm.onCarDisconnected(1_000)
        val restored = RecordingStateMachine(sm.state.copy(), grace)
        assertEquals(listOf(Effect.Stop(1_000)), restored.onTimer(1_000 + grace))
    }

    @Test fun simulatedDay_bluetoothAndGps_makeOneTripWithTwoSegments() {
        // Drive, stop for fuel with the engine off for 10 minutes, drive on.
        val sm = RecordingStateMachine(graceMs = grace)
        val segments = ArrayList<Segment>()
        val sim = DriveSim()
        var start = 0L
        fun apply(effects: List<Effect>) = effects.forEach { e ->
            when (e) {
                is Effect.Start -> start = e.at
                is Effect.Stop -> segments += TripProcessor.process(sim.points, start, e.endAt)!!
                else -> Unit
            }
        }
        apply(sm.onCarConnected(sim.t, true))
        sim.drive(300, 15f).drive(10, 0f, accel = 2f)
        val off = sim.t
        apply(sm.onCarDisconnected(off))
        sim.idle(30) // phone still logs while we wait out the grace period
        apply(sm.onTimer(off + grace))
        sim.points.clear()
        sim.t += 10 * 60_000L
        assertTrue(TripEditor.continuesTrip(segments.last().end, sim.t, 90 * 60_000L))
        apply(sm.onCarConnected(sim.t, true))
        sim.drive(300, 15f).drive(10, 0f, accel = 2f)
        apply(sm.onCarDisconnected(sim.t))
        apply(sm.onTimer(sim.t + grace))

        assertEquals(2, segments.size)
        assertEquals(off, segments[0].end)
        val stats = StatsCalculator.compute(segments)
        assertEquals(1, stats.stops.count { it.parked })
        assertEquals(10 * 60_000L + 30_000L, stats.stoppedMs)
        assertEquals(stats.totalMs, stats.movingMs + stats.idleMs + stats.stoppedMs)
    }

    @Test fun insights_weeksStartOnMonday_andStreaks() {
        val zone = ZoneId.of("Asia/Kolkata")
        val day = 86_400_000L
        fun trip(id: Long, dayOffset: Int, km: Double, tags: List<String> = emptyList(), place: String? = null) =
            TripSummary(id, DriveSim.T0 + dayOffset * day, DriveSim.T0 + dayOffset * day + 1_800_000, km * 1000, 1_500_000, 20f + id, place, tags)
        // T0 is Monday 5 Jan 2026. Sunday 4 Jan belongs to the previous week.
        val trips = listOf(
            trip(1, -1, 10.0, listOf("Work"), "Office"), trip(2, 0, 20.0, listOf("Work"), "Office"),
            trip(3, 1, 30.0, listOf("Errand"), "Market"), trip(4, 2, 5.0), trip(5, 2, 7.0, place = "Office"), trip(6, -10, 100.0),
        )
        val today = LocalDate.of(2026, 1, 7)
        val d = Insights.compute(trips, today, zone)
        assertEquals(LocalDate.of(2026, 1, 5), Insights.weekStart(today))
        assertEquals(LocalDate.of(2026, 1, 5), Insights.weekStart(LocalDate.of(2026, 1, 11)))
        assertEquals(4, d.week.trips)
        assertEquals(62_000.0, d.week.distanceM, 0.1)
        assertEquals(1, d.lastWeek.trips)
        assertEquals(5, d.month.trips)
        assertEquals(6, d.allTime.trips)
        assertEquals(6L, d.longest?.id)
        assertEquals(6L, d.topSpeed?.id)
        assertEquals(4, d.currentStreak)
        assertEquals(4, d.longestStreak)
        assertEquals("Office" to 3, d.places.first())
        assertEquals(TagStat("Work", 2, 30_000.0), d.tags.first())
        assertEquals(12_000.0, d.daily[today]!!, 0.1)
        // A day without driving keeps yesterday's streak alive until the day is over.
        assertEquals(4, Insights.compute(trips, today.plusDays(1), zone).currentStreak)
        assertEquals(0, Insights.compute(trips, today.plusDays(2), zone).currentStreak)
    }

    @Test fun formatter_friendlyNumbers() {
        val f = Formatter(miles = false, locale = Locale.US)
        assertEquals("42.6 km", f.distance(42_630.0))
        assertEquals("0.4 km", f.distance(420.0))
        assertEquals("124 km", f.distance(124_400.0))
        assertEquals("1 h 24 m", f.duration(84 * 60_000L + 20_000))
        assertEquals("12 m", f.duration(12 * 60_000L))
        assertEquals("45 s", f.duration(45_000))
        assertEquals("72 km/h", f.speed(20f))
        assertEquals("30–60", f.band(1))
        assertEquals("120+", f.band(4))
        val mi = Formatter(miles = true, locale = Locale.US)
        assertEquals("26.5 mi", mi.distance(42_630.0))
        assertEquals("45 mph", mi.speed(20f))
        assertEquals("328 ft", mi.height(100f))
        val now = DriveSim.T0
        val zone = ZoneId.of("UTC")
        assertEquals("Just now", f.relative(now - 20_000, now, zone))
        assertEquals("5 min ago", f.relative(now - 5 * 60_000, now, zone))
        assertEquals("2 h ago", f.relative(now - 2 * 3_600_000, now, zone))
        assertEquals("Yesterday, 12:30", f.relative(now - 18 * 3_600_000, now, zone))
        assertEquals("Mon, 5 Jan", f.dayLabel(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9)))
        assertEquals("Today", f.dayLabel(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 9)))
    }
}
