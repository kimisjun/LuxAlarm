/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.time.LocalTime

enum class BedtimeRecommendationKind {
    NINE_HOURS,
    EIGHT_HOURS,
    SEVEN_AND_HALF_HOURS,
}

data class BedtimeRecommendation(
    val kind: BedtimeRecommendationKind,
    val bedtime: LocalTime,
    val sleepMinutes: Int,
    /** -1 means the bedtime is on the calendar day before the wake date. */
    val bedtimeDayOffset: Int,
)

data class SleepPlan(
    val wakeMinutes: Int,
    val bedtimeMinutes: Int,
    val bedtimeDayOffset: Int,
)

interface SleepPlanStore {
    suspend fun load(): SleepPlan?

    suspend fun save(plan: SleepPlan)
}

/**
 * Offers three transparent duration-based bedtime choices for one wake time. The default
 * fifteen-minute offset is an onboarding convenience for time spent falling asleep, not a
 * measurement or medical claim.
 */
fun recommendBedtimes(
    wakeTime: LocalTime,
    fallAsleepMinutes: Int = 15,
): List<BedtimeRecommendation> {
    require(fallAsleepMinutes >= 0) { "fallAsleepMinutes must not be negative" }
    return listOf(
            BedtimeRecommendationKind.NINE_HOURS to 9 * 60,
            BedtimeRecommendationKind.EIGHT_HOURS to 8 * 60,
            BedtimeRecommendationKind.SEVEN_AND_HALF_HOURS to 7 * 60 + 30,
        )
        .map { (kind, sleepMinutes) ->
            val bedtime = wakeTime.minusMinutes((sleepMinutes + fallAsleepMinutes).toLong())
            BedtimeRecommendation(
                kind = kind,
                bedtime = bedtime,
                sleepMinutes = sleepMinutes,
                bedtimeDayOffset = if (bedtime < wakeTime) 0 else -1,
            )
        }
}
