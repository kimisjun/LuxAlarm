/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = AppContainer::class)
@LazyApplication(LazyLoad.ON)
class AppContainerStartupTest {
    private var application: AppContainer? = null

    @After
    fun cleanUp() {
        application?.onTerminate()
        AppContainer.ioDispatcher = Dispatchers.IO
    }

    @Test
    fun onCreateReturnsWhileReconciliationIsQueued() {
        val dispatcher = StandardTestDispatcher()
        AppContainer.ioDispatcher = dispatcher

        application = ApplicationProvider.getApplicationContext()
        val startup = checkNotNull(AppContainer.startupReconciliationJob)

        assertFalse(startup.isCompleted)
    }

    @Test
    fun onTerminateCancelsPendingApplicationWork() {
        AppContainer.ioDispatcher = StandardTestDispatcher()
        application = ApplicationProvider.getApplicationContext()
        val startup = checkNotNull(AppContainer.startupReconciliationJob)

        application?.onTerminate()
        application = null

        assertTrue(startup.isCancelled)
        assertNull(AppContainer.startupReconciliationJob)
    }
}
