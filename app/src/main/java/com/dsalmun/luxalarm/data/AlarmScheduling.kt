/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

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
): Long {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

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
        if (triggerTime.after(now) && localDayOf(triggerTime.timeInMillis) != skipDay) {
            return triggerTime.timeInMillis
        }
    }

    // Unreachable in practice: a non-empty repeatDays set always has an occurrence within 15 days.
    return now.atTime(hour, minute).apply { add(Calendar.WEEK_OF_YEAR, 1) }.timeInMillis
}

private fun Calendar.atTime(hour: Int, minute: Int): Calendar =
    (clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
