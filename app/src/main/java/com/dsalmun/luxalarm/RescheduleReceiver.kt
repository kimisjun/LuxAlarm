/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Re-arms the next alarm after an event that invalidates the one already scheduled.
 *
 * Separate from [BootReceiver], which also clears the ringing flag: right after a reboot, wrong if
 * a time-zone change lands mid-ring.
 */
class RescheduleReceiver : BroadcastReceiver() {
    companion object {
        /** Inlined at compile time. Only reaches SCHEDULE_EXACT_ALARM holders, so never API 33+. */
        @SuppressLint("InlinedApi")
        private const val ACTION_EXACT_ALARM_STATE_CHANGED =
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED

        private val HANDLED_ACTIONS =
            setOf(
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ACTION_EXACT_ALARM_STATE_CHANGED,
            )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in HANDLED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(AppContainer.ioDispatcher).launch {
            try {
                AppContainer.repository.scheduleNextAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
