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

class BootReceiver : BroadcastReceiver() {
    companion object {
        /** Some OEMs send a quick-boot action instead of `BOOT_COMPLETED`. */
        private val BOOT_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON",
            )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in BOOT_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(AppContainer.ioDispatcher).launch {
            try {
                // The HTC action is unprotected, so a spoofed one must not strand a live alarm.
                if (!AlarmService.isRunning) {
                    AppContainer.repository.clearRingingAlarm()
                }
                AppContainer.repository.scheduleNextAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
