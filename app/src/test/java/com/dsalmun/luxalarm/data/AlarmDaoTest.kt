/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.WEEKDAYS
import com.dsalmun.luxalarm.testing.alarm
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlarmDaoTest {
    private lateinit var database: AlarmDatabase
    private lateinit var dao: AlarmDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.alarmDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** Matching `repeatDays = ''` couples this to [Converters]: a change switches off repeats. */
    @Test
    fun deactivateOneShotAlarms_onlyDeactivatesNonRepeating() = runBlocking {
        dao.insert(alarm(id = 1, hour = 7, minute = 0, repeatDays = emptySet()))
        dao.insert(alarm(id = 2, hour = 8, minute = 0, repeatDays = WEEKDAYS))

        dao.deactivateOneShotAlarms(listOf(1, 2))

        assertFalse(dao.getAlarmById(1)!!.isActive, "The one-shot alarm should be deactivated")
        assertTrue(dao.getAlarmById(2)!!.isActive, "The repeating alarm should stay active")
    }

    @Test
    fun deactivateOneShotAlarms_ignoresIdsItWasNotGiven() = runBlocking {
        dao.insert(alarm(id = 1, hour = 7, minute = 0))
        dao.insert(alarm(id = 2, hour = 8, minute = 0))

        dao.deactivateOneShotAlarms(listOf(1))

        assertFalse(dao.getAlarmById(1)!!.isActive)
        assertTrue(dao.getAlarmById(2)!!.isActive)
    }

    @Test
    fun roundTripsEveryNullableColumn() = runBlocking {
        dao.insert(alarm(id = 1, hour = 7, minute = 0))

        assertNull(dao.getAlarmById(1)!!.ringtoneUri, "ringtoneUri defaults to null")
        assertNull(dao.getAlarmById(1)!!.skippedOccurrenceDay, "skippedOccurrenceDay defaults null")
        assertEquals(1f, dao.getAlarmById(1)!!.volume, "volume defaults to full")
        assertTrue(dao.getAlarmById(1)!!.vibrationEnabled, "vibration defaults on")

        val uri = "content://media/internal/audio/media/42"
        dao.update(
            dao.getAlarmById(1)!!.copy(
                ringtoneUri = uri,
                volume = 0.25f,
                vibrationEnabled = false,
                skippedOccurrenceDay = 19_000L,
            )
        )

        val stored = dao.getAlarmById(1)!!
        assertEquals(uri, stored.ringtoneUri)
        assertEquals(0.25f, stored.volume)
        assertFalse(stored.vibrationEnabled)
        assertEquals(19_000L, stored.skippedOccurrenceDay)

        dao.update(stored.copy(ringtoneUri = null, skippedOccurrenceDay = null))

        val cleared = dao.getAlarmById(1)!!
        assertNull(cleared.ringtoneUri, "A null ringtone clears the stored URI")
        assertNull(cleared.skippedOccurrenceDay)
    }

    @Test
    fun repeatDaysSurviveTheSetConverter() = runBlocking {
        dao.insert(alarm(id = 1, repeatDays = EVERY_DAY))
        dao.insert(alarm(id = 2, repeatDays = emptySet()))

        assertEquals(EVERY_DAY, dao.getAlarmById(1)!!.repeatDays)
        assertEquals(emptySet(), dao.getAlarmById(2)!!.repeatDays)
    }

    @Test
    fun getActiveAlarms_returnsOnlyEnabledOnes() = runBlocking {
        dao.insert(alarm(id = 1, hour = 6, isActive = true))
        dao.insert(alarm(id = 2, hour = 7, isActive = false))

        assertEquals(listOf(1), dao.getActiveAlarms().map { it.id })
    }

    @Test
    fun getAllAlarms_ordersByTimeThenId() = runBlocking {
        dao.insert(alarm(id = 1, hour = 9, minute = 30))
        dao.insert(alarm(id = 2, hour = 6, minute = 45))
        dao.insert(alarm(id = 3, hour = 6, minute = 15))

        assertEquals(listOf(3, 2, 1), dao.getAllAlarms().first().map { it.id })
    }

    @Test
    fun delete_removesTheRow() = runBlocking {
        dao.insert(alarm(id = 1))

        dao.delete(dao.getAlarmById(1)!!)

        assertNull(dao.getAlarmById(1))
        assertEquals(emptyList(), dao.getAllAlarmIds())
    }
}
