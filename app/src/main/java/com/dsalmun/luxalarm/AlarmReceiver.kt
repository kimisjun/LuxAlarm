/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val alarmIds = intent?.getIntegerArrayListExtra("alarm_ids") ?: arrayListOf()
        val alarmId = alarmIds.firstOrNull() ?: -1
        val wakeProfile = AppContainer.settingsManager.getWakeProfile()
        val ringtoneUri = resolveWakeRingtone(wakeProfile, intent?.getStringExtra("ringtone_uri"))
        val volume = intent?.getFloatExtra("volume", 1f) ?: 1f
        val vibrationEnabled = intent?.getBooleanExtra("vibration_enabled", true) ?: true

        if (AppContainer.repository.setRingingAlarm()) {
            val serviceIntent =
                Intent(context, AlarmService::class.java).apply {
                    putExtra("alarm_id", alarmId)
                    putExtra("ringtone_uri", ringtoneUri)
                    putExtra("volume", volume)
                    putExtra("vibration_enabled", vibrationEnabled)
                    putExtra("gentle_wake", true)
                    putExtra("ramp_minutes", wakeProfile.rampMinutes)
                    putExtra("start_volume", wakeProfile.startVolume)
                    putExtra("max_volume", wakeProfile.maxVolume)
                    putExtra("dismissal", wakeProfile.dismissal.name)
                }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        val pendingResult = goAsync()
        CoroutineScope(AppContainer.ioDispatcher).launch {
            try {
                AppContainer.repository.deactivateOneShotAlarms(alarmIds.toList())
                AppContainer.repository.scheduleNextAlarm()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resolveWakeRingtone(profile: WakeProfile, fallbackRingtoneUri: String?): String? {
        val importedFile = profile.importedAudioPath?.let(::File)?.takeIf { it.isFile }
        return importedFile?.let(Uri::fromFile)?.toString() ?: fallbackRingtoneUri
    }
}
