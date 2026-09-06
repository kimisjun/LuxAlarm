/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [AlarmItem::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile private var Instance: AlarmDatabase? = null
        internal val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE alarms ADD COLUMN ringtoneUri TEXT")
                }
            }

        internal val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE alarms ADD COLUMN volume REAL")
                    db.execSQL(
                        "ALTER TABLE alarms ADD COLUMN vibrationEnabled INTEGER NOT NULL DEFAULT 1"
                    )
                }
            }

        internal val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE alarms ADD COLUMN skippedOccurrenceDay INTEGER")
                }
            }

        /**
         * A null volume only ever meant "this column was added after the alarm was", never a
         * choice: the row's slider has always drawn one of those alarms at full volume. SQLite
         * cannot drop a column's nullability in place, so the table is rebuilt around it.
         */
        internal val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alarms_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `hour` INTEGER NOT NULL,
                            `minute` INTEGER NOT NULL,
                            `isActive` INTEGER NOT NULL,
                            `repeatDays` TEXT NOT NULL,
                            `ringtoneUri` TEXT,
                            `volume` REAL NOT NULL,
                            `vibrationEnabled` INTEGER NOT NULL,
                            `skippedOccurrenceDay` INTEGER
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        INSERT INTO `alarms_new`
                        SELECT `id`, `hour`, `minute`, `isActive`, `repeatDays`, `ringtoneUri`,
                               IFNULL(`volume`, 1.0), `vibrationEnabled`, `skippedOccurrenceDay`
                        FROM `alarms`
                        """
                            .trimIndent()
                    )
                    db.execSQL("DROP TABLE `alarms`")
                    db.execSQL("ALTER TABLE `alarms_new` RENAME TO `alarms`")
                }
            }

        fun getDatabase(context: Context): AlarmDatabase {
            return Instance
                ?: synchronized(this) {
                    Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_database")
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                        .build()
                        .also { Instance = it }
                }
        }
    }
}
