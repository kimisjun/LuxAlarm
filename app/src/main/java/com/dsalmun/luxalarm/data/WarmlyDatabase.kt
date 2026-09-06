/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dsalmun.luxalarm.SleepPlan
import com.dsalmun.luxalarm.SleepPlanStore

@Entity(tableName = "sleep_plan")
data class SleepPlanEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val wakeMinutes: Int,
    val bedtimeMinutes: Int,
    val bedtimeDayOffset: Int,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface SleepPlanDao {
    @Query("SELECT * FROM sleep_plan WHERE singletonId = 1") suspend fun load(): SleepPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(plan: SleepPlanEntity)
}

@Database(
    entities =
        [
            SleepPlanEntity::class,
            WakePlaylistEntity::class,
            WakeTrackEntity::class,
            WakePlaylistEntryEntity::class,
            WakePlaylistSelectionEntity::class,
        ],
    version = 2,
    exportSchema = false,
)
abstract class WarmlyDatabase : RoomDatabase() {
    abstract fun sleepPlanDao(): SleepPlanDao

    abstract fun wakePlaylistDao(): WakePlaylistDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `wake_playlists` (
                            `id` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `wake_tracks` (
                            `id` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `storedPath` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `wake_playlist_entries` (
                            `id` TEXT NOT NULL,
                            `playlistId` TEXT NOT NULL,
                            `trackId` TEXT NOT NULL,
                            `position` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`playlistId`) REFERENCES `wake_playlists`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE,
                            FOREIGN KEY(`trackId`) REFERENCES `wake_tracks`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS
                            `index_wake_playlist_entries_playlistId_trackId`
                        ON `wake_playlist_entries` (`playlistId`, `trackId`)
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS
                            `index_wake_playlist_entries_playlistId_position`
                        ON `wake_playlist_entries` (`playlistId`, `position`)
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_wake_playlist_entries_trackId`
                        ON `wake_playlist_entries` (`trackId`)
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `wake_playlist_selection` (
                            `singletonId` INTEGER NOT NULL,
                            `playlistId` TEXT NOT NULL,
                            PRIMARY KEY(`singletonId`),
                            FOREIGN KEY(`playlistId`) REFERENCES `wake_playlists`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_wake_playlist_selection_playlistId`
                        ON `wake_playlist_selection` (`playlistId`)
                        """
                            .trimIndent()
                    )
                }
            }

        @Volatile private var instance: WarmlyDatabase? = null

        fun getDatabase(context: Context): WarmlyDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                WarmlyDatabase::class.java,
                                "warmly.db",
                            )
                            .addMigrations(MIGRATION_1_2)
                            .build()
                            .also { instance = it }
                }
    }
}

class RoomSleepPlanStore(private val dao: SleepPlanDao) : SleepPlanStore {
    override suspend fun load(): SleepPlan? =
        dao.load()?.let {
            SleepPlan(
                wakeMinutes = it.wakeMinutes,
                bedtimeMinutes = it.bedtimeMinutes,
                bedtimeDayOffset = it.bedtimeDayOffset,
            )
        }

    override suspend fun save(plan: SleepPlan) {
        dao.save(
            SleepPlanEntity(
                wakeMinutes = plan.wakeMinutes,
                bedtimeMinutes = plan.bedtimeMinutes,
                bedtimeDayOffset = plan.bedtimeDayOffset,
            )
        )
    }
}
