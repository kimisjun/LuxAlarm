/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Against a real [SettingsManager], so the slider-to-storage round trip is covered, not mocked. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("lux_alarm_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        settingsManager = SettingsManager(context)
    }

    @Test
    fun theInitialSliderPositionComesFromStorage() {
        settingsManager.setRequiredLuxLevel(250f)

        setContent()

        composeRule.onNodeWithText("250 lux").assertIsDisplayed()
    }

    @Test
    fun releasingTheSlider_persistsTheThreshold() {
        setContent()

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }

        assertEquals(300f, settingsManager.getRequiredLuxLevel())
        assertEquals(300f, settingsManager.requiredLuxLevel.value)
        assertEquals(
            300f,
            SettingsManager(context).getRequiredLuxLevel(),
            "A freshly built manager must see the stored value",
        )
    }

    @Test
    fun theDisplayedThresholdFollowsTheSlider() {
        setContent()

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(300f) }

        composeRule.onNodeWithText("300 lux").assertIsDisplayed()
    }

    private fun setContent(currentLightLevel: Float = 0f) {
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) {
                SettingsScreen(
                    onBackClick = {},
                    settingsManager = settingsManager,
                    currentLightLevel = currentLightLevel,
                )
            }
        }
    }
}
