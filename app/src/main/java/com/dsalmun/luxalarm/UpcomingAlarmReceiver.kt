/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** [ACTION_SHOW] comes from AlarmManager, [ACTION_SKIP] from the notification's Dismiss action. */
class UpcomingAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_SHOW = "com.dsalmun.luxalarm.SHOW_UPCOMING"
        const val ACTION_SKIP = "com.dsalmun.luxalarm.SKIP_UPCOMING"
        const val EXTRA_ALARM_IDS = "alarm_ids"
        const val EXTRA_TRIGGER_MILLIS = "trigger_millis"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val alarmIds = intent?.getIntegerArrayListExtra(EXTRA_ALARM_IDS) ?: arrayListOf()
        val triggerMillis = intent?.getLongExtra(EXTRA_TRIGGER_MILLIS, 0L) ?: 0L

        when (intent?.action) {
            ACTION_SHOW -> UpcomingAlarmNotifier.post(context, alarmIds, triggerMillis)
            ACTION_SKIP -> {
                val pendingResult = goAsync()
                // Clear *before* skipping: skipAlarms reschedules and may post a fresh notification
                // for the following occurrence, which cancelling afterwards would wipe instead.
                UpcomingAlarmNotifier.cancel(context)
                CoroutineScope(AppContainer.ioDispatcher).launch {
                    try {
                        if (!AppContainer.repository.skipAlarms(alarmIds.toList(), triggerMillis)) {
                            // The skip was reverted, so restore the notification we just cleared.
                            UpcomingAlarmNotifier.post(context, alarmIds, triggerMillis)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
