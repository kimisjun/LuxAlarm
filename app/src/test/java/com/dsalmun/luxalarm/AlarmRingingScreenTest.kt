/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.pinLocalHourTo
import com.dsalmun.luxalarm.testing.restoreSystemTimeZone
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The alarm cannot be turned off until the room is bright enough, so the boundary is the point. */
@RunWith(AndroidJUnit4::class)
class AlarmRingingScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun restoreTimeZone() {
        restoreSystemTimeZone()
    }

    @Test
    fun belowTheThreshold_theStopButtonIsDisabled() {
        setContent(currentLightLevel = 10f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Need More Light").assertIsNotEnabled()
        composeRule.onNodeWithText("Go to a brighter area to turn off alarm").assertExists()
    }

    @Test
    fun atExactlyTheThreshold_theStopButtonIsEnabled() {
        setContent(currentLightLevel = 50f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Turn Off Alarm").assertIsEnabled()
    }

    @Test
    fun aboveTheThreshold_theStopButtonIsEnabled() {
        setContent(currentLightLevel = 500f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Turn Off Alarm").assertIsEnabled()
    }

    @Test
    fun theEnabledButton_stopsTheAlarm() {
        var stopped = 0
        setContent(currentLightLevel = 500f, requiredLightLevel = 50f, onStopAlarm = { stopped++ })

        composeRule.onNodeWithText("Turn Off Alarm").performClick()

        assertEquals(1, stopped)
    }

    @Test
    fun theDisabledButton_doesNotStopTheAlarm() {
        var stopped = 0
        setContent(currentLightLevel = 10f, requiredLightLevel = 50f, onStopAlarm = { stopped++ })

        composeRule.onNodeWithText("Need More Light").performClick()

        assertEquals(0, stopped, "A dark room must not be able to dismiss the alarm")
    }

    @Test
    fun beforeNoon_greetsWithGoodMorning() {
        pinLocalHourTo(5)
        setContent(currentLightLevel = 0f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Good Morning").assertExists()
    }

    @Test
    fun inTheAfternoon_greetsWithGoodAfternoon() {
        pinLocalHourTo(12)
        setContent(currentLightLevel = 0f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Good Afternoon").assertExists()
    }

    @Test
    fun inTheEvening_greetsWithGoodEvening() {
        pinLocalHourTo(18)
        setContent(currentLightLevel = 0f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Good Evening").assertExists()
    }

    @Test
    fun lateAtNight_greetsWithTimeToWakeUp() {
        pinLocalHourTo(22)
        setContent(currentLightLevel = 0f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Time to Wake Up").assertExists()
    }

    @Test
    fun inTheSmallHours_greetsWithTimeToWakeUp() {
        pinLocalHourTo(4)
        setContent(currentLightLevel = 0f, requiredLightLevel = 50f)

        composeRule.onNodeWithText("Time to Wake Up").assertExists()
    }

    private fun setContent(
        currentLightLevel: Float,
        requiredLightLevel: Float,
        onStopAlarm: () -> Unit = {},
    ) {
        composeRule.setContent {
            // dynamicColor defaults to true and would read the host's system palette.
            LuxAlarmTheme(dynamicColor = false) {
                AlarmRingingScreen(
                    currentLightLevel = currentLightLevel,
                    requiredLightLevel = requiredLightLevel,
                    onStopAlarm = onStopAlarm,
                )
            }
        }
    }
}
