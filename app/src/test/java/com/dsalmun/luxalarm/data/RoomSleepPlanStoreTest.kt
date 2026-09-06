/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.SleepPlan
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomSleepPlanStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "warmly-sleep-plan-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun onePlanSurvivesDatabaseCloseAndReopen() = runTest {
        val expected =
            SleepPlan(
                wakeMinutes = 7 * 60 + 15,
                bedtimeMinutes = 23 * 60,
                bedtimeDayOffset = -1,
            )
        val firstDatabase = open()
        RoomSleepPlanStore(firstDatabase.sleepPlanDao()).save(expected)
        firstDatabase.close()

        val secondDatabase = open()
        val actual = RoomSleepPlanStore(secondDatabase.sleepPlanDao()).load()
        secondDatabase.close()

        assertEquals(expected, actual)
    }

    private fun open(): WarmlyDatabase =
        Room.databaseBuilder(context, WarmlyDatabase::class.java, databaseName).build()
}
