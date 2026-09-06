/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import com.dsalmun.luxalarm.data.localDayOf
import com.dsalmun.luxalarm.data.nextTrigger
import java.util.Calendar
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class AlarmSchedulingTest {
    private companion object {
        val EVERY_DAY = setOf(1, 2, 3, 4, 5, 6, 7)
    }

    private val originalZone = TimeZone.getDefault()

    // nextTrigger builds Calendars in the default zone; pin it so millisAt (UTC) matches.
    @Before
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            .apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }
            .timeInMillis

    private fun dayOfWeek(millis: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = millis }[Calendar.DAY_OF_WEEK]

    @Test
    fun oneShot_laterToday_returnsToday() {
        // 2026-07-24 is a Friday.
        val now = millisAt(2026, 7, 24, 6, 0)
        val result = nextTrigger(7, 0, emptySet(), now)
        assertEquals(millisAt(2026, 7, 24, 7, 0), result)
    }

    @Test
    fun oneShot_timePassed_returnsTomorrow() {
        val now = millisAt(2026, 7, 24, 8, 0)
        val result = nextTrigger(7, 0, emptySet(), now)
        assertEquals(millisAt(2026, 7, 25, 7, 0), result)
    }

    @Test
    fun repeating_skipExactOccurrence_returnsFollowingDay() {
        val today = millisAt(2026, 7, 24, 7, 0) // Friday 07:00
        val now = millisAt(2026, 7, 24, 6, 0)
        val repeatDays =
            setOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
            )

        val result = nextTrigger(7, 0, repeatDays, now, skipDay = localDayOf(today))

        // The next weekday after Friday is Monday 2026-07-27.
        assertEquals(millisAt(2026, 7, 27, 7, 0), result)
        assertEquals(Calendar.MONDAY, dayOfWeek(result))
    }

    @Test
    fun repeating_weeklySingleDaySkipped_landsSevenDaysLater() {
        val thisFriday = millisAt(2026, 7, 24, 7, 0)
        val now = millisAt(2026, 7, 24, 6, 0)

        val result =
            nextTrigger(7, 0, setOf(Calendar.FRIDAY), now, skipDay = localDayOf(thisFriday))

        assertEquals(millisAt(2026, 7, 31, 7, 0), result)
        assertEquals(Calendar.FRIDAY, dayOfWeek(result))
    }

    @Test
    fun repeating_noSkip_returnsSoonestOccurrence() {
        val now = millisAt(2026, 7, 24, 6, 0) // Friday 06:00
        val result = nextTrigger(7, 0, setOf(Calendar.FRIDAY), now)
        assertEquals(millisAt(2026, 7, 24, 7, 0), result)
    }

    @Test
    fun oneShot_ignoresSkipDay() {
        // A one-shot alarm has no "occurrence after next"; skip must not affect it.
        val now = millisAt(2026, 7, 24, 6, 0)
        val today = millisAt(2026, 7, 24, 7, 0)
        val result = nextTrigger(7, 0, emptySet(), now, skipDay = localDayOf(today))
        assertEquals(today, result)
    }

    @Test
    fun repeating_skipSurvivesATimeZoneChange() {
        // Madrid then New York: a different instant, the same calendar day, so the skip holds.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val now = millisAt(2026, 7, 24, 20, 0)
        val skipDay = localDayOf(nextTrigger(7, 0, EVERY_DAY, now))

        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val result = nextTrigger(7, 0, EVERY_DAY, now, skipDay = skipDay)

        // Storing the skip as an instant would have missed this and rung on the 25th.
        assertEquals(millisAt(2026, 7, 26, 11, 0), result)
    }

    @Test
    fun result_isAlwaysInFuture() {
        val now = millisAt(2026, 7, 24, 7, 0)
        val result = nextTrigger(7, 0, setOf(Calendar.FRIDAY), now)
        assertTrue(result > now)
    }
}
