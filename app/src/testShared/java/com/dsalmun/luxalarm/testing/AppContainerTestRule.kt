/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.testing

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.AlarmActivity
import com.dsalmun.luxalarm.AppContainer
import com.dsalmun.luxalarm.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points the [AppContainer] service locator at test doubles; drive `goAsync` work with [scheduler].
 * The `lateinit var`s cannot be un-set and Robolectric reuses one classloader per SDK, so apply
 * this everywhere: a class without it inherits the previous class's doubles.
 */
class AppContainerTestRule(val repository: FakeAlarmRepository = FakeAlarmRepository()) :
    TestWatcher() {

    val scheduler = TestCoroutineScheduler()

    lateinit var settingsManager: SettingsManager
        private set

    private lateinit var alarmActivityComponent: ComponentName

    override fun starting(description: Description) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        alarmActivityComponent = ComponentName(context, AlarmActivity::class.java)
        context.packageManager.setComponentEnabledSetting(
            alarmActivityComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppContainer.repository = repository
        settingsManager = SettingsManager(context)
        AppContainer.settingsManager = settingsManager
        AppContainer.ioDispatcher = StandardTestDispatcher(scheduler)
    }

    override fun finished(description: Description) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.packageManager.setComponentEnabledSetting(
            alarmActivityComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        AppContainer.ioDispatcher = Dispatchers.IO
    }
}
