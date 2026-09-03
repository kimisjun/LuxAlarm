/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

enum class PresetSelection { VERY_GENTLE, BALANCED, RELIABLE, CUSTOM }

enum class WakePreset {
    VERY_GENTLE,
    BALANCED,
    RELIABLE,
    ;

    internal fun stages(): List<WakeStage> =
        when (this) {
            VERY_GENTLE ->
                listOf(
                    stage(StageKind.LIGHT, 45, 0.02, 0.80),
                    stage(StageKind.MUSIC, 30, 0.02, 0.20),
                    stage(StageKind.VIBRATION, 5, VibrationStrength.VERY_WEAK.normalizedIntensity, VibrationStrength.WEAK.normalizedIntensity),
                )
            BALANCED ->
                listOf(
                    stage(StageKind.LIGHT, 30, 0.02, 0.90),
                    stage(StageKind.MUSIC, 20, 0.03, 0.30),
                    stage(StageKind.VIBRATION, 5, VibrationStrength.WEAK.normalizedIntensity, VibrationStrength.MEDIUM.normalizedIntensity),
                )
            RELIABLE ->
                listOf(
                    stage(StageKind.LIGHT, 20, 0.05, 1.00),
                    stage(StageKind.MUSIC, 15, 0.05, 0.40),
                    stage(StageKind.VIBRATION, 7, VibrationStrength.WEAK.normalizedIntensity, VibrationStrength.STRONG.normalizedIntensity),
                )
        }

    private fun stage(kind: StageKind, offset: Int, start: Double, goal: Double) =
        WakeStage(kind, true, offset, start, goal, RampCurve.EASE_IN_OUT)
}
