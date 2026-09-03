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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The threshold branch also drives colour, which semantics cannot see; text only here. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenContentTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var backClicks = 0
    private val changes = mutableListOf<Float>()
    private var finished = 0

    @Test
    fun showsTheTitleAndBackAffordance() {
        setContent()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun theBackButton_reportsBack() {
        setContent()

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backClicks)
    }

    @Test
    fun showsTheCurrentReadingTheThresholdAndTheRangeEnds() {
        setContent(currentLightLevel = 120f, requiredLuxLevel = 50f)

        composeRule.onNodeWithText("Required Light Level").assertIsDisplayed()
        composeRule.onNodeWithText("Current Light Level").assertIsDisplayed()
        composeRule.onNodeWithText("120 lux").assertIsDisplayed()
        composeRule.onNodeWithText("50 lux").assertIsDisplayed()
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("1000").assertIsDisplayed()
    }

    @Test
    fun whenTheRoomIsBrightEnough_saysSo() {
        setContent(currentLightLevel = 120f, requiredLuxLevel = 50f)

        composeRule.onNodeWithText("✓ Current light level meets threshold").assertIsDisplayed()
        composeRule.onNodeWithText("Current light is below threshold").assertDoesNotExist()
    }

    @Test
    fun whenTheRoomIsTooDark_saysSo() {
        setContent(currentLightLevel = 10f, requiredLuxLevel = 50f)

        composeRule.onNodeWithText("Current light is below threshold").assertIsDisplayed()
        composeRule.onNodeWithText("✓ Current light level meets threshold").assertDoesNotExist()
    }

    /** Guards the `>=`: at exactly the threshold the alarm must already be dismissable. */
    @Test
    fun atExactlyTheThreshold_countsAsBrightEnough() {
        setContent(currentLightLevel = 50f, requiredLuxLevel = 50f)

        composeRule.onNodeWithText("✓ Current light level meets threshold").assertIsDisplayed()
    }

    @Test
    fun draggingTheSlider_reportsTheValueAndTheRelease() {
        setContent(currentLightLevel = 0f, requiredLuxLevel = 50f)

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }

        assertEquals(1, changes.size)
        assertTrue(
            kotlin.math.abs(changes.single() - 300f) < 0.5f,
            "Expected roughly 300 lux, got ${changes.single()}",
        )
        assertEquals(1, finished, "Releasing the slider is what persists the value")
    }

    @Test
    fun gentleWakeSettingsShowDefaultsAndReportWholeProfileAndAudioActions() {
        val profiles = mutableListOf<WakeProfile>()
        var audioPicks = 0
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                SettingsScreenContent(
                    requiredLuxLevel = 50f,
                    currentLightLevel = 0f,
                    wakeProfile = WakeProfile(),
                    onBackClick = {},
                    onLuxLevelChange = {},
                    onLuxLevelChangeFinished = {},
                    onWakeProfileChange = { profiles += it },
                    onImportAudioClick = { audioPicks++ },
                )
            }
        }

        composeRule.onNodeWithText("20분 · 5% → 35%").assertIsDisplayed()
        composeRule.onNodeWithText("알람 시각부터 점진 실행 · 목표 시각 전 시작은 아직 지원하지 않아요").assertIsDisplayed()
        composeRule.onNodeWithText("Lux 미션 (선택)").performClick()
        composeRule.onNodeWithText("휴대폰 음악 가져오기").performClick()

        assertEquals(listOf(WakeProfile(dismissal = WakeDismissal.LUX)), profiles)
        assertEquals(1, audioPicks)
    }

    private fun setContent(currentLightLevel: Float = 0f, requiredLuxLevel: Float = 50f) {
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) {
                SettingsScreenContent(
                    requiredLuxLevel = requiredLuxLevel,
                    currentLightLevel = currentLightLevel,
                    onBackClick = { backClicks++ },
                    onLuxLevelChange = { changes += it },
                    onLuxLevelChangeFinished = { finished++ },
                )
            }
        }
    }
}
