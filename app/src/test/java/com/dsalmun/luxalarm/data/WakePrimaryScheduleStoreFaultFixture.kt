/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.Context
import com.dsalmun.luxalarm.PrimaryWakeScheduleCoordinator
import com.dsalmun.luxalarm.WakeAlarmClockPort

internal fun primaryScheduleStoreWithFaultHook(
    database: AlarmDatabase,
    faultHook: (String) -> Unit,
): RoomWakePrimaryScheduleStore {
    val constructor =
        RoomWakePrimaryScheduleStore::class
            .java
            .getDeclaredConstructor(
                AlarmDatabase::class.java,
                kotlin.Function1::class.java,
            )
    constructor.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return constructor.newInstance(database, faultHook) as RoomWakePrimaryScheduleStore
}

internal fun primaryWakeScheduleCoordinatorWithClock(
    context: Context,
    alarmClockPort: WakeAlarmClockPort,
    store: RoomWakePrimaryScheduleStore,
    epochClock: () -> Long,
): PrimaryWakeScheduleCoordinator {
    val constructor =
        PrimaryWakeScheduleCoordinator::class
            .java
            .getDeclaredConstructor(
                Context::class.java,
                WakeAlarmClockPort::class.java,
                RoomWakePrimaryScheduleStore::class.java,
                kotlin.Function0::class.java,
            )
    constructor.isAccessible = true
    return constructor.newInstance(context, alarmClockPort, store, epochClock)
        as PrimaryWakeScheduleCoordinator
}
