package com.canis.hejje.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class, SegmentEntity::class, TagEntity::class, TripTagEntity::class,
        ZoneEntity::class, PlaceEntity::class, RawPointEntity::class, LogEntity::class, StopAskEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class HejjeDb : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun zones(): ZoneDao
    abstract fun places(): PlaceDao
    abstract fun raw(): RawDao
    abstract fun log(): LogDao
    abstract fun stopAsks(): StopAskDao

    companion object {
        val PRESET_TAGS = listOf("Work", "Commute", "Road trip", "Errand")

        fun insertPresetTags(db: SupportSQLiteDatabase) {
            PRESET_TAGS.forEach { db.execSQL("INSERT OR IGNORE INTO tags(name, preset) VALUES (?, 1)", arrayOf<Any>(it)) }
        }

        /** 1.2.0: places (icon, hide, tag), the diagnostics log, and phone-sensor columns for GPS fixes. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE zones ADD COLUMN icon TEXT NOT NULL DEFAULT 'pin'")
                db.execSQL("ALTER TABLE zones ADD COLUMN hide INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE zones ADD COLUMN tag TEXT")
                for (column in listOf("sLon", "sMin", "sMax", "sLat")) db.execSQL("ALTER TABLE raw_points ADD COLUMN $column REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `t` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `detail` TEXT NOT NULL, `tripId` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_log_t` ON `log` (`t`)")
            }
        }

        /** 1.4.0: route shape (circuity and twistiness) per trip, filled in by the stats upgrade. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN circuity REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN twistiness REAL")
            }
        }

        /** 1.6.0: food and coffee stops (a rating on places, and the questions asked after parking). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE zones ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stop_asks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, " +
                        "`parkedAt` INTEGER NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `state` TEXT NOT NULL, `placeId` INTEGER, " +
                        "`feedback` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stop_asks_at` ON `stop_asks` (`at`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE zones ADD COLUMN purpose TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE raw_points ADD COLUMN fSpeed REAL")
                db.execSQL("ALTER TABLE raw_points ADD COLUMN fDist REAL")
                db.execSQL("ALTER TABLE raw_points ADD COLUMN fState INTEGER")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): HejjeDb {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, HejjeDb::class.java).allowMainThreadQueries()
            } else {
                Room.databaseBuilder(context, HejjeDb::class.java, "hejje.db")
            }
            return builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = insertPresetTags(db)
            }).build()
        }
    }
}
