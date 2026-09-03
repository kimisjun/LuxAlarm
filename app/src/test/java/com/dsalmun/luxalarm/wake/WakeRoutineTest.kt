/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class WakeRoutineTest {
    @Test
    fun wakeStartUsesEarliestEnabledStageAndCrossesMidnight() {
        val routine = routine(WakePreset.VERY_GENTLE, LocalTime.of(0, 15))

        assertEquals(45, routine.preparationMinutes)
        assertEquals(LocalTime.of(23, 30), routine.wakeStart)
    }

    @Test
    fun disabledOffsetsDoNotAffectPreparationOrWakeStart() {
        val routine =
            routine(WakePreset.VERY_GENTLE)
                .withStageEnabled(StageKind.LIGHT, false)

        assertEquals(30, routine.preparationMinutes)
        assertEquals(LocalTime.of(6, 30), routine.wakeStart)
    }

    @Test
    fun goalEditKeepsOffsetsAndAllowsWakeStartAtGoal() {
        val zero = routine(WakePreset.BALANCED).withPreparationMinutes(0)
        val moved = zero.withGoal(LocalTime.of(8, 15))

        assertEquals(zero.stages, moved.stages)
        assertEquals(LocalTime.of(8, 15), moved.wakeStart)
        assertEquals(moved.goal, moved.wakeStart)
    }

    @Test
    fun preparationRescalesEnabledOffsetsWithHalfUpIntegerRounding() {
        val routine =
            routine(WakePreset.BALANCED)
                .withStage(
                    WakeStage(StageKind.LIGHT, true, 30, 0.02, 0.90, RampCurve.EASE_IN_OUT),
                )
                .withStage(
                    WakeStage(StageKind.MUSIC, true, 15, 0.03, 0.30, RampCurve.EASE_IN_OUT),
                )
                .withStage(
                    WakeStage(StageKind.VIBRATION, true, 15, 0.25, 0.50, RampCurve.EASE_IN_OUT),
                )
                .withPreparationMinutes(5)

        assertEquals(listOf(5, 3, 3), routine.stages.map { it.startOffsetBeforeGoalMinutes })
        assertEquals(PresetSelection.CUSTOM, routine.presetSelection)
        assertEquals(5, routine.preparationMinutes)
    }

    @Test
    fun zeroOldPreparationUsesPresetBaseRatios() {
        val routine =
            routine(WakePreset.BALANCED)
                .withPreparationMinutes(0)
                .withPreparationMinutes(10)

        assertEquals(10, routine.stage(StageKind.LIGHT).startOffsetBeforeGoalMinutes)
        assertEquals(7, routine.stage(StageKind.MUSIC).startOffsetBeforeGoalMinutes)
        assertEquals(2, routine.stage(StageKind.VIBRATION).startOffsetBeforeGoalMinutes)
    }

    @Test
    fun zeroOldPreparationNormalizesPresetRatiosAcrossEnabledStages() {
        val routine =
            routine(WakePreset.VERY_GENTLE)
                .withStageEnabled(StageKind.LIGHT, false)
                .withPreparationMinutes(0)
                .withPreparationMinutes(10)

        assertEquals(45, routine.stage(StageKind.LIGHT).startOffsetBeforeGoalMinutes)
        assertEquals(10, routine.stage(StageKind.MUSIC).startOffsetBeforeGoalMinutes)
        assertEquals(2, routine.stage(StageKind.VIBRATION).startOffsetBeforeGoalMinutes)
        assertEquals(10, routine.preparationMinutes)
    }

    @Test
    fun preparationZeroMovesEnabledStagesToGoalButLeavesInactiveOffsetsUnchanged() {
        val routine =
            routine(WakePreset.VERY_GENTLE)
                .withStageEnabled(StageKind.MUSIC, false)
                .withPreparationMinutes(0)

        assertEquals(0, routine.stage(StageKind.LIGHT).startOffsetBeforeGoalMinutes)
        assertEquals(30, routine.stage(StageKind.MUSIC).startOffsetBeforeGoalMinutes)
        assertEquals(0, routine.stage(StageKind.VIBRATION).startOffsetBeforeGoalMinutes)
    }

    @Test
    fun directStageOffsetEditMarksCustomAndRecomputesWakeStart() {
        val routine =
            routine(WakePreset.BALANCED)
                .withStage(
                    WakeStage(StageKind.LIGHT, true, 10, 0.02, 0.90, RampCurve.EASE_IN_OUT),
                )

        assertEquals(PresetSelection.CUSTOM, routine.presetSelection)
        assertEquals(20, routine.preparationMinutes)
        assertEquals(LocalTime.of(6, 40), routine.wakeStart)
    }

    @Test
    fun preparationAndDirectOffsetEditsValidateRange() {
        val routine = routine(WakePreset.RELIABLE)

        assertFailsWith<IllegalArgumentException> { routine.withPreparationMinutes(-1) }
        assertFailsWith<IllegalArgumentException> { routine.withPreparationMinutes(61) }
        assertFailsWith<IllegalArgumentException> {
            routine.withStage(routine.stage(StageKind.LIGHT).copy(startOffsetBeforeGoalMinutes = 61))
        }
    }

    @Test
    fun exposedStagesCannotBeMutatedThroughJvmMutableListCast() {
        val routine = routine(WakePreset.VERY_GENTLE)
        @Suppress("UNCHECKED_CAST")
        val mutableStages = routine.stages as MutableList<WakeStage>

        assertFailsWith<UnsupportedOperationException> {
            mutableStages[0] = routine.stage(StageKind.MUSIC)
        }
        assertEquals(StageKind.entries.toSet(), routine.stages.map { it.kind }.toSet())
    }

    @Test
    fun exposedRepeatDaysCannotBeMutatedThroughJvmMutableSetCast() {
        val routine = routine(WakePreset.VERY_GENTLE)
        @Suppress("UNCHECKED_CAST")
        val mutableDays = routine.repeatDays as MutableSet<DayOfWeek>

        assertFailsWith<UnsupportedOperationException> { mutableDays.clear() }
        assertEquals(setOf(DayOfWeek.MONDAY), routine.repeatDays)
    }

    private fun routine(
        preset: WakePreset,
        goal: LocalTime = LocalTime.of(7, 0),
    ): WakeRoutine =
        WakeRoutine.fromPreset(preset, goal, setOf(DayOfWeek.MONDAY))
}
