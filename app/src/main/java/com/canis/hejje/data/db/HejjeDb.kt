package com.canis.hejje.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class, SegmentEntity::class, TagEntity::class, TripTagEntity::class,
        ZoneEntity::class, PlaceEntity::class, RawPointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class HejjeDb : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun zones(): ZoneDao
    abstract fun places(): PlaceDao
    abstract fun raw(): RawDao

    companion object {
        val PRESET_TAGS = listOf("Work", "Commute", "Road trip", "Errand")

        fun insertPresetTags(db: SupportSQLiteDatabase) {
            PRESET_TAGS.forEach { db.execSQL("INSERT OR IGNORE INTO tags(name, preset) VALUES (?, 1)", arrayOf<Any>(it)) }
        }

        fun create(context: Context, inMemory: Boolean = false): HejjeDb {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, HejjeDb::class.java).allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, HejjeDb::class.java, "hejje.db")
            }
            return builder.addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = insertPresetTags(db)
            }).build()
        }
    }
}
