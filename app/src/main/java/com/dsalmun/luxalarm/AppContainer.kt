/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import androidx.annotation.VisibleForTesting
import com.dsalmun.luxalarm.data.AlarmDatabase
import com.dsalmun.luxalarm.data.AlarmRepository
import com.dsalmun.luxalarm.data.IAlarmRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppContainer : Application() {
    companion object {
        lateinit var database: AlarmDatabase
        lateinit var repository: IAlarmRepository
        lateinit var settingsManager: SettingsManager

        /** Backs the work the broadcast receivers start behind `goAsync()`. */
        @VisibleForTesting var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }

    override fun onCreate() {
        super.onCreate()
        database = AlarmDatabase.getDatabase(this)
        repository = AlarmRepository(database.alarmDao(), this)
        settingsManager = SettingsManager(this)
        CoroutineScope(Dispatchers.IO).launch { repository.cancelV1Alarms() }
    }
}
