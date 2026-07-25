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

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A reboot wipes `AlarmManager`, so without this receiver every alarm silently stops working. The
 * persisted ringing flag survives instead, and a stale one blocks every future alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BootReceiverTest {
    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var context: Context

    private val repository
        get() = appContainer.repository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun onBoot_theStaleRingingFlagIsClearedAndAlarmsAreRescheduled() {
        assertTrue(repository.setRingingAlarm(), "Precondition: an alarm was ringing at shutdown")

        sendBootBroadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(0, repository.scheduleNextAlarmCallCount, "The async work has not run yet")

        appContainer.scheduler.advanceUntilIdle()

        assertFalse(
            repository.isAlarmRinging(),
            "A reboot leaves no service, so nothing is ringing",
        )
        assertEquals(1, repository.clearRingingAlarmCallCount)
        assertEquals(1, repository.scheduleNextAlarmCallCount)
    }

    /** The receiver is exported, so the action guard is all that stops a stray broadcast. */
    @Test
    fun anyOtherAction_isIgnored() {
        assertTrue(repository.setRingingAlarm(), "Precondition: an alarm is ringing")

        sendBootBroadcast(Intent.ACTION_SCREEN_ON)
        appContainer.scheduler.advanceUntilIdle()

        assertTrue(repository.isAlarmRinging(), "A live alarm must survive an unrelated broadcast")
        assertEquals(0, repository.clearRingingAlarmCallCount)
        assertEquals(0, repository.scheduleNextAlarmCallCount)
    }

    @Test
    fun aNullIntent_isIgnored() {
        BootReceiver().onReceive(context, null)
        appContainer.scheduler.advanceUntilIdle()

        assertEquals(0, repository.clearRingingAlarmCallCount)
        assertEquals(0, repository.scheduleNextAlarmCallCount)
    }

    /** Dispatched through the framework: `goAsync` needs the pending result it installs. */
    private fun sendBootBroadcast(action: String) {
        context.sendBroadcast(
            Intent(context, BootReceiver::class.java).apply { this.action = action }
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
