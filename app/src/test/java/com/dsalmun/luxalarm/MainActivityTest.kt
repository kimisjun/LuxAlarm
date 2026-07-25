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
package com.dsalmun.luxalarm

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Beyond hosting the UI, [MainActivity] asks for permissions and recovers when reopened mid-alarm.
 * `isAlarmRinging` is persisted but [AlarmService] is not, so a killed process leaves the flag set
 * with no service behind it — and every future alarm blocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MainActivityTest {
    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var application: Application
    private var controller: ActivityController<MainActivity>? = null

    private val repository
        get() = appContainer.repository

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        // Nothing to ask for by default; individual tests revoke what they are about.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repository.setShouldSucceed(true)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        // A static that Robolectric's per-SDK sandbox classloader carries into the next test class.
        AlarmService.isRunning = false
    }

    @Test
    fun whenAnAlarmIsRingingAndTheServiceIsAlive_theAlarmScreenIsBroughtBack() {
        repository.setRingingAlarm()
        AlarmService.isRunning = true

        startAndSettle()
        resume()

        val started = assertNotNull(nextStartedActivity(), "The user must land back on the alarm")
        assertEquals(AlarmActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals(0, repository.clearRingingAlarmCallCount, "The alarm is genuinely still live")
    }

    /** The flag outlived its service; nothing but reopening the app will ever clear it. */
    @Test
    fun whenTheFlagIsStaleWithNoService_itIsCleared() {
        repository.setRingingAlarm()
        AlarmService.isRunning = false

        startAndSettle()
        resume()

        assertNull(nextStartedActivity(), "There is no alarm to return to")
        assertEquals(1, repository.clearRingingAlarmCallCount)
        assertFalse(repository.isAlarmRinging())
    }

    @Test
    fun whenNoAlarmIsRinging_resumingDoesNothing() {
        AlarmService.isRunning = true

        startAndSettle()
        resume()

        assertNull(nextStartedActivity())
        assertEquals(0, repository.clearRingingAlarmCallCount)
    }

    @Test
    fun withoutNotificationPermission_itIsRequested() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val activity = create()

        val request =
            assertNotNull(
                shadowOf(activity).lastRequestedPermission,
                "The alarm's foreground notification needs this permission",
            )
        assertTrue(
            request.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS),
            "Requested ${request.requestedPermissions.toList()}",
        )
    }

    @Test
    fun withNotificationPermission_nothingIsRequested() {
        val activity = create()

        assertNull(shadowOf(activity).lastRequestedPermission)
    }

    /** Without exact alarms the OS may delay an alarm by minutes, which is as good as silence. */
    @Test
    fun withoutExactAlarmPermission_theSystemSettingIsOpened() {
        repository.setShouldSucceed(false)

        create()

        val intent =
            assertNotNull(
                startedIntentFor(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
                "The exact-alarm setting must be opened",
            )
        assertEquals("package:${application.packageName}", intent.data?.toString())
    }

    @Test
    fun withExactAlarmPermission_theSystemSettingIsNotOpened() {
        create()

        assertNull(startedIntentFor(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
    }

    /**
     * Robolectric reports the permission as unheld, so only the "ask for it" branch is reachable.
     */
    @Test
    fun withoutFullScreenIntentPermission_theSystemSettingIsOpened() {
        create()

        val intent =
            assertNotNull(
                startedIntentFor(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT),
                "The full-screen-intent setting must be opened",
            )
        assertEquals("package:${application.packageName}", intent.data?.toString())
    }

    private fun create(): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java).create()
        controller = c
        return c.get()
    }

    /** Discards whatever `onCreate` launched, which would be mistaken for `onResume`'s intent. */
    private fun startAndSettle(): MainActivity {
        val activity = create()
        controller?.start()
        while (nextStartedActivity() != null) {
            // drain
        }
        return activity
    }

    private fun resume() {
        controller?.resume()
    }

    private fun nextStartedActivity(): Intent? = shadowOf(application).nextStartedActivity

    /**
     * `onCreate` may open more than one settings screen, so match on the action, not on a count.
     */
    private fun startedIntentFor(action: String): Intent? =
        generateSequence { nextStartedActivity() }.firstOrNull { it.action == action }
}
