package com.canis.hejje.recording

import android.content.Context
import com.canis.hejje.R
import com.canis.hejje.data.RawBuffer
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

/** Turns the raw fixes of a finished recording into a stored trip and discards the raw data. */
class TripFinalizer(
    private val context: Context,
    private val rawDao: RawDao,
    private val trips: TripRepository,
    private val settings: SettingsStore,
) {
    suspend fun finalize(session: String, start: Long, end: Long): Long? = withContext(Dispatchers.Default) {
        val raw = rawDao.points(session).map(RawBuffer::toRaw)
        val s = settings.current
        val segment = TripProcessor.process(raw, start, end, ProcessorConfig(s.brakeThreshold, s.accelThreshold))
        if (segment == null) {
            rawDao.delete(session)
            if (end - start > 60_000) {
                settings.update { it.copy(lastProblem = context.getString(R.string.problem_no_gps), lastProblemAt = System.currentTimeMillis()) }
            }
            return@withContext null
        }
        val tripId = trips.addRecording(segment, s.mergeGapMs, s.minTripM)
        rawDao.delete(session) // only after the trip is safely stored
        val route = segment.data.route
        if (route.size > 0) {
            settings.update { it.copy(parkedLat = route.lat.last(), parkedLon = route.lon.last(), parkedAt = end) }
        }
        if (tripId != null) {
            SummaryWorker.schedule(context, tripId, s)
            PlaceWorker.enqueue(context)
        }
        HejjeWidget.refresh(context)
        tripId
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
