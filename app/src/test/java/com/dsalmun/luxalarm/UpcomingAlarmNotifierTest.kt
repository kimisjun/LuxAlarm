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
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The POST_NOTIFICATIONS guard: some OEMs throw from `notify()` rather than no-opping. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpcomingAlarmNotifierTest {
    private companion object {
        const val TRIGGER_MILLIS = 1_700_000_000_000L
        val ALARM_IDS = listOf(1, 2)
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        UpcomingAlarmNotifier.cancel(context)
    }

    @Test
    fun whenNotificationsAreEnabled_theNoticeIsPosted() {
        setNotificationsEnabled(true)

        UpcomingAlarmNotifier.post(context, ALARM_IDS, TRIGGER_MILLIS)

        assertNotNull(upcomingNotification())
    }

    @Test
    fun whenNotificationsAreDenied_nothingIsPosted() {
        setNotificationsEnabled(false)

        UpcomingAlarmNotifier.post(context, ALARM_IDS, TRIGGER_MILLIS)

        assertNull(upcomingNotification(), "A denied permission must not reach notify()")
    }

    /** The channel is created before the permission check, so a later grant finds it configured. */
    @Test
    fun whenNotificationsAreDenied_theChannelIsStillCreated() {
        setNotificationsEnabled(false)

        UpcomingAlarmNotifier.post(context, ALARM_IDS, TRIGGER_MILLIS)

        val channel = notificationManager().getNotificationChannel("upcoming_channel_id")
        assertNotNull(channel, "The channel is set up regardless of the permission")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun setNotificationsEnabled(enabled: Boolean) {
        shadowOf(notificationManager()).setNotificationsEnabled(enabled)
    }

    private fun upcomingNotification(): Notification? =
        shadowOf(notificationManager()).getNotification(UpcomingAlarmNotifier.NOTIFICATION_ID)
}
