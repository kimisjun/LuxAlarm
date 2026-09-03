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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GentleWakePreviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun halfProgressUsesTheRampAndOffersALargeKoreanConfirmation() {
        var confirmations = 0
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(progress = 0.5f, onAwake = { confirmations++ })
            }
        }

        composeRule.onNodeWithText("부드럽게 깨어날 시간이에요").assertIsDisplayed()
        composeRule.onNodeWithText("진행 50% · 화면 51% · 음악 20%").assertIsDisplayed()
        composeRule.onNodeWithTag("gentle-wake-preview").assertIsDisplayed()
        composeRule
            .onNodeWithText("일어났어요")
            .assertWidthIsAtLeast(240.dp)
            .assertHeightIsAtLeast(64.dp)
            .performClick()

        assertEquals(1, confirmations)
    }

    @Test
    fun progressControlScrubsThroughDeterministicRampFrames() {
        val progress = mutableFloatStateOf(0f)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(
                    progress = progress.floatValue,
                    onProgressChange = { progress.floatValue = it },
                    onAwake = {},
                )
            }
        }

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        composeRule.onNodeWithText("진행 75% · 화면 85% · 음악 30%").assertIsDisplayed()
    }
}
