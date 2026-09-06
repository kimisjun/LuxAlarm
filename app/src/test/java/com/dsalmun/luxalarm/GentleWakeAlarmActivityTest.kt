/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.testing.AppContainerTestRule
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class GentleWakeAlarmActivityTest {
    @get:Rule(order = 0) val appContainer = AppContainerTestRule()

    @get:Rule(order = 1)
    val composeRule =
        AndroidComposeTestRule(
            activityRule =
                ActivityScenarioRule<AlarmActivity>(
                    Intent(
                            ApplicationProvider.getApplicationContext<Context>(),
                            AlarmActivity::class.java,
                        )
                        .putExtra("alarm_id", 7)
                        .putExtra("gentle_wake", true)
                        .putExtra("ramp_minutes", 1)
                ),
            activityProvider = { rule ->
                var activity: AlarmActivity? = null
                rule.scenario.onActivity { activity = it }
                checkNotNull(activity)
            },
        )

    @Test
    fun defaultConfirmModeShowsTheGentleWakeScreenInsteadOfTheLuxGate() {
        composeRule.onNodeWithText("부드럽게 깨어날 시간이에요").assertExists()
        composeRule
            .onNodeWithText("일어났어요")
            .assertIsEnabled()
            .assertWidthIsAtLeast(240.dp)
            .assertHeightIsAtLeast(64.dp)
        composeRule.onAllNodesWithText("Need More Light").assertCountEquals(0)
        composeRule.onAllNodesWithText("미리보기 진행도").assertCountEquals(0)
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assertCountEquals(0)
    }

    @Test
    fun awakeConfirmationStopsTheAlarmImmediately() {
        composeRule.onNodeWithText("일어났어요").performClick()

        val activity = composeRule.activity
        val stopIntent = assertNotNull(shadowOf(activity).nextStartedService)
        assertEquals(AlarmService::class.java.name, stopIntent.component?.className)
        assertEquals(AlarmService.ACTION_STOP_ALARM, stopIntent.action)
        assertEquals(7, stopIntent.getIntExtra("alarm_id", -1))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun sunriseBrightnessAdvancesFromStartToMaximumOverTheRampDuration() {
        val initialBrightness = composeRule.activity.window.attributes.screenBrightness

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)

        val halfwayBrightness = composeRule.activity.window.attributes.screenBrightness
        assertTrue(halfwayBrightness > initialBrightness)
        assertTrue(halfwayBrightness < 1f)

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)

        assertEquals(1f, composeRule.activity.window.attributes.screenBrightness)
    }
}
