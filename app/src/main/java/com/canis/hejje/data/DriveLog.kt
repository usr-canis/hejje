package com.canis.hejje.data

import com.canis.hejje.data.db.LogDao
import com.canis.hejje.data.db.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What Hejje noticed and did, for the "Recent activity" list in Recording health. */
enum class LogKind {
    CAR_CONNECTED, CAR_DISCONNECTED, REC_STARTED, FIRST_FIX, GPS_LOST, GPS_REGAINED,
    RECOVERED, STALE_CLOSED, START_REFUSED, TRIP_SAVED, TRIP_MERGED, TRIP_SHORT, NO_GPS,
    LOCATION_OFF, SENSORS,
}

/**
 * The drive diagnostics log. Entries never contain coordinates and are deleted after 14 days.
 * Writing never blocks or fails the caller.
 */
class DriveLog(
    private val dao: LogDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun add(kind: LogKind, detail: String = "", tripId: Long? = null) {
        val entry = LogEntity(0, clock(), kind.name, detail, tripId)
        scope.launch { runCatching { dao.insert(entry) } }
    }

    fun observe(): Flow<List<LogEntity>> = dao.observe(clock() - KEEP_MS)

    suspend fun recent(): List<LogEntity> = dao.recent(clock() - KEEP_MS)

    suspend fun prune() = runCatching { dao.deleteBefore(clock() - KEEP_MS) }

    companion object {
        const val KEEP_MS = 14 * 86_400_000L
        const val SOURCE_COMPANION = "companion"
        const val SOURCE_BLUETOOTH = "bluetooth"
        const val SOURCE_BLUETOOTH_OFF = "bluetooth_off"
        const val MANUAL = "manual"
        const val AUTO = "auto"
    }
}
