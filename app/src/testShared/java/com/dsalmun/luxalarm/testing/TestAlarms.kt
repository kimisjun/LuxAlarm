/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.testing

import com.dsalmun.luxalarm.AlarmViewModel
import com.dsalmun.luxalarm.data.AlarmItem
import com.dsalmun.luxalarm.data.localDayOf
import com.dsalmun.luxalarm.data.nextTrigger
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

fun clockTimeIn(offsetMillis: Long): Pair<Int, Int> {
    val calendar =
        Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() + offsetMillis }
    return calendar[Calendar.HOUR_OF_DAY] to calendar[Calendar.MINUTE]
}

/** Captured before any test can move it. */
private val systemTimeZone: TimeZone = TimeZone.getDefault()

/**
 * Shifts the default zone so "now" reads as [hour] locally — Robolectric cannot set an absolute
 * wall-clock time under the paused looper. Pair with [restoreSystemTimeZone]: the zone is global.
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
    volume: Float = 1f,
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

/**
 * Derives the trigger instants the way the ViewModel does, so a state is never self-inconsistent.
 */
fun uiState(
    item: AlarmItem = alarm(),
    isUpcoming: Boolean = false,
    isSkippingNext: Boolean = false,
    nowMillis: Long = System.currentTimeMillis(),
): AlarmViewModel.AlarmUiState {
    val rawNext = nextTrigger(item.hour, item.minute, item.repeatDays, nowMillis)
    return AlarmViewModel.AlarmUiState(
        alarm = item,
        isUpcoming = isUpcoming,
        isSkippingNext = isSkippingNext,
        skippedTriggerMillis = rawNext.takeIf { isSkippingNext },
        nextTriggerMillis =
            if (isSkippingNext)
                nextTrigger(item.hour, item.minute, item.repeatDays, nowMillis, localDayOf(rawNext))
            else rawNext,
        nowMillis = nowMillis,
    )
}
