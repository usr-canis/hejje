package com.canis.hejje.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingTest {
    private fun trip() = DriveSim().drive(300, 15f).drive(10, 0f, accel = 4.5f).idle(60).drive(300, 20f).segment()

    @Test fun split_keepsTotals() {
        val seg = trip()
        val whole = StatsCalculator.compute(listOf(seg))
        val (a, b) = TripEditor.split(listOf(seg), seg.start + 200_000)
        val sa = StatsCalculator.compute(a)
        val sb = StatsCalculator.compute(b)
        assertEquals(whole.distanceM, sa.distanceM + sb.distanceM, 0.5)
        assertEquals(whole.movingMs, sa.movingMs + sb.movingMs)
        assertEquals(whole.totalMs, sa.totalMs + sb.totalMs)
        assertEquals(whole.hardBrakes, sa.hardBrakes + sb.hardBrakes)
        assertEquals(seg.start + 200_000, sa.end)
        assertEquals(sa.end, sb.start)
        // Both halves meet at the same point on the road.
        val ra = a[0].data.route
        val rb = b[0].data.route
        assertEquals(ra.lat.last(), rb.lat.first(), 1e-9)
        assertEquals(0, rb.t.first())
        assertTrue(rb.t.all { it >= 0 } && ra.t.all { it <= 200 })
    }

    @Test fun trim_removesStartAndEnd() {
        val seg = trip()
        val whole = StatsCalculator.compute(listOf(seg))
        val trimmed = TripEditor.trim(listOf(seg), seg.start + 60_000, seg.end - 60_000)
        val s = StatsCalculator.compute(trimmed)
        assertEquals((whole.totalMs - 120_000).toDouble(), s.totalMs.toDouble(), 5_000.0)
        assertTrue(s.distanceM < whole.distanceM - 1500)
        assertEquals(1, s.hardBrakes)
    }

    @Test fun merge_recordsGapAsParkedStop_andSplitUndoesIt() {
        val first = DriveSim().drive(300, 15f)
        val a = first.segment()
        val b = DriveSim(t = first.t + 20 * 60_000L, lat = first.lat, lon = first.lon).drive(300, 15f).segment()
        val gap = b.start - a.end
        val merged = TripEditor.merge(listOf(b), listOf(a))
        val s = StatsCalculator.compute(merged)
        assertEquals(gap, s.stoppedMs)
        assertEquals(1, s.stops.count { it.parked })
        assertEquals(a.durationMs + b.durationMs + gap, s.totalMs)
        assertEquals(StatsCalculator.compute(listOf(a)).distanceM + StatsCalculator.compute(listOf(b)).distanceM, s.distanceM, 0.01)
        // Splitting in the gap gives the two original trips back.
        val (x, y) = TripEditor.split(merged, a.end + gap / 2)
        assertEquals(1, x.size)
        assertEquals(1, y.size)
        assertEquals(0L, StatsCalculator.compute(x).stoppedMs)
    }

    @Test fun mergeGap_rule() {
        val gap = 90 * 60_000L
        assertTrue(TripEditor.continuesTrip(1_000_000, 1_000_000 + gap, gap))
        assertFalse(TripEditor.continuesTrip(1_000_000, 1_000_001 + gap, gap))
        assertFalse(TripEditor.continuesTrip(1_000_000, 1_000_500, 0))
        assertFalse(TripEditor.continuesTrip(1_000_000, 900_000, gap))
    }

    @Test fun privacyZone_cutsRouteAtBoundary() {
        val sim = DriveSim().drive(400, 15f)
        val route = sim.segment().data.route
        val mid = route.size / 2
        val zone = Zone(route.lat[mid], route.lon[mid], 300.0)
        val pieces = PrivacyZones.trim(route, listOf(zone))
        assertEquals(2, pieces.size)
        for (p in pieces) for (i in 0 until p.size) {
            assertTrue(Geo.distance(p.lat[i], p.lon[i], zone.lat, zone.lon) >= zone.radiusM - 0.5)
        }
        // The cut ends sit on the boundary, not far outside it.
        assertEquals(300.0, Geo.distance(pieces[0].lat.last(), pieces[0].lon.last(), zone.lat, zone.lon), 1.5)
        assertEquals(300.0, Geo.distance(pieces[1].lat.first(), pieces[1].lon.first(), zone.lat, zone.lon), 1.5)
    }

    @Test fun privacyZone_atStartAndWhollyInside() {
        val route = DriveSim().drive(400, 15f).segment().data.route
        val home = Zone(route.lat[0], route.lon[0], 250.0)
        val pieces = PrivacyZones.trim(route, listOf(home))
        assertEquals(1, pieces.size)
        assertEquals(250.0, Geo.distance(pieces[0].lat[0], pieces[0].lon[0], home.lat, home.lon), 1.5)
        assertTrue(PrivacyZones.trim(route, listOf(Zone(route.lat[0], route.lon[0], 50_000.0))).isEmpty())
        assertEquals(1, PrivacyZones.trim(route, emptyList()).size)
    }

    @Test fun gpx_roundTrip_andPrivacy() {
        val seg = DriveSim().drive(300, 15f).segment()
        val xml = Gpx.write("Test & <drive>", "Hejje", Gpx.pointsOf(listOf(seg), emptyList()))
        val tracks = Gpx.parse(xml)
        assertEquals(1, tracks.size)
        assertEquals("Test & <drive>", tracks[0].name)
        val pts = tracks[0].segments.single()
        assertEquals(seg.data.route.size, pts.size)
        val again = TripProcessor.process(pts, pts.first().t, pts.last().t)!!
        val a = StatsCalculator.compute(listOf(seg))
        val b = StatsCalculator.compute(listOf(again))
        assertEquals(a.distanceM, b.distanceM, a.distanceM * 0.02)
        assertEquals(a.start, b.start)

        val r = seg.data.route
        val zoned = Gpx.parse(Gpx.write("t", "Hejje", Gpx.pointsOf(listOf(seg), listOf(Zone(r.lat[0], r.lon[0], 400.0)))))
        assertTrue(zoned[0].segments.single().all { Geo.distance(it.lat, it.lon, r.lat[0], r.lon[0]) >= 399.0 })
    }

    @Test fun gpx_readsOtherApps() {
        val xml = """<gpx><trk><name>Morning</name><trkseg>
            <trkpt lon="77.60" lat="12.97"><ele>900</ele><time>2026-01-05T06:30:00Z</time></trkpt>
            <trkpt lat='12.971' lon='77.601'><time>2026-01-05T12:00:10+05:30</time></trkpt>
            <trkpt lat="12.972" lon="77.602"/>
            </trkseg></trk></gpx>"""
        val pts = Gpx.parse(xml).single().segments.single()
        assertEquals(2, pts.size)
        assertEquals(900f, pts[0].alt, 0.01f)
        assertEquals(10_000L, pts[1].t - pts[0].t)
    }

    @Test fun backup_roundTripsWithMissingAltitude() {
        val seg = DriveSim().drive(60, 10f).segment()
        seg.data.profile.alt[0] = Float.NaN
        val file = BackupFile(exportedAt = 5, trips = listOf(BackupTrip("u1", "note", tags = listOf("Work"), segments = listOf(BackupSegment(seg.start, seg.end, seg.data)))))
        val back = HejjeJson.decodeFromString(BackupFile.serializer(), HejjeJson.encodeToString(BackupFile.serializer(), file))
        assertEquals(BackupFile.VERSION, back.version)
        val s = back.trips.single().segments.single()
        assertTrue(s.data.profile.alt[0].isNaN())
        assertEquals(
            StatsCalculator.compute(listOf(seg)).distanceM,
            StatsCalculator.compute(listOf(Segment(s.start, s.end, s.data))).distanceM, 1e-6,
        )
    }
}
