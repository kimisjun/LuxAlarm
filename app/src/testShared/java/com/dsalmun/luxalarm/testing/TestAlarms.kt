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
package com.dsalmun.luxalarm.testing

import com.dsalmun.luxalarm.AlarmViewModel
import com.dsalmun.luxalarm.data.AlarmItem
import java.util.Calendar
import java.util.TimeZone

val EVERY_DAY =
    setOf(
        Calendar.SUNDAY,
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
    )

val WEEKDAYS =
    setOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
    )

/** Wall-clock hour/minute [offsetMillis] from now, without pinning the clock. */
fun clockTimeIn(offsetMillis: Long): Pair<Int, Int> {
    val calendar =
        Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + offsetMillis }
    return calendar[Calendar.HOUR_OF_DAY] to calendar[Calendar.MINUTE]
}

/** Captured before any test can move it. */
private val systemTimeZone: TimeZone = TimeZone.getDefault()

/**
 * Shifts the default time zone so that "now" reads as [hour] locally. Robolectric has no supported
 * way to set an absolute wall-clock time under the paused looper, and moving the zone under a fixed
 * instant has the same effect. Pair with [restoreSystemTimeZone]: the zone is process-wide.
 */
fun pinLocalHourTo(hour: Int) {
    val utcHour = Calendar.getInstance(TimeZone.getTimeZone("UTC"))[Calendar.HOUR_OF_DAY]
    // Real offsets stop at +14, so anything beyond becomes the equivalent negative one.
    val offset = (hour - utcHour).mod(24).let { if (it > 14) it - 24 else it }
    TimeZone.setDefault(TimeZone.getTimeZone(String.format("GMT%+03d:00", offset)))
}

fun restoreSystemTimeZone() {
    TimeZone.setDefault(systemTimeZone)
}

fun alarm(
    id: Int = 1,
    hour: Int = 7,
    minute: Int = 0,
    isActive: Boolean = true,
    repeatDays: Set<Int> = emptySet(),
    ringtoneUri: String? = null,
    volume: Float? = null,
    vibrationEnabled: Boolean = true,
    skippedOccurrenceDay: Long? = null,
): AlarmItem =
    AlarmItem(
        id = id,
        hour = hour,
        minute = minute,
        isActive = isActive,
        repeatDays = repeatDays,
        ringtoneUri = ringtoneUri,
        volume = volume,
        vibrationEnabled = vibrationEnabled,
        skippedOccurrenceDay = skippedOccurrenceDay,
    )

fun uiState(
    item: AlarmItem = alarm(),
    isUpcoming: Boolean = false,
    isSkippingNext: Boolean = false,
): AlarmViewModel.AlarmUiState =
    AlarmViewModel.AlarmUiState(
        alarm = item,
        isUpcoming = isUpcoming,
        isSkippingNext = isSkippingNext,
    )
