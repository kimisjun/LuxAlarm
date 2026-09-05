/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import com.dsalmun.luxalarm.data.RoomWakePrimaryScheduleStore
import com.dsalmun.luxalarm.data.WakeRunSnapshotEntity
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind

/** Schedules the GOAL safety dependency before START and records only returned API calls. */
internal class PrimaryWakeScheduleCoordinator
private constructor(
    private val context: Context,
    private val alarmClockPort: WakeAlarmClockPort,
    private val store: RoomWakePrimaryScheduleStore,
    private val epochClock: () -> Long,
) {
    internal constructor(
        context: Context,
        alarmClockPort: WakeAlarmClockPort,
        store: RoomWakePrimaryScheduleStore,
    ) : this(context, alarmClockPort, store, System::currentTimeMillis)

    fun schedule(snapshot: WakeRunSnapshotEntity) {
        val goal = WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
        val start = WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs)
        val initialNow = epochClock()
        check(goal.expectedTriggerEpochMillis > initialNow) {
            "GOAL primary trigger is not strictly in the future"
        }
        check(start.expectedTriggerEpochMillis > initialNow) {
            "START primary trigger is not strictly in the future"
        }
        store.ensureDesiredPrimaries(snapshot)
        scheduleAndRecord(snapshot, goal)
        scheduleAndRecord(snapshot, start)
    }

    private fun scheduleAndRecord(snapshot: WakeRunSnapshotEntity, event: WakeEventIdentity) {
        store.preflightApiCall(snapshot, event)
        val operation = WakePendingIntentFactory.createPrimary(context, event)
        val finalNow = epochClock()
        check(event.expectedTriggerEpochMillis > finalNow) {
            "Primary trigger is not strictly in the future"
        }
        alarmClockPort.schedule(event.expectedTriggerEpochMillis, operation)
        store.recordApiReturn(snapshot, event)
    }
}
