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

class WakePresetTest {
    @Test
    fun defaultRoutineUsesVeryGentlePreset() {
        val routine = WakeRoutine.default(LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY))

        assertEquals(PresetSelection.VERY_GENTLE, routine.presetSelection)
        assertEquals(WakePreset.VERY_GENTLE, routine.presetBase)
    }

    @Test
    fun veryGentleHasExactStageValuesAndNamedVibrationMapping() {
        val routine =
            WakeRoutine.fromPreset(
                preset = WakePreset.VERY_GENTLE,
                goal = LocalTime.of(7, 0),
                repeatDays = setOf(DayOfWeek.MONDAY),
            )

        assertEquals(PresetSelection.VERY_GENTLE, routine.presetSelection)
        assertEquals(WakePreset.VERY_GENTLE, routine.presetBase)
        assertEquals(WakeStage(StageKind.LIGHT, true, 45, 0.02, 0.80, RampCurve.EASE_IN_OUT), routine.stage(StageKind.LIGHT))
        assertEquals(WakeStage(StageKind.MUSIC, true, 30, 0.02, 0.20, RampCurve.EASE_IN_OUT), routine.stage(StageKind.MUSIC))
        assertEquals(
            WakeStage(
                StageKind.VIBRATION,
                true,
                5,
                VibrationStrength.VERY_WEAK.normalizedIntensity,
                VibrationStrength.WEAK.normalizedIntensity,
                RampCurve.EASE_IN_OUT,
            ),
            routine.stage(StageKind.VIBRATION),
        )
        assertEquals(0.10, VibrationStrength.VERY_WEAK.normalizedIntensity)
        assertEquals(0.25, VibrationStrength.WEAK.normalizedIntensity)
        assertEquals(0.50, VibrationStrength.MEDIUM.normalizedIntensity)
        assertEquals(0.75, VibrationStrength.STRONG.normalizedIntensity)
    }

    @Test
    fun balancedAndReliableHaveExactStageValues() {
        val balanced = routine(WakePreset.BALANCED)
        assertEquals(WakeStage(StageKind.LIGHT, true, 30, 0.02, 0.90, RampCurve.EASE_IN_OUT), balanced.stage(StageKind.LIGHT))
        assertEquals(WakeStage(StageKind.MUSIC, true, 20, 0.03, 0.30, RampCurve.EASE_IN_OUT), balanced.stage(StageKind.MUSIC))
        assertEquals(WakeStage(StageKind.VIBRATION, true, 5, 0.25, 0.50, RampCurve.EASE_IN_OUT), balanced.stage(StageKind.VIBRATION))

        val reliable = routine(WakePreset.RELIABLE)
        assertEquals(WakeStage(StageKind.LIGHT, true, 20, 0.05, 1.00, RampCurve.EASE_IN_OUT), reliable.stage(StageKind.LIGHT))
        assertEquals(WakeStage(StageKind.MUSIC, true, 15, 0.05, 0.40, RampCurve.EASE_IN_OUT), reliable.stage(StageKind.MUSIC))
        assertEquals(WakeStage(StageKind.VIBRATION, true, 7, 0.25, 0.75, RampCurve.EASE_IN_OUT), reliable.stage(StageKind.VIBRATION))
    }

    @Test
    fun editingAStageMarksCustomAndResetRestoresTheBasePreset() {
        val original = routine(WakePreset.BALANCED)
        val edited = original.withStage(original.stage(StageKind.MUSIC).copy(goalIntensity = 0.35))

        assertEquals(PresetSelection.CUSTOM, edited.presetSelection)
        assertEquals(WakePreset.BALANCED, edited.presetBase)
        assertEquals(0.35, edited.stage(StageKind.MUSIC).goalIntensity)
        assertEquals(original.stages, edited.resetToPresetBase().stages)
        assertEquals(PresetSelection.BALANCED, edited.resetToPresetBase().presetSelection)
    }

    @Test
    fun enabledRoutineCannotDisableItsLastStage() {
        val onlyLight =
            routine(WakePreset.BALANCED)
                .withStageEnabled(StageKind.MUSIC, false)
                .withStageEnabled(StageKind.VIBRATION, false)

        assertFailsWith<IllegalArgumentException> {
            onlyLight.withStageEnabled(StageKind.LIGHT, false)
        }
    }

    @Test
    fun disabledRoutineCanHaveNoStagesButCannotThenBeEnabled() {
        val noStages =
            routine(WakePreset.BALANCED)
                .withEnabled(false)
                .withStageEnabled(StageKind.LIGHT, false)
                .withStageEnabled(StageKind.MUSIC, false)
                .withStageEnabled(StageKind.VIBRATION, false)

        assertEquals(0, noStages.stages.count { it.enabled })
        assertFailsWith<IllegalArgumentException> { noStages.withEnabled(true) }
    }

    @Test
    fun stageRejectsInvalidOffsetAndIntensityBoundsOrDescendingRamp() {
        assertFailsWith<IllegalArgumentException> {
            WakeStage(StageKind.LIGHT, true, -1, 0.0, 1.0, RampCurve.LINEAR)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeStage(StageKind.LIGHT, true, 61, 0.0, 1.0, RampCurve.LINEAR)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeStage(StageKind.MUSIC, true, 5, -0.01, 1.0, RampCurve.LINEAR)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeStage(StageKind.MUSIC, true, 5, 0.0, 1.01, RampCurve.LINEAR)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeStage(StageKind.VIBRATION, true, 5, 0.5, 0.25, RampCurve.LINEAR)
        }
    }

    private fun routine(preset: WakePreset): WakeRoutine =
        WakeRoutine.fromPreset(
            preset = preset,
            goal = LocalTime.of(7, 0),
            repeatDays = setOf(DayOfWeek.MONDAY),
        )
}
