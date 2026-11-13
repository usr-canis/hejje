package com.canis.hejje.recording

import android.content.Context
import com.canis.hejje.R
import com.canis.hejje.data.DriveLog
import com.canis.hejje.data.LogKind
import com.canis.hejje.data.RawBuffer
import com.canis.hejje.data.toPlace
import com.canis.hejje.domain.Places
import com.canis.hejje.data.SettingsStore
import com.canis.hejje.data.TripRepository
import com.canis.hejje.data.db.RawDao
import com.canis.hejje.domain.ProcessorConfig
import com.canis.hejje.domain.TripProcessor
import com.canis.hejje.work.PlaceWorker
import com.canis.hejje.work.SummaryWorker
import com.canis.hejje.widget.HejjeWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/** Turns the raw fixes of a finished recording into a stored trip and discards the raw data. */
class TripFinalizer(
    private val context: Context,
    private val rawDao: RawDao,
    private val trips: TripRepository,
    private val settings: SettingsStore,
    private val log: DriveLog? = null,
    private val zones: com.canis.hejje.data.db.ZoneDao? = null,
) {
    suspend fun finalize(session: String, start: Long, end: Long): Long? = withContext(Dispatchers.Default) {
        val raw = rawDao.points(session).map(RawBuffer::toRaw)
        val s = settings.current
        val segment = TripProcessor.process(raw, start, end, ProcessorConfig(s.brakeThreshold, s.accelThreshold))
        if (segment == null) {
            log?.add(LogKind.NO_GPS)
            rawDao.delete(session)
            if (end - start > 60_000) {
                settings.update { it.copy(lastProblem = context.getString(R.string.problem_no_gps), lastProblemAt = System.currentTimeMillis()) }
            }
            return@withContext null
        }
        val tripId = trips.addRecording(segment, s.mergeGapMs, s.minTripM)
        val distance = segment.data.profile.dist.sum().toDouble().roundToLong().toString()
        when {
            tripId == null -> log?.add(LogKind.TRIP_SHORT, distance)
            (trips.trip(tripId)?.startTime ?: segment.start) < segment.start -> log?.add(LogKind.TRIP_MERGED, distance, tripId)
            else -> log?.add(LogKind.TRIP_SAVED, distance, tripId)
        }
        rawDao.delete(session) // only after the trip is safely stored
        val route = segment.data.route
        if (route.size > 0) {
            settings.update { it.copy(parkedLat = route.lat.last(), parkedLon = route.lon.last(), parkedAt = end) }
        }
        if (tripId != null) {
            autoTag(tripId)
            SummaryWorker.schedule(context, tripId, s)
            PlaceWorker.enqueue(context)
        }
        HejjeWidget.refresh(context)
        tripId
    }

    /** Home <-> Work trips become "Commute", and a place's own tag goes on trips that end there. */
    private suspend fun autoTag(tripId: Long) {
        val dao = zones ?: return
        val trip = trips.trip(tripId) ?: return
        val places = dao.all().map { it.toPlace() }
        val tags = Places.autoTags(Places.placeAt(trip.startLat, trip.startLon, places), Places.placeAt(trip.endLat, trip.endLon, places))
        tags.forEach { trips.setTag(tripId, it, true) }
    }

    /** A recording whose end we never saw (crash, power loss): it ends at its last fix. */
    suspend fun finalizeOrphan(session: String) {
        val first = rawDao.points(session).firstOrNull() ?: return
        val last = rawDao.lastTime(session) ?: return
        if (last <= first.t) {
            rawDao.delete(session)
            return
        }
        finalize(session, first.t, last)
    }
}
