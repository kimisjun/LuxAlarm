/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import com.dsalmun.luxalarm.wake.PresetSelection
import com.dsalmun.luxalarm.wake.StageKind
import com.dsalmun.luxalarm.wake.WakePreset
import com.dsalmun.luxalarm.wake.WakeRoutine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val NightNavy = Color(0xFF11162F)
private val DawnViolet = Color(0xFF55406F)
private val DawnApricot = Color(0xFFF2A876)
private val MorningCream = Color(0xFFFFF7E8)
private val QuietSunriseGradientColors = listOf(NightNavy, DawnViolet, DawnApricot, MorningCream)
internal val WakeHomeTopTextColor = MorningCream
internal val WakeHomeTopTextBackgroundColors = listOf(NightNavy)

/** Immutable, unformatted projection for the read-only, single-routine home shell. */
data class WakeHomeUiState(
    val enabled: Boolean,
    val goalDate: LocalDate,
    val goalTime: LocalTime,
    val wakeStartTime: LocalTime,
    val preparationMinutes: Int,
    val firstEnabledStage: StageKind?,
    val preset: PresetSelection,
    val selectedSong: String,
) {
    companion object {
        fun from(
            routine: WakeRoutine,
            goalDate: LocalDate,
            selectedSong: String,
        ): WakeHomeUiState {
            val firstStage =
                routine.stages
                    .asSequence()
                    .filter { it.enabled }
                    .sortedWith(
                        compareByDescending<com.dsalmun.luxalarm.wake.WakeStage> {
                                it.startOffsetBeforeGoalMinutes
                            }
                            .thenBy { it.kind.ordinal }
                    )
                    .firstOrNull()
                    ?.kind
            return WakeHomeUiState(
                enabled = routine.enabled,
                goalDate = goalDate,
                goalTime = routine.goal,
                wakeStartTime = routine.wakeStart,
                preparationMinutes = routine.preparationMinutes,
                firstEnabledStage = firstStage,
                preset = routine.presetSelection,
                selectedSong = selectedSong,
            )
        }
    }
}

/** Task 9.1 shell only: rendering is intentionally independent from persistence and scheduling. */
@Composable
fun WakeHomeScreen(state: WakeHomeUiState, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val date =
        state.goalDate.format(
            DateTimeFormatter.ofPattern(stringResource(R.string.wake_home_date_pattern), locale)
        )
    val timeFormatter =
        DateTimeFormatter.ofPattern(stringResource(R.string.wake_home_time_pattern), locale)
    val goalTime = state.goalTime.format(timeFormatter)
    val wakeStartTime = state.wakeStartTime.format(timeFormatter)
    val stage = stringResource(state.firstEnabledStage.labelResource())
    val preset = stringResource(state.preset.labelResource())

    Box(
        modifier =
            modifier.fillMaxSize().background(Brush.verticalGradient(QuietSunriseGradientColors))
    ) {
        QuietSunriseArtwork()
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .semantics { isTraversalGroup = true }
                    .testTag("wake-home-root"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = WakeHomeTopTextBackgroundColors.single(),
                contentColor = WakeHomeTopTextColor,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wake_home_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.orderedTag("wake-home-title", 0f, isHeading = true),
                    )
                    Text(
                        text =
                            if (state.enabled) {
                                date
                            } else {
                                stringResource(R.string.wake_home_saved_date, date)
                            },
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.orderedTag("wake-home-date", 1f),
                    )
                    Text(
                        text =
                            if (state.enabled) {
                                stringResource(R.string.wake_home_next, date, goalTime)
                            } else {
                                stringResource(R.string.wake_home_no_next)
                            },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.orderedTag("wake-home-next", 2f),
                    )
                }
            }
            Spacer(Modifier.height(96.dp))
            Surface(
                color = MorningCream.copy(alpha = 0.96f),
                contentColor = NightNavy,
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_goal
                                else R.string.wake_home_saved_goal,
                                goalTime,
                            ),
                        tag = "wake-home-goal",
                        order = 3f,
                    )
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_start
                                else R.string.wake_home_saved_start,
                                wakeStartTime,
                            ),
                        tag = "wake-home-start",
                        order = 4f,
                    )
                    SummaryLine(
                        text =
                            pluralStringResource(
                                if (state.enabled) R.plurals.wake_home_preparation
                                else R.plurals.wake_home_saved_preparation,
                                state.preparationMinutes,
                                state.preparationMinutes,
                            ),
                        tag = "wake-home-preparation",
                        order = 5f,
                    )
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_first_stage
                                else R.string.wake_home_saved_first_stage,
                                stage,
                            ),
                        tag = "wake-home-first-stage",
                        order = 6f,
                    )
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_preset
                                else R.string.wake_home_saved_preset,
                                preset,
                            ),
                        tag = "wake-home-preset",
                        order = 7f,
                    )
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_music
                                else R.string.wake_home_saved_music,
                                state.selectedSong,
                            ),
                        tag = "wake-home-song",
                        order = 8f,
                    )
                    SummaryLine(
                        text =
                            stringResource(
                                if (state.enabled) R.string.wake_home_routine_on
                                else R.string.wake_home_routine_off
                            ),
                        tag = "wake-home-enabled",
                        order = 9f,
                        emphasized = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(
    text: String,
    tag: String,
    order: Float,
    emphasized: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.orderedTag(tag, order),
    )
}

@Composable
private fun QuietSunriseArtwork() {
    Canvas(Modifier.fillMaxWidth().height(330.dp).clearAndSetSemantics {}) {
        val horizonY = size.height * 0.72f
        drawCircle(
            color = DawnApricot,
            radius = size.minDimension * 0.16f,
            center = Offset(size.width * 0.72f, horizonY),
        )
        listOf(0.55f, 0.74f, 0.93f).forEachIndexed { index, radiusScale ->
            drawArc(
                color = MorningCream.copy(alpha = 0.18f - index * 0.035f),
                startAngle = 190f,
                sweepAngle = 160f,
                useCenter = false,
                topLeft =
                    Offset(size.width * (0.72f - radiusScale), horizonY - size.width * radiusScale),
                size =
                    androidx.compose.ui.geometry.Size(
                        size.width * radiusScale * 2,
                        size.width * radiusScale * 2,
                    ),
                style = Stroke(width = 18.dp.toPx()),
            )
        }
        drawLine(
            color = MorningCream.copy(alpha = 0.72f),
            start = Offset(0f, horizonY),
            end = Offset(size.width, horizonY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun Modifier.orderedTag(tag: String, order: Float, isHeading: Boolean = false): Modifier =
    semantics {
            traversalIndex = order
            if (isHeading) heading()
        }
        .testTag(tag)

@StringRes
private fun StageKind?.labelResource(): Int =
    when (this) {
        StageKind.LIGHT -> R.string.wake_home_stage_light
        StageKind.MUSIC -> R.string.wake_home_stage_music
        StageKind.VIBRATION -> R.string.wake_home_stage_vibration
        null -> R.string.wake_home_stage_none
    }

@StringRes
private fun PresetSelection.labelResource(): Int =
    when (this) {
        PresetSelection.VERY_GENTLE -> R.string.wake_home_preset_very_gentle
        PresetSelection.BALANCED -> R.string.wake_home_preset_balanced
        PresetSelection.RELIABLE -> R.string.wake_home_preset_reliable
        PresetSelection.CUSTOM -> R.string.wake_home_preset_custom
    }

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun WakeHomeScreenPreview() {
    LuxAlarmTheme(dynamicColor = false) {
        WakeHomeScreen(
            WakeHomeUiState.from(
                routine =
                    WakeRoutine.fromPreset(
                        preset = WakePreset.VERY_GENTLE,
                        goal = LocalTime.of(7, 0),
                        repeatDays = setOf(DayOfWeek.MONDAY),
                    ),
                goalDate = LocalDate.of(2026, 9, 7),
                selectedSong = stringResource(R.string.wake_home_preview_song),
            )
        )
    }
}
