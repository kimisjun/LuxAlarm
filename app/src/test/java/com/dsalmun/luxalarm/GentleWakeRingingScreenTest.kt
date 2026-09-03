/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GentleWakeRingingScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun optionalLuxKeepsTheSunriseScreenAndLocksOnlyDismissal() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakeRingingScreen(
                    progress = 0.25f,
                    currentLightLevel = 10f,
                    requiredLightLevel = 50f,
                    requiresLux = true,
                    onAwake = {},
                )
            }
        }

        composeRule.onNodeWithText("부드럽게 깨어날 시간이에요").assertExists()
        composeRule.onNodeWithText("밝기 미션 · 10 / 50 lux").assertExists()
        composeRule.onNodeWithText("더 밝은 곳으로 이동해 주세요").assertExists()
        composeRule.onNodeWithText("밝은 곳으로 이동 중").assertIsNotEnabled()
    }

    @Test
    fun optionalLuxUnlocksTheSameSunriseScreenWhenBrightEnough() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakeRingingScreen(
                    progress = 0.75f,
                    currentLightLevel = 80f,
                    requiredLightLevel = 50f,
                    requiresLux = true,
                    onAwake = {},
                )
            }
        }

        composeRule.onNodeWithText("부드럽게 깨어날 시간이에요").assertExists()
        composeRule.onNodeWithText("충분히 밝아요").assertExists()
        composeRule.onNodeWithText("일어났어요").assertIsEnabled()
    }
}
