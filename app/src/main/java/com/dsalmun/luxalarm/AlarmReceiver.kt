/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
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
