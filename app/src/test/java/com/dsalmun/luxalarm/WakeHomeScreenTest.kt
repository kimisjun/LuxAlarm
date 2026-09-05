/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.intl.LocaleList as ComposeLocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import com.dsalmun.luxalarm.wake.StageKind
import com.dsalmun.luxalarm.wake.WakePreset
import com.dsalmun.luxalarm.wake.WakeRoutine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeHomeScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enabledRoutineShowsTheCalculatedReadOnlySummaryInKorean() {
        setContent(enabledState(), locale = Locale.KOREAN)

        listOf(
                "9월 7일 월요일",
                "기상 목표 · 오전 7:00",
                "기상 시작 · 오전 6:15",
                "준비 시간 · 45분",
                "첫 단계 · 빛",
                "프리셋 · 아주 부드럽게",
                "음악 · 새벽의 피아노",
                "루틴 · 켜짐",
                "다음 기상 · 9월 7일 월요일 오전 7:00",
            )
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun disabledRoutinePresentsOnlyClearlyLabeledSavedSettings() {
        setContent(enabledState().copy(enabled = false), locale = Locale.ENGLISH)

        listOf(
                "No next wake is scheduled",
                "Saved settings (not scheduled) · Monday, September 7",
                "Saved goal · 7:00 AM",
                "Saved start · 6:15 AM",
                "Saved preparation · 45 minutes",
                "Saved first stage · Light",
                "Saved preset · Very gentle",
                "Saved music · 새벽의 피아노",
                "Routine · Off",
            )
            .forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("Monday, September 7").assertDoesNotExist()
        composeRule.onNodeWithText("Wake goal · 7:00 AM").assertDoesNotExist()
        composeRule.onNodeWithText("Wake start · 6:15 AM").assertDoesNotExist()
        composeRule.onNodeWithText("First stage · Light").assertDoesNotExist()
        composeRule.onNodeWithText("Preset · Very gentle").assertDoesNotExist()
        composeRule.onNodeWithText("Music · 새벽의 피아노").assertDoesNotExist()
        composeRule.onNodeWithText("Next wake · Monday, September 7 7:00 AM").assertDoesNotExist()
    }

    @Test
    fun disabledRoutineUsesSavedPresetAndMusicLabelsInKorean() {
        setContent(enabledState().copy(enabled = false), locale = Locale.KOREAN)

        composeRule.onNodeWithText("저장된 프리셋 · 아주 부드럽게").assertIsDisplayed()
        composeRule.onNodeWithText("저장된 음악 · 새벽의 피아노").assertIsDisplayed()
        composeRule.onNodeWithText("프리셋 · 아주 부드럽게").assertDoesNotExist()
        composeRule.onNodeWithText("음악 · 새벽의 피아노").assertDoesNotExist()
    }

    @Test
    fun topTextMeetsNormalTextContrastAcrossEveryBackgroundColor() {
        val worstCase = WakeHomeTopTextBackgroundColors.minOf { background ->
            val lighter = maxOf(WakeHomeTopTextColor.luminance(), background.luminance())
            val darker = minOf(WakeHomeTopTextColor.luminance(), background.luminance())
            (lighter + 0.05f) / (darker + 0.05f)
        }

        assertTrue(worstCase >= 4.5f, "Top text worst-case contrast was $worstCase:1")
    }

    @Test
    fun topTextAndFinalSummaryRemainReachableAtDoubleFontScale() {
        setContent(enabledState(), locale = Locale.KOREAN, fontScale = 2f)

        composeRule.onNodeWithTag("wake-home-title").assertIsDisplayed()
        composeRule.onNodeWithTag("wake-home-date").assertIsDisplayed()
        composeRule.onNodeWithTag("wake-home-next").assertIsDisplayed()
        composeRule.onNodeWithTag("wake-home-enabled").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyMeaningfulTextNodeHasAUniqueCompleteTraversalPosition() {
        setContent(enabledState(), locale = Locale.ENGLISH, fontScale = 2f)

        val textNodes =
            composeRule
                .onAllNodes(hasAnyAncestor(hasTestTag("wake-home-root")), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { SemanticsProperties.Text in it.config }
        val indices = textNodes.map { it.config[SemanticsProperties.TraversalIndex] }
        val texts = textNodes.map {
            it.config[SemanticsProperties.Text].joinToString { value -> value.text }
        }

        assertEquals(10, textNodes.size)
        assertEquals((0..9).map(Int::toFloat), indices.sorted())
        assertEquals(indices.size, indices.toSet().size)
        assertEquals(
            listOf(
                "Quiet morning",
                "Monday, September 7",
                "Next wake · Monday, September 7 7:00 AM",
                "Wake goal · 7:00 AM",
                "Wake start · 6:15 AM",
                "Preparation · 45 minutes",
                "First stage · Light",
                "Preset · Very gentle",
                "Music · 새벽의 피아노",
                "Routine · On",
            ),
            textNodes
                .sortedBy { it.config[SemanticsProperties.TraversalIndex] }
                .map {
                    it.config[SemanticsProperties.Text].joinToString { value -> value.text }
                },
        )
        assertEquals(texts.size, texts.toSet().size)
        composeRule.onNodeWithTag("wake-home-enabled").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun localeControlsAllLabelsDatesAndTimes() {
        setContent(enabledState(), locale = Locale.ENGLISH)

        composeRule.onNodeWithText("Monday, September 7").assertIsDisplayed()
        composeRule.onNodeWithText("Wake goal · 7:00 AM").assertIsDisplayed()
        composeRule.onNodeWithText("월요일", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("오전", substring = true).assertDoesNotExist()
    }

    @Test
    fun midnightUsesLocaleAwareTwelveHourBoundary() {
        setContent(enabledState(LocalTime.MIDNIGHT), locale = Locale.ENGLISH)

        composeRule.onNodeWithText("Wake goal · 12:00 AM").assertIsDisplayed()
    }

    @Test
    fun noonUsesLocaleAwareTwelveHourBoundary() {
        setContent(enabledState(LocalTime.NOON), locale = Locale.ENGLISH)

        composeRule.onNodeWithText("Wake goal · 12:00 PM").assertIsDisplayed()
    }

    @Test
    fun disabledRoutineWithEveryStageOffHasZeroPreparationAndNoFirstStage() {
        val routine =
            WakeRoutine.fromPreset(
                    preset = WakePreset.VERY_GENTLE,
                    goal = LocalTime.of(7, 0),
                    repeatDays = setOf(DayOfWeek.MONDAY),
                    enabled = false,
                )
                .withStageEnabled(StageKind.LIGHT, false)
                .withStageEnabled(StageKind.MUSIC, false)
                .withStageEnabled(StageKind.VIBRATION, false)
        val state = WakeHomeUiState.from(routine, LocalDate.of(2026, 9, 7), "새벽의 피아노")

        assertEquals(0, state.preparationMinutes)
        assertEquals(null, state.firstEnabledStage)
        setContent(state, locale = Locale.ENGLISH)
        composeRule.onNodeWithText("Saved preparation · 0 minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Saved first stage · None").assertIsDisplayed()
    }

    @Test
    fun equalOffsetFirstStageTieUsesStableStageKindPriority() {
        var routine =
            WakeRoutine.fromPreset(
                preset = WakePreset.VERY_GENTLE,
                goal = LocalTime.of(7, 0),
                repeatDays = setOf(DayOfWeek.MONDAY),
            )
        routine =
            routine
                .withStage(
                    routine
                        .stage(StageKind.LIGHT)
                        .copy(startOffsetBeforeGoalMinutes = 45, enabled = true)
                )
                .withStage(
                    routine
                        .stage(StageKind.MUSIC)
                        .copy(startOffsetBeforeGoalMinutes = 45, enabled = true)
                )

        val state = WakeHomeUiState.from(routine, LocalDate.of(2026, 9, 7), "새벽의 피아노")

        assertEquals(StageKind.LIGHT, state.firstEnabledStage)
    }

    private fun setContent(
        state: WakeHomeUiState,
        locale: Locale,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 600.dp)) then
                        DeviceConfigurationOverride.FontScale(fontScale) then
                        DeviceConfigurationOverride.Locales(
                            ComposeLocaleList(ComposeLocale(locale.toLanguageTag()))
                        )
                ) {
                    WakeHomeScreen(state)
                }
            }
        }
    }

    private fun enabledState(goal: LocalTime = LocalTime.of(7, 0)): WakeHomeUiState =
        WakeHomeUiState.from(
            routine =
                WakeRoutine.fromPreset(
                    preset = WakePreset.VERY_GENTLE,
                    goal = goal,
                    repeatDays = setOf(DayOfWeek.MONDAY),
                ),
            goalDate = LocalDate.of(2026, 9, 7),
            selectedSong = "새벽의 피아노",
        )
}
