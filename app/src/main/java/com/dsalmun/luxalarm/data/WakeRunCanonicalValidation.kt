/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeFailureReason
import com.dsalmun.luxalarm.wake.WakeRecoveryRunStatus
import com.dsalmun.luxalarm.wake.WakeRunState
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId

/** Shared canonical validation for persisted wake-run context consumed by recovery boundaries. */
internal fun WakeRunSnapshotEntity.requireCanonicalFor(event: WakeEventIdentity) {
    require(id == event.snapshotId)
    require(id.isNotBlank())
    require(occurrenceId.isNotBlank() && '\u0000' !in occurrenceId)
    require(scheduleGeneration >= 0L)
    require(routineRevision >= 0L)
    require(calculationRuleVersion >= 0L)
    require(zoneId.isNotBlank())
    val canonicalZoneId =
        try {
            ZoneId.of(zoneId).id
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("Invalid snapshot zone ID", exception)
        }
    require(canonicalZoneId == zoneId)
    require(occurrenceLocalDate.isNotBlank())
    val canonicalLocalDate =
        try {
            LocalDate.parse(occurrenceLocalDate).toString()
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("Invalid snapshot occurrence date", exception)
        }
    require(canonicalLocalDate == occurrenceLocalDate)
    require(wakeStartEpochMs >= 0L && goalEpochMs >= 0L)
    require(wakeStartEpochMs <= goalEpochMs)
    require(
        event.expectedTriggerEpochMillis ==
            when (event.kind) {
                WakeEventKind.START -> wakeStartEpochMs
                WakeEventKind.GOAL -> goalEpochMs
            }
    )
    require(lightPayload.isNotBlank())
    require(musicPayload.isNotBlank())
    require(vibrationPayload.isNotBlank())
    require((selectedTrackId == null) == (selectedTrackStorageKey == null))
    require(selectedTrackId == null || selectedTrackId.isNotBlank())
    require(selectedTrackStorageKey == null || selectedTrackStorageKey.isNotBlank())
    require(dismissal == "CONFIRM" || dismissal == "LUX")
    require(createdAt >= 0L)
    require(installEpoch.isNotBlank() && '\u0000' !in installEpoch)
}

/** Converts a persisted status through the pure protocol's complete invariant constructor. */
internal fun WakeRunStatusEntity.toPureWakeRecoveryRunStatus(): WakeRecoveryRunStatus =
    WakeRecoveryRunStatus(
        state = WakeRunState.valueOf(state),
        processedStartAtEpochMillis = processedStartAt,
        processedGoalAtEpochMillis = processedGoalAt,
        activeServiceOwnerToken = activeServiceOwnerToken,
        executionEpoch = executionEpoch,
        serviceLeaseOwner = serviceLeaseOwner,
        serviceLeaseExpiresAtEpochMillis = serviceLeaseExpiresAt,
        heartbeatAtEpochMillis = heartbeatAt,
        armedStart = armedStart.toCanonicalBooleanFlag(),
        armedGoal = armedGoal.toCanonicalBooleanFlag(),
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
        cancelledAtEpochMillis = cancelledAt,
        failureReason = failureReason?.let(WakeFailureReason::valueOf),
    )

private fun Int.toCanonicalBooleanFlag(): Boolean {
    require(this in 0..1)
    return this == 1
}
