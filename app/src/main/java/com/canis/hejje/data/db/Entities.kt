package com.canis.hejje.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "trips", indices = [Index(value = ["uuid"], unique = true), Index("startTime")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val startTime: Long,
    val endTime: Long,
    val distanceM: Double,
    val movingMs: Long,
    val idleMs: Long,
    val stoppedMs: Long,
    val maxSpeed: Float,
    val stopCount: Int,
    val stopMs: Long,
    val elevGain: Float,
    val elevLoss: Float,
    val altMin: Float?,
    val altMax: Float?,
    val hardBrakes: Int,
    val hardAccels: Int,
    val smoothness: Int,
    /** Milliseconds per speed band, comma separated. */
    val bands: String,
    val nightShare: Float,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val startPlace: String? = null,
    val endPlace: String? = null,
    val note: String = "",
    /** Tiny normalised route outline for list thumbnails: x,y byte pairs. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val thumb: ByteArray,
    val segmentCount: Int,
    val baro: Boolean,
) {
    val totalMs: Long get() = endTime - startTime
    val avgMovingSpeed: Float get() = if (movingMs > 0) (distanceM / (movingMs / 1000.0)).toFloat() else 0f
    val bandsMs: List<Long> get() = bands.split(',').mapNotNull { it.toLongOrNull() }
}

@Entity(
    tableName = "segments",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startTime: Long,
    val endTime: Long,
    /** Gzipped JSON of the route, profile and events. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val data: ByteArray,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val preset: Boolean = false,
)

@Entity(
    tableName = "trip_tags",
    primaryKeys = ["tripId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class TripTagEntity(val tripId: Long, val tagId: Long)

data class TripWithTags(
    @Embedded val trip: TripEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(TripTagEntity::class, parentColumn = "tripId", entityColumn = "tagId"),
    )
    val tags: List<TagEntity>,
)

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
)

/** Cached place names, keyed by coordinates rounded to about 100 m. An empty name means "nothing found". */
@Entity(tableName = "places")
data class PlaceEntity(@PrimaryKey val key: String, val name: String, val fetchedAt: Long)

/** GPS fixes of a recording in progress. Deleted once the segment has been processed. */
@Entity(tableName = "raw_points", indices = [Index(value = ["session", "t"])])
data class RawPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val session: String,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Float?,
    val speed: Float?,
    val acc: Float?,
    val pressure: Float?,
    val speedAcc: Float?,
)
