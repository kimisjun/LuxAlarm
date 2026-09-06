/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app never reschedules on launch, so a missed action leaves the alarm wrong until edited. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RescheduleReceiverTest {
    private companion object {
        /** Duplicated rather than read off the receiver, so dropping one there fails a test. */
        val HANDLED_ACTIONS =
            listOf(
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            )
    }

    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var context: Context

    private val repository
        get() = appContainer.repository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun everyHandledAction_reschedulesExactlyOnce() {
        HANDLED_ACTIONS.forEachIndexed { index, action ->
            sendBroadcast(action)
            appContainer.scheduler.advanceUntilIdle()

            assertEquals(index + 1, repository.scheduleNextAlarmCallCount, "$action rescheduled")
        }
    }

    @Test
    fun theRescheduleIsDeferredToTheAsyncWork() {
        sendBroadcast(Intent.ACTION_TIMEZONE_CHANGED)

        assertEquals(0, repository.scheduleNextAlarmCallCount, "The async work has not run yet")

        appContainer.scheduler.advanceUntilIdle()

        assertEquals(1, repository.scheduleNextAlarmCallCount)
    }

    /** The receiver is exported, so the action guard is all that stops a stray broadcast. */
    @Test
    fun anUnrelatedAction_isIgnored() {
        sendBroadcast(Intent.ACTION_SCREEN_ON)
        appContainer.scheduler.advanceUntilIdle()

        assertEquals(0, repository.scheduleNextAlarmCallCount)
    }

    @Test
    fun aNullIntent_isIgnored() {
        RescheduleReceiver().onReceive(context, null)
        appContainer.scheduler.advanceUntilIdle()

        assertEquals(0, repository.scheduleNextAlarmCallCount)
    }

    /** The whole reason this is not folded into [BootReceiver], which clears the flag. */
    @Test
    fun aRingingAlarm_isLeftAlone() {
        assertTrue(repository.setRingingAlarm(), "Precondition: an alarm is ringing")

        HANDLED_ACTIONS.forEach { action ->
            sendBroadcast(action)
            appContainer.scheduler.advanceUntilIdle()

            assertTrue(repository.isAlarmRinging(), "$action must not stop a live alarm")
        }

        assertEquals(0, repository.clearRingingAlarmCallCount)
    }

    @Test
    fun whenExactAlarmsAreStillDenied_theRescheduleIsStillAttempted() {
        repository.setShouldSucceed(false)

        sendBroadcast(Intent.ACTION_TIMEZONE_CHANGED)
        appContainer.scheduler.advanceUntilIdle()

        assertEquals(1, repository.scheduleNextAlarmCallCount)
    }

    /** Warmly must not re-arm the legacy multi-alarm repository before its scheduler exists. */
    @Test
    fun legacyRescheduleReceiverIsDisabledForTheWarmlyFirstSlice() {
        HANDLED_ACTIONS.forEach { action ->
            assertNull(resolve(action), "$action must not resolve to the disabled legacy receiver")
        }
    }

    private fun resolve(action: String): ResolveInfo? =
        context.packageManager
            .queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            .firstOrNull { it.activityInfo.name == RescheduleReceiver::class.java.name }

    /** Dispatched through the framework: `goAsync` needs the pending result it installs. */
    private fun sendBroadcast(action: String) {
        context.sendBroadcast(
            Intent(context, RescheduleReceiver::class.java).apply { this.action = action }
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
