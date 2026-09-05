/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import com.dsalmun.luxalarm.data.RoomWakePrimaryScheduleStore
import com.dsalmun.luxalarm.data.WakeDynamicScheduleRequest
import com.dsalmun.luxalarm.data.WakeRunSnapshotEntity
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind

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
        val initialNow = epochClock()
        val plan = store.prepareSchedule(snapshot, initialNow)
        val anchors =
            plan.anchorKinds.map { kind ->
                val trigger =
                    checkNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)) {
                        "Immutable anchor trigger overflows epoch range"
                    }
                kind to trigger
            }
        plan.primaryEvents.forEach { scheduleAndRecord(snapshot, it) }
        anchors.forEach { (kind, trigger) ->
            scheduleAnchorAndRecord(snapshot, goal, kind, trigger)
        }
        plan.dynamicRequests.forEach { scheduleDynamicAndRecord(snapshot, it) }
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

    private fun scheduleAnchorAndRecord(
        snapshot: WakeRunSnapshotEntity,
        goal: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
        trigger: Long,
    ) {
        store.preflightAnchorApiCall(snapshot, goal, kind)
        val operation = WakePendingIntentFactory.createAnchor(context, goal, kind)
        val finalNow = epochClock()
        check(trigger > finalNow) { "Immutable anchor trigger is not strictly in the future" }
        alarmClockPort.schedule(trigger, operation)
        store.recordAnchorApiReturn(snapshot, goal, kind)
    }

    private fun scheduleDynamicAndRecord(
        snapshot: WakeRunSnapshotEntity,
        request: WakeDynamicScheduleRequest,
    ) {
        store.preflightDynamicApiCall(snapshot, request)
        val operation =
            WakePendingIntentFactory.createDynamic(
                context,
                request.event,
                request.slot,
                request.token,
                request.triggerEpochMillis,
            )
        val finalNow = epochClock()
        check(request.triggerEpochMillis > finalNow) {
            "Dynamic recovery trigger is not strictly in the future"
        }
        alarmClockPort.schedule(request.triggerEpochMillis, operation)
        store.recordDynamicApiReturn(snapshot, request)
    }
}
