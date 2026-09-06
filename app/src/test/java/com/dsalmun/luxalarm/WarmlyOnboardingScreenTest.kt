/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarmlyOnboardingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun use24HourTime() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    @Test
    fun selectedWakeTimeDrivesExactlyThreeBedtimeRecommendations() {
        setContent()
        composeRule.onNodeWithText("Create my first sleep plan").performClick()

        composeRule.onNodeWithContentDescription("Increase wake time by 15 minutes").performClick()
        composeRule.onNodeWithText("07:15").assertIsDisplayed()
        composeRule.onNodeWithText("Next").performClick()

        composeRule.onAllNodes(hasTestTag("bedtime-recommendation")).assertCountEquals(3)
        composeRule.onNodeWithText("22:00").assertIsDisplayed()
        composeRule.onNodeWithText("23:00").performClick()
        composeRule.onNodeWithText("Selected bedtime · 23:00").assertIsDisplayed()
        composeRule.onNodeWithText("23:30").assertIsDisplayed()
    }

    @Test
    fun directBedtimeCanBeAdjustedAndSelected() {
        reachBedtimeStep()

        composeRule.onNodeWithText("Set a different time").performClick()
        composeRule.onNodeWithText("Choose your bedtime").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Increase bedtime by 15 minutes").performClick()
        composeRule.onNodeWithText("23:00").assertIsDisplayed()
        composeRule.onNodeWithText("Use this time").performClick()

        composeRule.onNodeWithText("Selected bedtime · 23:00").assertIsDisplayed()
    }

    @Test
    fun selectedRecommendationCompletesOneSleepPlan() {
        var completed: SleepPlan? = null
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WarmlyOnboardingScreen(onPlanComplete = { completed = it })
            }
        }
        composeRule.onNodeWithText("Create my first sleep plan").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("22:45").performClick()

        composeRule.onNodeWithText("Save this sleep plan").performClick()

        assertEquals(
            SleepPlan(wakeMinutes = 420, bedtimeMinutes = 1365, bedtimeDayOffset = -1),
            completed,
        )
    }

    @Test
    fun recommendationSelectionIsExposedToAccessibility() {
        setContent()
        composeRule.onNodeWithText("Create my first sleep plan").performClick()
        composeRule.onNodeWithText("Next").performClick()

        composeRule.onNodeWithText("22:45").performClick()
        composeRule.onNodeWithText("22:45").assertIsSelected()
        composeRule.onNodeWithText("23:15").assertIsNotSelected()

        composeRule.onNodeWithText("23:15").performClick()
        composeRule.onNodeWithText("22:45").assertIsNotSelected()
        composeRule.onNodeWithText("23:15").assertIsSelected()
    }

    @Test
    fun openSourceNoticeIsReachableFromWelcome() {
        setContent()

        composeRule.onNodeWithText("Open-source notice").performClick()

        composeRule
            .onNodeWithText(
                "Warmly is GPLv3 software based on Lux Alarm 2.4.1 and modified in 2026."
            )
            .assertIsDisplayed()
    }

    @Test
    fun directBedtimeRemainsReachableOnCompactLargeTextLayout() {
        setContent(size = DpSize(320.dp, 480.dp), fontScale = 2f)
        reachBedtimeStep(alreadySet = true)

        composeRule.onNodeWithText("Set a different time").performScrollTo().assertIsDisplayed()
    }

    private fun reachBedtimeStep(alreadySet: Boolean = false) {
        if (!alreadySet) setContent()
        composeRule.onNodeWithText("Create my first sleep plan").performClick()
        composeRule.onNodeWithText("Next").performClick()
    }

    private fun setContent(
        size: DpSize? = null,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (size == null) {
                    WarmlyOnboardingScreen(onPlanComplete = {})
                } else {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.ForcedSize(size) then
                            DeviceConfigurationOverride.FontScale(fontScale)
                    ) {
                        WarmlyOnboardingScreen(onPlanComplete = {})
                    }
                }
            }
        }
    }
}
