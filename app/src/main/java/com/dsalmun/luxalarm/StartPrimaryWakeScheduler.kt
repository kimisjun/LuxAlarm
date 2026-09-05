/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind

/** Narrow capability boundary for the product's real alarm-clock registration call. */
internal fun interface WakeAlarmClockPort {
    fun schedule(triggerEpochMillis: Long, operation: PendingIntent)
}

/** Production AlarmManager capability; alarm-clock alarms are exact and allowed through idle. */
internal class AndroidWakeAlarmClockPort(private val alarmManager: AlarmManager) :
    WakeAlarmClockPort {
    override fun schedule(triggerEpochMillis: Long, operation: PendingIntent) {
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerEpochMillis, null),
            operation,
        )
    }
}

/** Ephemeral proof that the OS scheduling API returned; it is not durable scheduled truth. */
internal data class StartPrimaryScheduleOutcome(
    val event: WakeEventIdentity,
    val triggerEpochMillis: Long,
)

/** Task 5.2A tracer bullet: schedules only a START primary and performs no persistence/cutover. */
internal class StartPrimaryWakeScheduler(
    private val context: Context,
    private val alarmClockPort: WakeAlarmClockPort,
) {
    fun schedule(event: WakeEventIdentity): StartPrimaryScheduleOutcome {
        require(event.kind == WakeEventKind.START) { "START primary scheduler rejects GOAL events" }
        val operation = WakePendingIntentFactory.createPrimary(context, event)
        alarmClockPort.schedule(event.expectedTriggerEpochMillis, operation)
        return StartPrimaryScheduleOutcome(event, event.expectedTriggerEpochMillis)
    }
}
