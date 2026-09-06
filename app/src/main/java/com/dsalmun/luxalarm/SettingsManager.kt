/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WakeProfile(
    val rampMinutes: Int = WakeRamp.DEFAULT_RAMP_MINUTES,
    val startVolume: Float = WakeRamp.DEFAULT_START_VOLUME,
    val maxVolume: Float = WakeRamp.DEFAULT_MAX_VOLUME,
    val dismissal: WakeDismissal = WakeDismissal.DEFAULT,
    val importedAudioPath: String? = null,
)

internal fun interface SharedPreferencesWritePort {
    fun commitOrRestore(
        attempted: SharedPreferences.Editor,
        restore: SharedPreferences.Editor,
    ): Boolean
}

internal class CommitRestoringSharedPreferencesWritePort(
    private val commitEditor: (SharedPreferences.Editor) -> Boolean
) : SharedPreferencesWritePort {
    override fun commitOrRestore(
        attempted: SharedPreferences.Editor,
        restore: SharedPreferences.Editor,
    ): Boolean =
        try {
            val committed = commitEditor(attempted)
            if (!committed) restore.apply()
            committed
        } catch (cause: Exception) {
            runCatching(restore::apply).onFailure(cause::addSuppressed)
            throw cause
        }
}

class SettingsManager(
    context: Context,
    commitEditor: (SharedPreferences.Editor) -> Boolean = { it.commit() },
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val writePort: SharedPreferencesWritePort =
        CommitRestoringSharedPreferencesWritePort(commitEditor)

    private val _requiredLuxLevel = MutableStateFlow(getRequiredLuxLevel())
    val requiredLuxLevel: StateFlow<Float> = _requiredLuxLevel.asStateFlow()

    private val _wakeProfile = MutableStateFlow(getWakeProfile())
    val wakeProfile: StateFlow<WakeProfile> = _wakeProfile.asStateFlow()

    fun getRequiredLuxLevel(): Float {
        return prefs.getFloat(KEY_REQUIRED_LUX_LEVEL, DEFAULT_LUX_LEVEL)
    }

    fun setRequiredLuxLevel(level: Float) {
        prefs.edit { putFloat(KEY_REQUIRED_LUX_LEVEL, level) }
        _requiredLuxLevel.value = level
    }

    fun getWakeProfile(): WakeProfile = synchronized(wakeProfileLock) { readWakeProfile() }

    private fun readWakeProfile(): WakeProfile =
        WakeProfile(
            rampMinutes = prefs.getInt(KEY_WAKE_RAMP_MINUTES, WakeRamp.DEFAULT_RAMP_MINUTES),
            startVolume = prefs.getFloat(KEY_WAKE_START_VOLUME, WakeRamp.DEFAULT_START_VOLUME),
            maxVolume = prefs.getFloat(KEY_WAKE_MAX_VOLUME, WakeRamp.DEFAULT_MAX_VOLUME),
            dismissal =
                prefs.getString(KEY_WAKE_DISMISSAL, null)?.let { stored ->
                    WakeDismissal.entries.firstOrNull { it.name == stored }
                } ?: WakeDismissal.DEFAULT,
            importedAudioPath = prefs.getString(KEY_WAKE_IMPORTED_AUDIO_PATH, null),
        )

    /**
     * Replaces the complete profile under the same lock used by intent-specific mutations.
     * Production UI callbacks use the field APIs below so a stale UI snapshot cannot replace
     * unrelated fields.
     */
    fun updateWakeProfile(profile: WakeProfile) {
        commitWakeProfile(profile)
    }

    /** Durably publishes an imported-file reference before its pending marker may be cleared. */
    fun commitImportedAudioPath(path: String): Boolean = mutateWakeProfile { current ->
        current.copy(importedAudioPath = path)
    }

    fun setWakeDismissal(dismissal: WakeDismissal): Boolean = mutateWakeProfile { current ->
        current.copy(dismissal = dismissal)
    }

    /** Whole-profile compatibility API. Prefer intent-specific mutations in production UI. */
    fun commitWakeProfile(profile: WakeProfile): Boolean = mutateWakeProfile { profile }

    private fun mutateWakeProfile(transform: (WakeProfile) -> WakeProfile): Boolean =
        synchronized(wakeProfileLock) {
            val current = readWakeProfile()
            val profile = transform(current)
            if (profile == current) {
                _wakeProfile.value = profile
                return@synchronized true
            }
            val committed =
                writePort.commitOrRestore(
                    attempted = profileEditor(profile),
                    restore = profileEditor(current),
                )
            if (committed) _wakeProfile.value = profile
            committed
        }

    private fun profileEditor(profile: WakeProfile): SharedPreferences.Editor =
        prefs
            .edit()
            .putInt(KEY_WAKE_RAMP_MINUTES, profile.rampMinutes)
            .putFloat(KEY_WAKE_START_VOLUME, profile.startVolume)
            .putFloat(KEY_WAKE_MAX_VOLUME, profile.maxVolume)
            .putString(KEY_WAKE_DISMISSAL, profile.dismissal.name)
            .putString(KEY_WAKE_IMPORTED_AUDIO_PATH, profile.importedAudioPath)

    companion object {
        // All instances address the same named preferences file. Keeping this process-wide also
        // serializes a short-lived secondary manager with the AppContainer singleton.
        private val wakeProfileLock = Any()
        private const val PREFS_NAME = "lux_alarm_settings"
        private const val KEY_REQUIRED_LUX_LEVEL = "required_lux_level"
        private const val KEY_WAKE_RAMP_MINUTES = "wake_ramp_minutes"
        private const val KEY_WAKE_START_VOLUME = "wake_start_volume"
        private const val KEY_WAKE_MAX_VOLUME = "wake_max_volume"
        private const val KEY_WAKE_DISMISSAL = "wake_dismissal"
        private const val KEY_WAKE_IMPORTED_AUDIO_PATH = "wake_imported_audio_path"
        const val DEFAULT_LUX_LEVEL = 50f
        const val MIN_LUX_LEVEL = 1f
        const val MAX_LUX_LEVEL = 1000f
    }
}
