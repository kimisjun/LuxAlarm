/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AppContainer::class)
class MainActivityTest {
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun productionLauncherStartsWithoutLegacyRepositoryOrPermissionPrompts() {
        clearLegacyRepositoryForRegressionTest()
        val application = ApplicationProvider.getApplicationContext<AppContainer>()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val built = Robolectric.buildActivity(MainActivity::class.java).setup()
        controller = built
        val activity = built.get()

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun clearLegacyRepositoryForRegressionTest() {
        AppContainer::class.java.getDeclaredField("repository").apply {
            isAccessible = true
            set(null, null)
        }
    }
}
