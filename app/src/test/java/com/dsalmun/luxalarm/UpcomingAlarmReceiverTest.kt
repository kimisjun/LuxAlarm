/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A skip that fails has to put the notification back, or the alarm is silently un-skipped. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpcomingAlarmReceiverTest {
    private companion object {
        const val TRIGGER_MILLIS = 1_700_000_000_000L
    }

    @get:Rule val appContainer = AppContainerTestRule()

    private lateinit var context: Context

    private val repository
        get() = appContainer.repository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        UpcomingAlarmNotifier.cancel(context)
    }

    @Test
    fun showAction_postsTheUpcomingNotification() {
        UpcomingAlarmReceiver().onReceive(context, intentFor(UpcomingAlarmReceiver.ACTION_SHOW))

        assertNotNull(upcomingNotification(), "ACTION_SHOW should post the upcoming notice")
    }

    /** The receiver is exported, so an intent carrying neither action can arrive. */
    @Test
    fun anUnrecognisedAction_isIgnored() {
        UpcomingAlarmReceiver().onReceive(context, intentFor("com.dsalmun.luxalarm.NOT_AN_ACTION"))
        runSkipToCompletion()

        assertNull(upcomingNotification())
        assertEquals(0, repository.skipAlarmsCallCount)
    }

    /** A null intent reaches the extras reads before the `when`, so the defaults have to hold. */
    @Test
    fun aNullIntent_isIgnored() {
        UpcomingAlarmReceiver().onReceive(context, null)
        runSkipToCompletion()

        assertNull(upcomingNotification())
        assertEquals(0, repository.skipAlarmsCallCount)
    }

    @Test
    fun skipAction_forwardsIdsAndTriggerToTheRepository() {
        sendSkipBroadcast()
        runSkipToCompletion()

        assertEquals(1, repository.skipAlarmsCallCount)
        assertEquals(listOf(1, 2), repository.lastSkipIds)
        assertEquals(TRIGGER_MILLIS, repository.lastSkipTriggerMillis)
    }

    /** A successful skip may post a notice for the *next* occurrence, which a late cancel wipes. */
    @Test
    fun skipAction_clearsTheNotificationBeforeTheSkipRuns() {
        UpcomingAlarmNotifier.post(context, listOf(1, 2), TRIGGER_MILLIS)
        assertNotNull(upcomingNotification(), "Precondition: the notice is showing")

        sendSkipBroadcast()

        assertNull(upcomingNotification(), "The notice is cleared before the skip is attempted")
        assertEquals(0, repository.skipAlarmsCallCount, "The skip has not run yet")
    }

    @Test
    fun skipAction_whenSkipSucceeds_leavesTheNotificationCleared() {
        repository.setShouldSucceed(true)
        UpcomingAlarmNotifier.post(context, listOf(1, 2), TRIGGER_MILLIS)
        assertNotNull(upcomingNotification(), "Precondition: the notice is showing")

        sendSkipBroadcast()
        runSkipToCompletion()

        assertEquals(1, repository.skipAlarmsCallCount)
        // Rescheduling owns the notification from here on; the receiver must not re-post it.
        assertNull(upcomingNotification())
    }

    @Test
    fun skipAction_whenSkipFails_restoresTheNotification() {
        repository.setShouldSucceed(false)
        UpcomingAlarmNotifier.post(context, listOf(1, 2), TRIGGER_MILLIS)
        assertNotNull(upcomingNotification(), "Precondition: the notice is showing")

        sendSkipBroadcast()
        runSkipToCompletion()

        assertEquals(1, repository.skipAlarmsCallCount)
        assertNotNull(upcomingNotification(), "A reverted skip must put the notice back")
    }

    private fun intentFor(action: String): Intent =
        Intent(context, UpcomingAlarmReceiver::class.java).apply {
            this.action = action
            putIntegerArrayListExtra(UpcomingAlarmReceiver.EXTRA_ALARM_IDS, arrayListOf(1, 2))
            putExtra(UpcomingAlarmReceiver.EXTRA_TRIGGER_MILLIS, TRIGGER_MILLIS)
        }

    /** Dispatched through the framework: `goAsync` needs the pending result it installs. */
    private fun sendSkipBroadcast() {
        context.sendBroadcast(intentFor(UpcomingAlarmReceiver.ACTION_SKIP))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runSkipToCompletion() {
        appContainer.scheduler.advanceUntilIdle()
    }

    private fun upcomingNotification(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(UpcomingAlarmNotifier.NOTIFICATION_ID)
}
