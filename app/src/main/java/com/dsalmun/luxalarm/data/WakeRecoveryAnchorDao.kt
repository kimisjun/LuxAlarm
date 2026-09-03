/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Query

/** Raw Room access for recovery-anchor stores; use a transactional store for composite work. */
@Dao
internal interface WakeRecoveryAnchorDao {
    @Query("SELECT * FROM wake_event_dispatch WHERE event_key = :eventKey")
    fun dispatch(eventKey: String): WakeEventDispatchEntity?

    @Query("SELECT * FROM wake_run_snapshot WHERE id = :snapshotId")
    fun snapshot(snapshotId: String): WakeRunSnapshotEntity?

    @Query("SELECT * FROM wake_run_status WHERE snapshot_id = :snapshotId")
    fun status(snapshotId: String): WakeRunStatusEntity?

    @Query("SELECT * FROM migration_state WHERE id = 1") fun migrationState(): MigrationStateEntity?

    @Query(
        "SELECT * FROM wake_recovery_anchor WHERE event_key = :eventKey AND anchor_kind = :anchorKind"
    )
    fun anchor(eventKey: String, anchorKind: String): WakeRecoveryAnchorEntity?

    @Query(
        """
        UPDATE wake_recovery_anchor
        SET state = 'FIRED'
        WHERE event_key = :eventKey
          AND anchor_kind = :anchorKind
          AND state = 'ARMED'
          AND trigger_epoch_ms = :triggerEpochMs
          AND pending_intent_identity = :pendingIntentIdentity
        """
    )
    fun compareAndSetArmedToFired(
        eventKey: String,
        anchorKind: String,
        triggerEpochMs: Long,
        pendingIntentIdentity: String,
    ): Int

    @Query(
        """
        UPDATE wake_event_dispatch
        SET state = :nextState,
            dispatch_attempt_id = :nextDispatchAttemptId,
            lease_owner = :nextLeaseOwner,
            lease_expires_at = :nextLeaseExpiresAt,
            attempt_count = :nextAttemptCount,
            last_attempt_at = :nextLastAttemptAt,
            failure_reason = :nextFailureReason,
            armed_primary = :nextArmedPrimary,
            recovery_slot_a_at = :nextSlotAAt,
            recovery_slot_a_state = :nextSlotAState,
            recovery_slot_a_token = :nextSlotAToken,
            recovery_slot_b_at = :nextSlotBAt,
            recovery_slot_b_state = :nextSlotBState,
            recovery_slot_b_token = :nextSlotBToken
        WHERE event_key = :eventKey
          AND snapshot_id = :snapshotId
          AND event_kind = :eventKind
          AND expected_trigger_epoch_ms = :expectedTriggerEpochMs
          AND state = :expectedState
          AND dispatch_attempt_id = :expectedDispatchAttemptId
          AND lease_owner IS :expectedLeaseOwner
          AND lease_expires_at IS :expectedLeaseExpiresAt
          AND attempt_count = :expectedAttemptCount
          AND last_attempt_at IS :expectedLastAttemptAt
          AND failure_reason IS :expectedFailureReason
          AND armed_primary = :expectedArmedPrimary
          AND recovery_slot_a_at IS :expectedSlotAAt
          AND recovery_slot_a_state = :expectedSlotAState
          AND recovery_slot_a_token = :expectedSlotAToken
          AND recovery_slot_b_at IS :expectedSlotBAt
          AND recovery_slot_b_state = :expectedSlotBState
          AND recovery_slot_b_token = :expectedSlotBToken
        """
    )
    fun compareAndSetDispatchRaw(
        eventKey: String,
        snapshotId: String,
        eventKind: String,
        expectedTriggerEpochMs: Long,
        expectedState: String,
        expectedDispatchAttemptId: Long,
        expectedLeaseOwner: String?,
        expectedLeaseExpiresAt: Long?,
        expectedAttemptCount: Long,
        expectedLastAttemptAt: Long?,
        expectedFailureReason: String?,
        expectedArmedPrimary: Int,
        expectedSlotAAt: Long?,
        expectedSlotAState: String,
        expectedSlotAToken: Long,
        expectedSlotBAt: Long?,
        expectedSlotBState: String,
        expectedSlotBToken: Long,
        nextState: String,
        nextDispatchAttemptId: Long,
        nextLeaseOwner: String?,
        nextLeaseExpiresAt: Long?,
        nextAttemptCount: Long,
        nextLastAttemptAt: Long?,
        nextFailureReason: String?,
        nextArmedPrimary: Int,
        nextSlotAAt: Long?,
        nextSlotAState: String,
        nextSlotAToken: Long,
        nextSlotBAt: Long?,
        nextSlotBState: String,
        nextSlotBToken: Long,
    ): Int

    fun compareAndSetDispatchRequest(
        expected: WakeEventDispatchEntity,
        leaseOwner: String,
        leaseExpiresAt: Long,
        attemptedAt: Long,
        clearPrimary: Boolean = true,
    ): Int =
        compareAndSetDispatchRaw(
            expected.eventKey,
            expected.snapshotId,
            expected.eventKind,
            expected.expectedTriggerEpochMs,
            expected.state,
            expected.dispatchAttemptId,
            expected.leaseOwner,
            expected.leaseExpiresAt,
            expected.attemptCount,
            expected.lastAttemptAt,
            expected.failureReason,
            expected.armedPrimary,
            expected.recoverySlotAAt,
            expected.recoverySlotAState,
            expected.recoverySlotAToken,
            expected.recoverySlotBAt,
            expected.recoverySlotBState,
            expected.recoverySlotBToken,
            "DISPATCH_REQUESTED",
            expected.dispatchAttemptId + 1L,
            leaseOwner,
            leaseExpiresAt,
            expected.attemptCount + 1L,
            attemptedAt,
            null,
            if (clearPrimary) 0 else expected.armedPrimary,
            expected.recoverySlotAAt,
            expected.recoverySlotAState,
            expected.recoverySlotAToken,
            expected.recoverySlotBAt,
            expected.recoverySlotBState,
            expected.recoverySlotBToken,
        )

    fun compareAndSetReceivedToDeferred(expected: WakeEventDispatchEntity): Int =
        compareAndSetDispatchPostimage(expected, expected.copy(state = "DEFERRED"))

    fun compareAndSetPrimaryClear(expected: WakeEventDispatchEntity): Int =
        compareAndSetDispatchPostimage(expected, expected.copy(armedPrimary = 0))

    fun compareAndSetDispatchPostimage(
        expected: WakeEventDispatchEntity,
        next: WakeEventDispatchEntity,
    ): Int =
        compareAndSetDispatchRaw(
            expected.eventKey,
            expected.snapshotId,
            expected.eventKind,
            expected.expectedTriggerEpochMs,
            expected.state,
            expected.dispatchAttemptId,
            expected.leaseOwner,
            expected.leaseExpiresAt,
            expected.attemptCount,
            expected.lastAttemptAt,
            expected.failureReason,
            expected.armedPrimary,
            expected.recoverySlotAAt,
            expected.recoverySlotAState,
            expected.recoverySlotAToken,
            expected.recoverySlotBAt,
            expected.recoverySlotBState,
            expected.recoverySlotBToken,
            next.state,
            next.dispatchAttemptId,
            next.leaseOwner,
            next.leaseExpiresAt,
            next.attemptCount,
            next.lastAttemptAt,
            next.failureReason,
            next.armedPrimary,
            next.recoverySlotAAt,
            next.recoverySlotAState,
            next.recoverySlotAToken,
            next.recoverySlotBAt,
            next.recoverySlotBState,
            next.recoverySlotBToken,
        )

    @Query(
        """
        UPDATE wake_recovery_anchor
        SET state = 'CONSUMED'
        WHERE event_key = :eventKey
          AND anchor_kind = :anchorKind
          AND trigger_epoch_ms = :triggerEpochMs
          AND state = :expectedState
          AND pending_intent_identity = :pendingIntentIdentity
        """
    )
    fun compareAndSetFiredToConsumedRaw(
        eventKey: String,
        anchorKind: String,
        triggerEpochMs: Long,
        expectedState: String,
        pendingIntentIdentity: String,
    ): Int

    fun compareAndSetFiredToConsumed(expected: WakeRecoveryAnchorEntity): Int =
        compareAndSetFiredToConsumedRaw(
            expected.eventKey,
            expected.anchorKind,
            expected.triggerEpochMs,
            expected.state,
            expected.pendingIntentIdentity,
        )
}
