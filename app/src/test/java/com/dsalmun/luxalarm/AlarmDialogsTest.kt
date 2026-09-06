/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePickerState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.alarm
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The two dialogs the alarm list puts up, tested away from the screen that owns their state. */
@RunWith(AndroidJUnit4::class)
class AlarmDialogsTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var confirmed = 0
    private var dismissed = 0

    @Test
    fun deleteDialog_namesTheAlarmBeingDeleted() {
        setDeleteDialog()

        composeRule.onNodeWithText("Delete alarm").assertIsDisplayed()
        composeRule.onNodeWithText("Delete the 07:05 alarm?").assertIsDisplayed()
    }

    @Test
    fun deleteDialog_deleteConfirms() {
        setDeleteDialog()

        // Exact match, so the "Delete alarm" title does not collide with the button.
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun deleteDialog_cancelDismisses() {
        setDeleteDialog()

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun timePickerDialog_showsItsTitleAndButtons() {
        setTimePickerDialog()

        composeRule.onNodeWithText("Set Alarm Time").assertIsDisplayed()
        composeRule.onNodeWithText("Set").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun timePickerDialog_setConfirms() {
        setTimePickerDialog()

        composeRule.onNodeWithText("Set").performClick()

        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun timePickerDialog_cancelDismisses() {
        setTimePickerDialog()

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    private fun setDeleteDialog() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                DeleteAlarmDialog(
                    alarm = alarm(id = 1, hour = 7, minute = 5),
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun setTimePickerDialog() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                TimePickerDialog(
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    timePickerState =
                        TimePickerState(initialHour = 7, initialMinute = 30, is24Hour = true),
                )
            }
        }
    }
}
