/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.dsalmun.luxalarm

import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.WEEKDAYS
import java.util.Calendar
import kotlin.test.assertEquals
import org.junit.Test

/**
 * The repeat-day subtitle. `AlarmScreenContentTest` renders it for two cases; here every day
 * constant and the out-of-range fallback are cheap to reach.
 */
class FormatRepeatDaysTest {
    private companion object {
        /** Only read when the day set is empty. */
        const val HOUR = 7
        const val MINUTE = 0
    }

    @Test
    fun eachWeekdayHasItsOwnAbbreviation() {
        val expected =
            mapOf(
                Calendar.SUNDAY to "Sun",
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
            )

        for ((day, abbreviation) in expected) {
            assertEquals(abbreviation, format(setOf(day)))
        }
    }

    /** The set is unordered, so the label sorts it. */
    @Test
    fun daysAreListedInWeekOrder() {
        val outOfOrder = setOf(Calendar.SATURDAY, Calendar.SUNDAY, Calendar.FRIDAY)

        assertEquals("Sun, Fri, Sat", format(outOfOrder))
    }

    @Test
    fun weekdaysAreListedIndividually() {
        assertEquals("Mon, Tue, Wed, Thu, Fri", format(WEEKDAYS))
    }

    @Test
    fun allSevenDaysCollapseToEveryDay() {
        assertEquals("Every day", format(EVERY_DAY))
    }

    /** Day constants only come from the picker; pinned so a stray value stays harmless. */
    @Test
    fun anUnknownDayConstantRendersEmpty() {
        assertEquals("", format(setOf(99)))
        assertEquals("Mon, ", format(setOf(Calendar.MONDAY, 99)))
    }

    private fun format(days: Set<Int>) = formatRepeatDays(days, HOUR, MINUTE)
}
