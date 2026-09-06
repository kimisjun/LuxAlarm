/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

data class WakeRampFrame(
    val clampedProgress: Float,
    val easedProgress: Float,
    val screenBrightness: Float,
    val audioVolume: Float,
    val hapticIntensity: Float,
    val sunriseRgb: List<Float>,
)

/** Platform-independent smoothstep ramp shared by preview and alarm execution. */
object WakeRamp {
    const val DEFAULT_RAMP_MINUTES = 20
    const val DEFAULT_START_VOLUME = 0.05f
    const val DEFAULT_MAX_VOLUME = 0.35f

    fun frameAt(
        progress: Float,
        startVolume: Float = DEFAULT_START_VOLUME,
        maxVolume: Float = DEFAULT_MAX_VOLUME,
    ): WakeRampFrame {
        val clamped = progress.coerceIn(0f, 1f)
        val eased = clamped * clamped * (3f - 2f * clamped)
        return WakeRampFrame(
            clampedProgress = clamped,
            easedProgress = eased,
            screenBrightness = lerp(0.02f, 1f, eased),
            audioVolume = lerp(startVolume, maxVolume, eased),
            hapticIntensity = ((eased - 0.5f) * 1.6f).coerceIn(0f, 0.8f),
            sunriseRgb =
                listOf(
                    lerp(0.08f, 1f, eased),
                    lerp(0.025f, 0.78f, eased),
                    lerp(0.01f, 0.48f, eased),
                ),
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction
}
