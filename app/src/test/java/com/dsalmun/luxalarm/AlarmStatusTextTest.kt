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

import com.dsalmun.luxalarm.testing.restoreSystemTimeZone
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The status line the alarm row shows in place of its repeat days. */
class AlarmStatusTextTest {
    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        const val SUNDAY = "2026-07-26"
        const val MONDAY = "2026-07-27"
        const val FRIDAY = "2026-07-31"
        const val SATURDAY = "2026-08-01"
        const val SUNDAY_NEXT_WEEK = "2026-08-02"
    }

    /** Day boundaries are local, so pin a zone rather than inherit the host's. */
    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        restoreSystemTimeZone()
    }

    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun aSkipOfTodaysOccurrence_namesTheDayItLandsOnInstead() {
        val now = at(SUNDAY, 6)

        assertEquals(
            "Skipping today · rings tomorrow",
            formatSkipStatus(at(SUNDAY, 7), at(MONDAY, 7), now),
        )
    }

    @Test
    fun anOccurrenceFurtherOutIsNamedByItsWeekday() {
        val now = at(SUNDAY, 6)

        assertEquals(
            "Skipping tomorrow · rings Fri",
            formatSkipStatus(at(MONDAY, 7), at(FRIDAY, 7), now),
        )
    }

    /** Naming the day would read as the one just ahead, which is a week early. */
    @Test
    fun aWeeklyAlarmSkippedTodayReadsAsAWeekAway() {
        val now = at(SUNDAY, 6)

        assertEquals(
            "Skipping today · rings next week",
            formatSkipStatus(at(SUNDAY, 7), at(SUNDAY_NEXT_WEEK, 7), now),
        )
    }

    /** The last day that is still unambiguously "this coming week". */
    @Test
    fun sixDaysOutIsStillNamedByItsWeekday() {
        val now = at(SUNDAY, 6)

        assertEquals(
            "Skipping today · rings Sat",
            formatSkipStatus(at(SUNDAY, 7), at(SATURDAY, 7), now),
        )
    }

    @Test
    fun withoutTheSkippedOccurrence_theLandingPointStillStands() {
        val now = at(SUNDAY, 6)

        assertEquals("Rings tomorrow", formatSkipStatus(null, at(MONDAY, 7), now))
    }

    @Test
    fun aCountdownUnderAnHourIsMinutesOnly() {
        assertEquals("Rings in 42 min", formatCountdown(42 * MINUTE))
    }

    @Test
    fun aCountdownOverAnHourCarriesBothParts() {
        assertEquals("Rings in 1 hr 12 min", formatCountdown(HOUR + 12 * MINUTE))
        assertEquals("Rings in 2 hr", formatCountdown(2 * HOUR))
    }

    @Test
    fun aPartialMinuteRoundsUp() {
        assertEquals("Rings in 42 min", formatCountdown(41 * MINUTE + 1))
        assertEquals("Rings in 1 hr", formatCountdown(59 * MINUTE + 1))
    }

    /** The last minute before ringing: still upcoming, so it cannot read as zero. */
    @Test
    fun theFinalStretchStillReadsAsAMinute() {
        assertEquals("Rings in 1 min", formatCountdown(1))
        assertEquals("Rings in 1 min", formatCountdown(0))
    }
}
