/*
 * This file is part of Lux Alarm, authored by Daniel Salmun.
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not covered here: a *system* back press. In activity-compose 1.13 `BackHandler` runs over
 * `NavigationEventDispatcher`, which `onBackPressedDispatcher` no longer reaches under Robolectric.
 * The back arrow drives the same state transition; the gesture itself needs an instrumented test.
 */
@RunWith(AndroidJUnit4::class)
class LuxAlarmAppNavigationTest {
    @get:Rule(order = 0) val appContainer = AppContainerTestRule()
    @get:Rule(order = 1) val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun startsOnTheAlarmList() {
        setContent()

        composeRule.onNodeWithText("Lux Alarm").assertIsDisplayed()
        composeRule.onNodeWithText("Required Light Level").assertDoesNotExist()
    }

    @Test
    fun theSettingsIcon_opensSettings() {
        setContent()

        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.onNodeWithText("Required Light Level").assertIsDisplayed()
        composeRule.onNodeWithText("Lux Alarm").assertDoesNotExist()
    }

    @Test
    fun theBackArrow_returnsToTheAlarmList() {
        setContent()
        composeRule.onNodeWithContentDescription("Settings").performClick()

        composeRule.onNodeWithContentDescription("Back").performClick()

        composeRule.onNodeWithText("Lux Alarm").assertIsDisplayed()
    }

    /** A rotation recreates the activity, so the flag has to be saved, not merely remembered. */
    @Test
    fun theOpenPane_survivesAConfigurationChange() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent { LuxAlarmTheme(dynamicColor = false) { LuxAlarmApp() } }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("Required Light Level").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Required Light Level").assertIsDisplayed()
    }

    private fun setContent() {
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) { LuxAlarmApp() }
        }
    }
}
