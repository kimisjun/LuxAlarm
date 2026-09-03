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

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.EVERY_DAY
import com.dsalmun.luxalarm.testing.alarm
import com.dsalmun.luxalarm.testing.uiState
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A 48sp clock beside a switch: the largest font scale pushes a control out of reach. */
@RunWith(AndroidJUnit4::class)
class AlarmScreenLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun onACompactWindow_theListAndItsControlsAreReachable() {
        setContent(DpSize(400.dp, 500.dp))

        assertCoreControlsVisible()
    }

    @Test
    fun onAMediumWindow_theListAndItsControlsAreReachable() {
        setContent(DpSize(610.dp, 500.dp))

        assertCoreControlsVisible()
    }

    @Test
    fun onAnExpandedWindow_theListAndItsControlsAreReachable() {
        setContent(DpSize(900.dp, 1000.dp))

        assertCoreControlsVisible()
    }

    @Test
    fun onAShortWindow_theListAndItsControlsAreReachable() {
        setContent(DpSize(400.dp, 400.dp))

        assertCoreControlsVisible()
    }

    @Test
    fun atLargeFontScale_theRowStillExposesItsControls() {
        setContent(DpSize(400.dp, 500.dp), fontScale = 1.5f)

        assertCoreControlsVisible()
    }

    /** The status line and its action share a row, so a long status could crowd the button out. */
    @Test
    fun atLargeFontScale_anUpcomingRowStillExposesItsAction() {
        setContent(
            DpSize(400.dp, 500.dp),
            fontScale = 1.5f,
            state =
                uiState(
                    alarm(id = 1, hour = 7, minute = 5, repeatDays = EVERY_DAY),
                    isUpcoming = true,
                ),
        )

        composeRule.onNodeWithText("Skip next").assertIsDisplayed()
    }

    @Test
    fun atLargeFontScale_aSkippedRowStillExposesItsUndo() {
        setContent(
            DpSize(400.dp, 500.dp),
            fontScale = 1.5f,
            state =
                uiState(
                    alarm(id = 1, hour = 7, minute = 5, repeatDays = EVERY_DAY),
                    isSkippingNext = true,
                ),
        )

        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun atLargeFontScale_anExpandedRowStillExposesItsControls() {
        setContent(DpSize(400.dp, 1000.dp), fontScale = 1.5f, expandedAlarmId = 1)

        composeRule.onNodeWithContentDescription("Vibration enabled").assertExists()
        composeRule.onNodeWithContentDescription("Delete alarm").assertExists()
        composeRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    private fun assertCoreControlsVisible() {
        composeRule.onNodeWithText("GentleWake · 부드러운 기상").assertIsDisplayed()
        composeRule.onNodeWithText("07:05").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add Alarm").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Alarm enabled").assertIsDisplayed()
    }

    private fun setContent(
        size: DpSize,
        fontScale: Float = 1f,
        expandedAlarmId: Int? = null,
        state: AlarmViewModel.AlarmUiState = uiState(alarm(id = 1, hour = 7, minute = 5)),
    ) {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(size) then
                        DeviceConfigurationOverride.FontScale(fontScale)
                ) {
                    Content(expandedAlarmId, state)
                }
            }
        }
    }

    @Composable
    private fun Content(expandedAlarmId: Int?, state: AlarmViewModel.AlarmUiState) {
        AlarmScreenContent(
            alarmStates = listOf(state),
            expandedAlarmId = expandedAlarmId,
            ringtoneNameFor = { "Bright Morning" },
            onSettingsClick = {},
            onAddClick = {},
            onAlarmClick = {},
            onTimeClick = {},
            onToggle = { _, _ -> },
            onSkip = {},
            onCancelSkip = {},
            onRepeatDaysChange = { _, _ -> },
            onVolumeChange = { _, _ -> },
            onVibrationToggle = { _, _ -> },
            onDeleteClick = {},
            onRingtoneClick = {},
        )
    }
}
