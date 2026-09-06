/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.dsalmun.luxalarm.data.IAlarmRepository
import com.dsalmun.luxalarm.data.RoomSleepPlanStore
import com.dsalmun.luxalarm.data.RoomWakePlaylistStore
import com.dsalmun.luxalarm.data.WarmlyDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AppContainer : Application() {
    companion object {
        /** Legacy runtime seam retained only while reusable alarm components are redesigned. */
        lateinit var repository: IAlarmRepository
        lateinit var settingsManager: SettingsManager
        lateinit var sleepPlanStore: SleepPlanStore
        lateinit var wakePlaylistStore: WakePlaylistStore

        /** Backs the work the disabled legacy broadcast receivers use in focused tests. */
        @VisibleForTesting var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        val database = WarmlyDatabase.getDatabase(this)
        sleepPlanStore = RoomSleepPlanStore(database.sleepPlanDao())
        wakePlaylistStore = RoomWakePlaylistStore(database)
    }
}
