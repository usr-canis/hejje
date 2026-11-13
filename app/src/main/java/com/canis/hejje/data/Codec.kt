package com.canis.hejje.data

import com.canis.hejje.data.db.SegmentEntity
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.domain.Geo
import com.canis.hejje.domain.HejjeJson
import com.canis.hejje.domain.Segment
import com.canis.hejje.domain.SegmentData
import com.canis.hejje.domain.Simplifier
import com.canis.hejje.domain.StatsCalculator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max

object Codec {
    fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }

    fun encode(data: SegmentData): ByteArray = gzip(HejjeJson.encodeToString(SegmentData.serializer(), data))

    fun decode(bytes: ByteArray): SegmentData = HejjeJson.decodeFromString(SegmentData.serializer(), gunzip(bytes))

    fun toSegment(e: SegmentEntity): Segment = Segment(e.startTime, e.endTime, decode(e.data))

    fun toEntity(tripId: Long, s: Segment): SegmentEntity = SegmentEntity(0, tripId, s.start, s.end, encode(s.data))

    /** Up to [maxPoints] route points scaled into a 0..255 square (north up), keeping proportions. */
    fun thumbnail(segments: List<Segment>, maxPoints: Int = 48): ByteArray {
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        for (s in segments) {
            s.data.route.lat.forEach { lat += it }
            s.data.route.lon.forEach { lon += it }
        }
        if (lat.isEmpty()) return ByteArray(0)
        val keep = Simplifier.thin(lat.size, maxPoints)
        val xs = DoubleArray(keep.size) { Geo.x(lon[keep[it]], lon[0], lat[0]) }
        val ys = DoubleArray(keep.size) { Geo.y(lat[keep[it]], lat[0]) }
        val minX = xs.min()
        val minY = ys.min()
        val span = max(max(xs.max() - minX, ys.max() - minY), 1.0)
        val offX = (span - (xs.max() - minX)) / 2
        val offY = (span - (ys.max() - minY)) / 2
        val out = ByteArray(keep.size * 2)
        for (i in keep.indices) {
            out[i * 2] = ((xs[i] - minX + offX) / span * 255).toInt().coerceIn(0, 255).toByte()
            out[i * 2 + 1] = (255 - (ys[i] - minY + offY) / span * 255).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Builds the stored trip row from its segments, keeping the user's own fields from [base]. */
    fun buildTrip(base: TripEntity?, uuid: String, segments: List<Segment>): TripEntity {
        val s = StatsCalculator.compute(segments)
        val sorted = segments.sortedBy { it.start }
        val first = sorted.firstOrNull { it.data.route.size > 0 }?.data?.route
        val last = sorted.lastOrNull { it.data.route.size > 0 }?.data?.route
        val startLat = first?.lat?.first() ?: 0.0
        val startLon = first?.lon?.first() ?: 0.0
        val endLat = last?.lat?.last() ?: 0.0
        val endLon = last?.lon?.last() ?: 0.0
        // A place name stays valid only while that end of the trip has not moved.
        fun same(aLat: Double, aLon: Double, bLat: Double, bLon: Double) = Geo.distance(aLat, aLon, bLat, bLon) < 150
        return TripEntity(
            id = base?.id ?: 0,
            uuid = uuid,
            startTime = s.start,
            endTime = s.end,
            distanceM = s.distanceM,
            movingMs = s.movingMs,
            idleMs = s.idleMs,
            stoppedMs = s.stoppedMs,
            maxSpeed = s.maxSpeed,
            stopCount = s.stops.size,
            stopMs = s.stopMs,
            elevGain = s.elevGain,
            elevLoss = s.elevLoss,
            altMin = s.altMin.takeUnless { it.isNaN() },
            altMax = s.altMax.takeUnless { it.isNaN() },
            hardBrakes = s.hardBrakes,
            hardAccels = s.hardAccels,
            smoothness = s.smoothness,
            bands = s.bandsMs.joinToString(","),
            nightShare = s.nightShare,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            startPlace = base?.startPlace?.takeIf { same(base.startLat, base.startLon, startLat, startLon) },
            endPlace = base?.endPlace?.takeIf { same(base.endLat, base.endLon, endLat, endLon) },
            note = base?.note ?: "",
            thumb = thumbnail(sorted),
            segmentCount = sorted.size,
            baro = sorted.any { it.data.baro },
            circuity = s.shape.circuity.takeUnless { it.isNaN() },
            twistiness = s.shape.twistiness.takeUnless { it.isNaN() },
        )
    }
}
