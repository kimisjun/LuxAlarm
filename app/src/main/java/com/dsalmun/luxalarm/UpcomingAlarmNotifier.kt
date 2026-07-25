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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.DateFormat
import java.util.Date

/**
 * The silent "Upcoming alarm" notification shown ahead of the next alarm. Swiping it away only
 * hides it — the alarm still rings; only the Dismiss action skips that occurrence.
 */
object UpcomingAlarmNotifier {
    private const val CHANNEL_ID = "upcoming_channel_id"
    const val NOTIFICATION_ID = 1002
    private const val SKIP_REQUEST_CODE = 2
    private const val CONTENT_REQUEST_CODE = 3

    fun post(context: Context, alarmIds: List<Int>, triggerMillis: Long) {
        createChannel(context)

        val timeText = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(triggerMillis))

        val contentIntent =
            PendingIntent.getActivity(
                context,
                CONTENT_REQUEST_CODE,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val skipIntent =
            Intent(context, UpcomingAlarmReceiver::class.java).apply {
                action = UpcomingAlarmReceiver.ACTION_SKIP
                putIntegerArrayListExtra(UpcomingAlarmReceiver.EXTRA_ALARM_IDS, ArrayList(alarmIds))
                putExtra(UpcomingAlarmReceiver.EXTRA_TRIGGER_MILLIS, triggerMillis)
            }
        val skipPendingIntent =
            PendingIntent.getBroadcast(
                context,
                SKIP_REQUEST_CODE,
                skipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Upcoming alarm")
                .setContentText("Alarm set for $timeText")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .setOngoing(false)
                .addAction(0, "Dismiss", skipPendingIntent)
                .build()

        // Without POST_NOTIFICATIONS, notify() is a no-op — and a SecurityException on some OEMs.
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    "Upcoming alarms",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "Advance notice before an alarm rings"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
