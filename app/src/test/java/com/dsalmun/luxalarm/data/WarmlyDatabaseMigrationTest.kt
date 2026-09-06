/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.SleepPlan
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WarmlyDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "warmly-v1-to-v2-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFrom1To2PreservesTheSingletonSleepPlanAndAddsEmptyPlaylistTables() = runTest {
        createVersion1Database()

        val database =
            Room.databaseBuilder(context, WarmlyDatabase::class.java, databaseName)
                .addMigrations(WarmlyDatabase.MIGRATION_1_2)
                .build()

        assertEquals(
            SleepPlan(wakeMinutes = 435, bedtimeMinutes = 1380, bedtimeDayOffset = -1),
            RoomSleepPlanStore(database.sleepPlanDao()).load(),
        )
        assertTrue(RoomWakePlaylistStore(database).listPlaylists().isEmpty())
        assertTrue(RoomWakePlaylistStore(database).listLibraryTracks().isEmpty())
        database.close()
    }

    private fun createVersion1Database() {
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(path, null)
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sleep_plan` (
                `singletonId` INTEGER NOT NULL,
                `wakeMinutes` INTEGER NOT NULL,
                `bedtimeMinutes` INTEGER NOT NULL,
                `bedtimeDayOffset` INTEGER NOT NULL,
                PRIMARY KEY(`singletonId`)
            )
            """
                .trimIndent()
        )
        database.insert(
            "sleep_plan",
            null,
            ContentValues().apply {
                put("singletonId", 1)
                put("wakeMinutes", 435)
                put("bedtimeMinutes", 1380)
                put("bedtimeDayOffset", -1)
            },
        )
        database.version = 1
        database.close()
    }
}
