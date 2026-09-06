/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarmlyAppEntryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun use24HourTime() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    @Test
    fun emptyStoreStartsWithTheWarmlySoloWelcomeExperience() {
        setContent(FakeSleepPlanStore())

        composeRule.onNodeWithText("Wake gently. Start your day warmly.").assertIsDisplayed()
        composeRule.onNodeWithText("Create my first sleep plan").assertIsDisplayed()
    }

    @Test
    fun savedPlanStartsOnItsHomeSummary() {
        setContent(
            FakeSleepPlanStore(
                SleepPlan(
                    wakeMinutes = 7 * 60 + 15,
                    bedtimeMinutes = 23 * 60,
                    bedtimeDayOffset = -1,
                )
            )
        )

        composeRule.onNodeWithText("Tomorrow’s wake time").assertIsDisplayed()
        composeRule.onNodeWithText("07:15").assertIsDisplayed()
        composeRule.onNodeWithText("Bedtime · 23:00").assertIsDisplayed()
        composeRule.onNodeWithText("Create my first sleep plan").assertDoesNotExist()
    }

    @Test
    fun completingOnboardingSavesAndShowsHome() {
        val store = FakeSleepPlanStore()
        setContent(store)
        composeRule.onNodeWithText("Create my first sleep plan").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("22:45").performClick()

        composeRule.onNodeWithText("Save this sleep plan").performClick()

        composeRule.onNodeWithText("Tomorrow’s wake time").assertIsDisplayed()
        composeRule.onNodeWithText("07:00").assertIsDisplayed()
        composeRule.onNodeWithText("Bedtime · 22:45").assertIsDisplayed()
    }

    private fun setContent(store: SleepPlanStore) {
        composeRule.setContent { LuxAlarmTheme(dynamicColor = false) { LuxAlarmApp(store) } }
    }
}

private class FakeSleepPlanStore(initial: SleepPlan? = null) : SleepPlanStore {
    private var plan = initial

    override suspend fun load(): SleepPlan? = plan

    override suspend fun save(plan: SleepPlan) {
        this.plan = plan
    }
}
