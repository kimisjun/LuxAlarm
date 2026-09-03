/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class WakeOccurrence(
    /** The routine's first enabled stage instant. */
    val instant: Instant,
    /** The local calendar date to which the repeated goal belongs. */
    val localDate: LocalDate,
    /** The zone offset selected while resolving the goal local date-time. */
    val offset: ZoneOffset,
    val goalInstant: Instant,
)

/** Pure, deterministic timeline calculations for [WakeRoutine]. */
object WakeTimeline {
    /**
     * Returns the next routine start strictly after [now]. Calling this again with another zone
     * intentionally recomputes the future occurrence; no active-run state is modeled here.
     */
    fun nextOccurrence(
        routine: WakeRoutine,
        now: Instant,
        zoneId: ZoneId,
    ): WakeOccurrence? {
        if (!routine.enabled || routine.repeatDays.isEmpty()) return null

        val firstDate = now.atZone(zoneId).toLocalDate()
        for (daysAhead in 0L..7L) {
            val date = firstDate.plusDays(daysAhead)
            if (date.dayOfWeek !in routine.repeatDays) continue

            val resolvedGoal = resolveGoal(LocalDateTime.of(date, routine.goal), zoneId)
            val start = resolvedGoal.instant.minusSeconds(routine.preparationMinutes * 60L)
            if (start.isAfter(now)) {
                return WakeOccurrence(
                    instant = start,
                    localDate = date,
                    offset = resolvedGoal.offset,
                    goalInstant = resolvedGoal.instant,
                )
            }
        }
        return null
    }

    private fun resolveGoal(localDateTime: LocalDateTime, zoneId: ZoneId): ResolvedGoal {
        val rules = zoneId.rules
        val validOffsets = rules.getValidOffsets(localDateTime)
        return when (validOffsets.size) {
            1 -> ResolvedGoal(localDateTime.toInstant(validOffsets.single()), validOffsets.single())
            2 -> {
                // ZoneRules orders overlap offsets with the earlier occurrence first.
                val earlierOffset = validOffsets.first()
                ResolvedGoal(localDateTime.toInstant(earlierOffset), earlierOffset)
            }
            else -> {
                // In a gap, move forward by the transition duration (java.time's standard rule).
                val transition = requireNotNull(rules.getTransition(localDateTime))
                val adjusted = localDateTime.plusSeconds(transition.duration.seconds)
                ResolvedGoal(adjusted.toInstant(transition.offsetAfter), transition.offsetAfter)
            }
        }
    }

    private data class ResolvedGoal(val instant: Instant, val offset: ZoneOffset)
}
