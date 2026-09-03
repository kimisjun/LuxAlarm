/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class WakeTimelineTest {
    @Test
    fun nextOccurrenceUsesGoalDayEvenWhenWakeStartCrossesMidnight() {
        val routine = routine(LocalTime.of(0, 15), setOf(DayOfWeek.MONDAY))
        val occurrence =
            WakeTimeline.nextOccurrence(
                routine,
                Instant.parse("2026-01-04T23:20:00Z"),
                ZoneId.of("UTC"),
            )!!

        assertEquals(LocalDate.of(2026, 1, 5), occurrence.localDate)
        assertEquals(Instant.parse("2026-01-04T23:30:00Z"), occurrence.instant)
        assertEquals(Instant.parse("2026-01-05T00:15:00Z"), occurrence.goalInstant)
        assertEquals(ZoneOffset.UTC, occurrence.offset)
    }

    @Test
    fun nextOccurrenceIsStrictlyAfterNow() {
        val routine = routine(LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY)).withPreparationMinutes(0)
        val occurrence =
            WakeTimeline.nextOccurrence(
                routine,
                Instant.parse("2026-01-05T07:00:00Z"),
                ZoneId.of("UTC"),
            )!!

        assertEquals(Instant.parse("2026-01-12T07:00:00Z"), occurrence.instant)
    }

    @Test
    fun dstGapMovesForwardByTheGapToAValidInstant() {
        val routine = routine(LocalTime.of(2, 30), setOf(DayOfWeek.SUNDAY)).withPreparationMinutes(0)
        val occurrence =
            WakeTimeline.nextOccurrence(
                routine,
                Instant.parse("2026-03-08T00:00:00Z"),
                ZoneId.of("America/New_York"),
            )!!

        assertEquals(LocalDate.of(2026, 3, 8), occurrence.localDate)
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), occurrence.instant)
        assertEquals(ZoneOffset.ofHours(-4), occurrence.offset)
    }

    @Test
    fun dstOverlapChoosesZonesEarlierOccurrenceAndOffset() {
        val routine = routine(LocalTime.of(1, 30), setOf(DayOfWeek.SUNDAY)).withPreparationMinutes(0)
        val occurrence =
            WakeTimeline.nextOccurrence(
                routine,
                Instant.parse("2026-11-01T00:00:00Z"),
                ZoneId.of("America/New_York"),
            )!!

        assertEquals(LocalDate.of(2026, 11, 1), occurrence.localDate)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), occurrence.instant)
        assertEquals(ZoneOffset.ofHours(-4), occurrence.offset)
    }

    @Test
    fun recomputingWithAnotherTimezoneProducesThatZonesFutureInstant() {
        val routine = routine(LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY)).withPreparationMinutes(0)
        val now = Instant.parse("2026-01-04T00:00:00Z")

        val newYork = WakeTimeline.nextOccurrence(routine, now, ZoneId.of("America/New_York"))!!
        val seoul = WakeTimeline.nextOccurrence(routine, now, ZoneId.of("Asia/Seoul"))!!

        assertEquals(Instant.parse("2026-01-05T12:00:00Z"), newYork.instant)
        assertEquals(Instant.parse("2026-01-04T22:00:00Z"), seoul.instant)
    }

    @Test
    fun disabledOrNonRepeatingRoutineHasNoOccurrence() {
        val now = Instant.parse("2026-01-04T00:00:00Z")
        assertNull(WakeTimeline.nextOccurrence(routine(LocalTime.NOON, emptySet()), now, ZoneId.of("UTC")))
        assertNull(
            WakeTimeline.nextOccurrence(
                routine(LocalTime.NOON, setOf(DayOfWeek.MONDAY)).withEnabled(false),
                now,
                ZoneId.of("UTC"),
            ),
        )
    }

    private fun routine(goal: LocalTime, repeatDays: Set<DayOfWeek>): WakeRoutine =
        WakeRoutine.fromPreset(WakePreset.VERY_GENTLE, goal, repeatDays)
}
