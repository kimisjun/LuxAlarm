/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.WEEKDAYS
import java.util.Calendar
import kotlin.test.assertEquals
import org.junit.Test

/** Every day constant and the out-of-range fallback, cheaper to reach than through the screen. */
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
