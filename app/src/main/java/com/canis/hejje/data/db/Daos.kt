package com.canis.hejje.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Transaction
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun observeTrips(): Flow<List<TripWithTags>>

    @Transaction
    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<TripWithTags?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun trip(id: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY endTime DESC LIMIT 1")
    suspend fun latest(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startTime")
    suspend fun all(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE startPlace IS NULL OR endPlace IS NULL ORDER BY startTime DESC LIMIT :limit")
    suspend fun withoutPlaces(limit: Int): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()

    @Query("SELECT * FROM segments WHERE tripId = :tripId ORDER BY startTime")
    suspend fun segments(tripId: Long): List<SegmentEntity>

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE tripId = :tripId")
    suspend fun deleteSegments(tripId: Long)

    @Query("SELECT * FROM tags ORDER BY preset DESC, name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun tags(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun tagByName(name: String): TagEntity?

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("DELETE FROM tags WHERE preset = 0")
    suspend fun deleteCustomTags()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTripTag(link: TripTagEntity)

    @Query("DELETE FROM trip_tags WHERE tripId = :tripId AND tagId = :tagId")
    suspend fun removeTripTag(tripId: Long, tagId: Long)

    @Query("SELECT tagId FROM trip_tags WHERE tripId = :tripId")
    suspend fun tagIds(tripId: Long): List<Long>

    @Query("SELECT t.name FROM tags t JOIN trip_tags x ON x.tagId = t.id WHERE x.tripId = :tripId")
    suspend fun tagNames(tripId: Long): List<String>
}

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ZoneEntity>>

    @Query("SELECT * FROM zones")
    suspend fun all(): List<ZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(zone: ZoneEntity): Long

    @Query("DELETE FROM zones WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM zones")
    suspend fun deleteAll()
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE `key` = :key")
    suspend fun get(key: String): PlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(place: PlaceEntity)

    @Query("DELETE FROM places")
    suspend fun deleteAll()
}

@Dao
interface RawDao {
    @Insert
    suspend fun insert(points: List<RawPointEntity>)

    @Query("SELECT * FROM raw_points WHERE session = :session ORDER BY t")
    suspend fun points(session: String): List<RawPointEntity>

    @Query("SELECT MAX(t) FROM raw_points WHERE session = :session")
    suspend fun lastTime(session: String): Long?

    @Query("SELECT DISTINCT session FROM raw_points")
    suspend fun sessions(): List<String>

    @Query("DELETE FROM raw_points WHERE session = :session")
    suspend fun delete(session: String)

    @Query("DELETE FROM raw_points")
    suspend fun deleteAll()
}
