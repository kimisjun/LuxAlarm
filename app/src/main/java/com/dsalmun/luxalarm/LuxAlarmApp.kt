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

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * With only a list and a settings pane, a boolean and a [BackHandler] beat a navigation library.
 */
@Composable
fun LuxAlarmApp() {
    // Saveable, not remembered: MainActivity declares no configChanges, so a rotation recreates it
    // and would otherwise kick the user out of settings.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showGentleWakePreview by rememberSaveable { mutableStateOf(false) }
    var previewProgress by rememberSaveable { mutableFloatStateOf(0f) }

    BackHandler(enabled = showSettings || showGentleWakePreview) {
        showSettings = false
        showGentleWakePreview = false
    }
    if (showGentleWakePreview) {
        GentleWakePreview(
            progress = previewProgress,
            onProgressChange = { previewProgress = it },
            onAwake = { showGentleWakePreview = false },
        )
    } else if (showSettings) {
        SettingsScreen(onBackClick = { showSettings = false })
    } else {
        AlarmScreen(
            onSettingsClick = { showSettings = true },
            onGentleWakePreviewClick = { showGentleWakePreview = true },
        )
    }
}
