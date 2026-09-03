/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
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
