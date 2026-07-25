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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.AppContainer
import com.dsalmun.luxalarm.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points the [AppContainer] service locator at test doubles. Drive the receivers' `goAsync` work
 * with [scheduler]: `runCurrent()` runs what is queued, so a test can assert either side of it.
 *
 * **Known limitation:** the `lateinit var`s cannot be un-set, so this guarantees overwrite-before-
 * use, not clearing. Robolectric reuses one sandbox classloader per SDK, so a class that reaches
 * `AppContainer` without this rule inherits the previous class's doubles — apply it everywhere.
 */
class AppContainerTestRule(val repository: FakeAlarmRepository = FakeAlarmRepository()) :
    TestWatcher() {

    val scheduler = TestCoroutineScheduler()

    lateinit var settingsManager: SettingsManager
        private set

    override fun starting(description: Description) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppContainer.repository = repository
        settingsManager = SettingsManager(context)
        AppContainer.settingsManager = settingsManager
        AppContainer.ioDispatcher = StandardTestDispatcher(scheduler)
    }

    override fun finished(description: Description) {
        AppContainer.ioDispatcher = Dispatchers.IO
    }
}
