/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import org.junit.Test

class SleepPlanTest {
    @Test
    fun sevenAmWakeOffersThreeBedtimesFromLongestToShortestSleep() {
        val recommendations = recommendBedtimes(LocalTime.of(7, 0))

        assertEquals(
            listOf(
                BedtimeRecommendation(
                    BedtimeRecommendationKind.NINE_HOURS,
                    LocalTime.of(21, 45),
                    540,
                    -1,
                ),
                BedtimeRecommendation(
                    BedtimeRecommendationKind.EIGHT_HOURS,
                    LocalTime.of(22, 45),
                    480,
                    -1,
                ),
                BedtimeRecommendation(
                    BedtimeRecommendationKind.SEVEN_AND_HALF_HOURS,
                    LocalTime.of(23, 15),
                    450,
                    -1,
                ),
            ),
            recommendations,
        )
    }

    @Test
    fun recommendationsPreserveTheIntendedElapsedIntervalAcrossMidnight() {
        val wake = LocalDate.of(2026, 9, 7).atTime(0, 30)

        recommendBedtimes(wake.toLocalTime()).forEach { recommendation ->
            val bedtime =
                wake
                    .toLocalDate()
                    .plusDays(recommendation.bedtimeDayOffset.toLong())
                    .atTime(recommendation.bedtime)
            assertEquals(
                recommendation.sleepMinutes + 15L,
                Duration.between(bedtime, wake).toMinutes(),
            )
        }
    }
}
