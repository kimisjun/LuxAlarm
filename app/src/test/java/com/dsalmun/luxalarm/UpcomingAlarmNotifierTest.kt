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
