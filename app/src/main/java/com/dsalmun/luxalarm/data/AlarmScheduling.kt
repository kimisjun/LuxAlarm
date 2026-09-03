/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
 * Modified for GentleWake in 2026 by 김은준.
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
package com.dsalmun.luxalarm.data

import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

/** Lead time before an alarm at which the "upcoming alarm" notification appears. */
const val UPCOMING_LEAD_MILLIS: Long = 2 * 60 * 60 * 1000L

/**
 * The local calendar day [millis] falls on, as days since 1970-01-01 in the device's current zone.
 * Alarms are defined in wall-clock terms, so a skipped occurrence is stored as a day: an instant
 * bakes in the UTC offset and stops matching once the device changes zone.
 */
fun localDayOf(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

/**
 * Next trigger time (epoch millis) after [nowMillis] for an alarm at [hour]:[minute] on
 * [repeatDays] (Calendar day-of-week constants; empty means one-shot). An occurrence falling on
 * [skipDay] is passed over. The loop spans 15 days so a skipped weekly occurrence is still found.
 */
fun nextTrigger(
    hour: Int,
    minute: Int,
    repeatDays: Set<Int>,
    nowMillis: Long,
    skipDay: Long? = null,
): Long = nextTrigger(hour, minute, repeatDays, nowMillis, ZoneId.systemDefault(), skipDay)

/** Process-global-time-zone-free scheduling variant for snapshots and deterministic tests. */
internal fun nextTrigger(
    hour: Int,
    minute: Int,
    repeatDays: Set<Int>,
    nowMillis: Long,
    zoneId: ZoneId,
    skipDay: Long? = null,
): Long {
    val now = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply { timeInMillis = nowMillis }

    if (repeatDays.isEmpty()) {
        val alarmTime = now.atTime(hour, minute)
        if (!alarmTime.after(now)) {
            alarmTime.add(Calendar.DAY_OF_MONTH, 1)
        }
        return alarmTime.timeInMillis
    }

    for (i in 0 until 15) {
        val candidate = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
        if (candidate[Calendar.DAY_OF_WEEK] !in repeatDays) continue

        val triggerTime = candidate.atTime(hour, minute)
        if (triggerTime.after(now) && localDayOf(triggerTime.timeInMillis, zoneId) != skipDay) {
            return triggerTime.timeInMillis
        }
    }

    // Preserve the legacy fallback for malformed, non-empty repeat-day sets.
    return now.atTime(hour, minute).apply { add(Calendar.WEEK_OF_YEAR, 1) }.timeInMillis
}

private fun localDayOf(millis: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate().toEpochDay()

private fun Calendar.atTime(hour: Int, minute: Int): Calendar =
    (clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
