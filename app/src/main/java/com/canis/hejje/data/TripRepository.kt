package com.canis.hejje.data

import androidx.room.withTransaction
import com.canis.hejje.data.db.HejjeDb
import com.canis.hejje.data.db.SegmentEntity
import com.canis.hejje.data.db.TagEntity
import com.canis.hejje.data.db.TripEntity
import com.canis.hejje.data.db.TripTagEntity
import com.canis.hejje.data.db.TripWithTags
import com.canis.hejje.domain.Segment
import com.canis.hejje.domain.StatsCalculator
import com.canis.hejje.domain.TripEditor
import com.canis.hejje.domain.TripTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Everything needed to put a trip back exactly as it was. */
class TripBundle(val trip: TripEntity, val segments: List<SegmentEntity>, val tagIds: List<Long>)

/** How to reverse an edit: remove what it created and restore what it changed. */
class Undo(val restore: List<TripBundle>, val remove: List<Long> = emptyList())

class TripRepository(private val db: HejjeDb) {
    private val dao = db.trips()

    val trips: Flow<List<TripWithTags>> = dao.observeTrips()
    val tags: Flow<List<TagEntity>> = dao.observeTags()

    fun observeTrip(id: Long): Flow<TripWithTags?> = dao.observeTrip(id)

    suspend fun trip(id: Long): TripEntity? = dao.trip(id)

    suspend fun latest(): TripEntity? = dao.latest()

    suspend fun all(): List<TripEntity> = dao.all()

    suspend fun segments(tripId: Long): List<Segment> = withContext(Dispatchers.Default) {
        dao.segments(tripId).map(Codec::toSegment)
    }

    /** Every trip's recorded pieces, newest trip first (for measuring stretches). */
    suspend fun tracks(): List<TripTrack> = withContext(Dispatchers.Default) {
        dao.allSegments().groupBy { it.tripId }
            .map { (id, rows) -> TripTrack(id, rows.map(Codec::toSegment)) }
            .sortedByDescending { t -> t.segments.firstOrNull()?.start ?: 0L }
    }

    private suspend fun bundle(id: Long): TripBundle? {
        val trip = dao.trip(id) ?: return null
        return TripBundle(trip, dao.segments(id), dao.tagIds(id))
    }

    private suspend fun save(base: TripEntity?, uuid: String, segments: List<Segment>): Long {
        val entity = withContext(Dispatchers.Default) { Codec.buildTrip(base, uuid, segments) }
        val rows = withContext(Dispatchers.Default) { segments.map { Codec.toEntity(0, it) } }
        return db.withTransaction {
            val id = if (base != null && base.id != 0L && dao.trip(base.id) != null) {
                dao.update(entity)
                dao.deleteSegments(base.id)
                base.id
            } else {
                dao.insert(entity.copy(id = 0))
            }
            dao.insertSegments(rows.map { SegmentEntity(0, id, it.startTime, it.endTime, it.data) })
            id
        }
    }

    /**
     * Stores a finished recording. It continues the previous trip as a new segment when it began
     * within the merge gap, otherwise it becomes a new trip.
     * @return the trip id, or null if the trip was shorter than the minimum length.
     */
    suspend fun addRecording(segment: Segment, mergeGapMs: Long, minTripM: Int): Long? {
        val last = dao.latest()
        if (last != null && TripEditor.continuesTrip(last.endTime, segment.start, mergeGapMs)) {
            return save(last, last.uuid, segments(last.id) + segment)
        }
        if (minTripM > 0 && StatsCalculator.compute(listOf(segment)).distanceM < minTripM) return null
        return save(null, UUID.randomUUID().toString(), listOf(segment))
    }

    /** Inserts a complete trip (import). */
    suspend fun insertTrip(uuid: String, segments: List<Segment>, note: String, startPlace: String?, endPlace: String?, tags: List<String>): Long {
        val id = save(null, uuid, segments)
        dao.trip(id)?.let { dao.update(it.copy(note = note, startPlace = startPlace, endPlace = endPlace)) }
        tags.forEach { setTag(id, it, true) }
        return id
    }

    suspend fun delete(id: Long): Undo? {
        val before = bundle(id) ?: return null
        dao.delete(id)
        return Undo(listOf(before))
    }

    suspend fun trim(id: Long, newStart: Long, newEnd: Long): Undo? {
        val before = bundle(id) ?: return null
        val kept = TripEditor.trim(segments(id), newStart, newEnd)
        if (kept.isEmpty()) return null
        save(before.trip, before.trip.uuid, kept)
        return Undo(listOf(before))
    }

    /** Splits a trip in two at [at]. The second part becomes a new trip with the same tags. */
    suspend fun split(id: Long, at: Long): Undo? {
        val before = bundle(id) ?: return null
        val (a, b) = TripEditor.split(segments(id), at)
        if (a.isEmpty() || b.isEmpty()) return null
        save(before.trip, before.trip.uuid, a)
        val newId = save(null, UUID.randomUUID().toString(), b)
        before.tagIds.forEach { dao.addTripTag(TripTagEntity(newId, it)) }
        return Undo(listOf(before), listOf(newId))
    }

    /** Joins two trips. The time between them becomes a stop. */
    suspend fun merge(idA: Long, idB: Long): Undo? {
        val a = bundle(idA) ?: return null
        val b = bundle(idB) ?: return null
        val (first, second) = if (a.trip.startTime <= b.trip.startTime) a to b else b to a
        val merged = TripEditor.merge(segments(first.trip.id), segments(second.trip.id))
        val note = listOf(first.trip.note, second.trip.note).filter { it.isNotBlank() }.joinToString("\n")
        save(first.trip.copy(note = note, endPlace = second.trip.endPlace, endLat = second.trip.endLat, endLon = second.trip.endLon), first.trip.uuid, merged)
        second.tagIds.forEach { dao.addTripTag(TripTagEntity(first.trip.id, it)) }
        dao.delete(second.trip.id)
        return Undo(listOf(a, b))
    }

    suspend fun undo(undo: Undo) = db.withTransaction {
        undo.remove.forEach { dao.delete(it) }
        undo.restore.forEach { b ->
            dao.insert(b.trip) // replaces the edited row; its segments and tags go with it
            dao.insertSegments(b.segments.map { SegmentEntity(0, b.trip.id, it.startTime, it.endTime, it.data) })
            b.tagIds.forEach { dao.addTripTag(TripTagEntity(b.trip.id, it)) }
        }
    }

    /** Recalculates the stored stats of every trip, e.g. after the scoring changed. Keeps notes, tags and places. */
    suspend fun recomputeAll() {
        for (t in dao.all()) {
            val segments = segments(t.id)
            if (segments.isEmpty()) continue
            val rebuilt = withContext(Dispatchers.Default) { Codec.buildTrip(t, t.uuid, segments) }
            dao.update(rebuilt.copy(startPlace = t.startPlace, endPlace = t.endPlace))
        }
    }

    suspend fun neighbours(id: Long): Pair<TripEntity?, TripEntity?> {
        val all = dao.all()
        val i = all.indexOfFirst { it.id == id }
        return if (i < 0) null to null else all.getOrNull(i - 1) to all.getOrNull(i + 1)
    }

    suspend fun setNote(id: Long, note: String) {
        dao.trip(id)?.let { dao.update(it.copy(note = note)) }
    }

    suspend fun setPlaces(id: Long, start: String?, end: String?) {
        dao.trip(id)?.let { dao.update(it.copy(startPlace = start ?: it.startPlace, endPlace = end ?: it.endPlace)) }
    }

    suspend fun withoutPlaces(limit: Int): List<TripEntity> = dao.withoutPlaces(limit)

    suspend fun setTag(tripId: Long, name: String, on: Boolean) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val tag = dao.tagByName(clean) ?: run {
            dao.insertTag(TagEntity(name = clean))
            dao.tagByName(clean) ?: return
        }
        if (on) dao.addTripTag(TripTagEntity(tripId, tag.id)) else dao.removeTripTag(tripId, tag.id)
    }

    suspend fun createTag(name: String) {
        if (name.isNotBlank() && dao.tagByName(name.trim()) == null) dao.insertTag(TagEntity(name = name.trim()))
    }

    suspend fun deleteTag(id: Long) = dao.deleteTag(id)

    suspend fun tagNames(tripId: Long): List<String> = dao.tagNames(tripId)

    suspend fun allTags(): List<TagEntity> = dao.tags()

    suspend fun deleteEverything() = db.withTransaction {
        dao.deleteAll()
        dao.deleteCustomTags()
        db.zones().deleteAll()
        db.places().deleteAll()
        db.raw().deleteAll()
        db.log().deleteAll()
    }
}
