/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.content.SharedPreferences

/** Read-only adapter; callers decide which already-open Room query supplies the legacy rows. */
internal class RoomOpenedLegacyAlarmSource(private val readOnlyQuery: () -> List<AlarmItem>) :
    LegacyAlarmSource {
    override fun readAlarms(): List<LegacyAlarmSnapshot> =
        readOnlyQuery().map { alarm ->
            LegacyAlarmSnapshot(
                id = alarm.id,
                hour = alarm.hour,
                minute = alarm.minute,
                isActive = alarm.isActive,
                repeatDays = alarm.repeatDays,
                ringtoneUri = alarm.ringtoneUri,
                volume = alarm.volume,
                vibrationEnabled = alarm.vibrationEnabled,
                skippedOccurrenceDay = alarm.skippedOccurrenceDay,
            )
        }
}

/** Reads known legacy keys without editing them; malformed types become invalid proposal inputs. */
internal class SharedPreferencesLegacyWakeSettingsSource(
    private val preferences: SharedPreferences
) : LegacyWakeSettingsSource {
    override fun readSettings(): LegacyWakeSettingsSnapshot {
        val values = preferences.all
        return LegacyWakeSettingsSnapshot(
            requiredLuxLevel = values["required_lux_level"] as? Float,
            rampMinutes = values["wake_ramp_minutes"] as? Int,
            startVolume = values["wake_start_volume"] as? Float,
            maxVolume = values["wake_max_volume"] as? Float,
            dismissal = values["wake_dismissal"] as? String,
            importedAudioPath = values["wake_imported_audio_path"] as? String,
        )
    }
}
