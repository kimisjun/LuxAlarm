/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class WakeRampTest {
    @Test
    fun matchesEveryCheckedInJsonVector() {
        val vectors = readVectors()

        assertEquals(7, vectors.size, "The canonical vector set changed; update this parser if needed")
        vectors.forEach { expected ->
            val actual = WakeRamp.frameAt(expected.progress)
            assertClose(expected.clampedProgress, actual.clampedProgress, "clamped progress")
            assertClose(expected.easedProgress, actual.easedProgress, "eased progress")
            assertClose(expected.screenBrightness, actual.screenBrightness, "screen brightness")
            assertClose(expected.audioVolume, actual.audioVolume, "audio volume")
            assertClose(expected.hapticIntensity, actual.hapticIntensity, "haptic intensity")
            expected.sunriseRgb.zip(actual.sunriseRgb).forEachIndexed { index, (want, got) ->
                assertClose(want, got, "sunrise RGB[$index]")
            }
        }
    }

    private fun assertClose(expected: Float, actual: Float, label: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= 0.000001f,
            "$label: expected $expected, got $actual",
        )
    }

    /** Tiny purpose-built reader keeps the production ramp free of JSON/runtime dependencies. */
    private fun readVectors(): List<Vector> {
        val repository =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "wake-ramp-test-vectors.json").isFile }
        val json = File(repository, "wake-ramp-test-vectors.json").readText()
        val vectorBody = json.substringAfter("\"vectors\": [").substringBeforeLast("]")
        return Regex("\\{(.*?)\\}", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(vectorBody)
            .map { match ->
                val objectText = match.groupValues[1]
                Vector(
                    progress = objectText.number("progress"),
                    clampedProgress = objectText.number("clampedProgress"),
                    easedProgress = objectText.number("easedProgress"),
                    screenBrightness = objectText.number("screenBrightness"),
                    audioVolume = objectText.number("audioVolume"),
                    hapticIntensity = objectText.number("hapticIntensity"),
                    sunriseRgb =
                        objectText
                            .substringAfter("\"sunriseRGB\": [")
                            .substringBefore("]")
                            .split(",")
                            .map { it.trim().toFloat() },
                )
            }
            .toList()
    }

    private fun String.number(name: String): Float =
        Regex("\"$name\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
            .find(this)
            ?.groupValues
            ?.get(1)
            ?.toFloat()
            ?: error("Missing $name in $this")

    private data class Vector(
        val progress: Float,
        val clampedProgress: Float,
        val easedProgress: Float,
        val screenBrightness: Float,
        val audioVolume: Float,
        val hapticIntensity: Float,
        val sunriseRgb: List<Float>,
    )
}
