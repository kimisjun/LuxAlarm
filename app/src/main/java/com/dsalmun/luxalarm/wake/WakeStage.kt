/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

enum class StageKind { LIGHT, MUSIC, VIBRATION }

enum class RampCurve { LINEAR, EASE_IN_OUT }

/**
 * Stable semantic vibration levels for routine modeling and UI display.
 * Values are normalized model intensities, not final device-safe hardware amplitudes.
 */
enum class VibrationStrength(val normalizedIntensity: Double) {
    VERY_WEAK(0.10),
    WEAK(0.25),
    MEDIUM(0.50),
    STRONG(0.75),
}

data class WakeStage(
    val kind: StageKind,
    val enabled: Boolean,
    val startOffsetBeforeGoalMinutes: Int,
    val startIntensity: Double,
    val goalIntensity: Double,
    val curve: RampCurve,
) {
    init {
        require(startOffsetBeforeGoalMinutes in 0..60) { "Stage offset must be between 0 and 60 minutes" }
        require(startIntensity in 0.0..1.0) { "Start intensity must be between 0 and 1" }
        require(goalIntensity in 0.0..1.0) { "Goal intensity must be between 0 and 1" }
        require(startIntensity <= goalIntensity) { "Start intensity must not exceed goal intensity" }
    }
}
