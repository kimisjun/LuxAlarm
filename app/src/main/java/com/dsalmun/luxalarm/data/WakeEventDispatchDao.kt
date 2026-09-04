/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Query

/** Raw Room access for [RoomWakeEventDispatchStore]; callers must use the transactional store. */
@Dao
internal interface WakeEventDispatchDao {
    @Query("SELECT * FROM wake_event_dispatch WHERE event_key = :eventKey")
    fun dispatch(eventKey: String): WakeEventDispatchEntity?

    @Query("SELECT * FROM wake_run_status WHERE snapshot_id = :snapshotId")
    fun status(snapshotId: String): WakeRunStatusEntity?

    @Query("SELECT * FROM wake_run_snapshot WHERE id = :snapshotId")
    fun snapshot(snapshotId: String): WakeRunSnapshotEntity?

    @Query("SELECT * FROM migration_state WHERE id = 1") fun migrationState(): MigrationStateEntity?

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
            recovery_slot_a_at = :nextRecoverySlotAAt,
            recovery_slot_a_state = :nextRecoverySlotAState,
            recovery_slot_a_token = :nextRecoverySlotAToken,
            recovery_slot_b_at = :nextRecoverySlotBAt,
            recovery_slot_b_state = :nextRecoverySlotBState,
            recovery_slot_b_token = :nextRecoverySlotBToken
        WHERE event_key IS :expectedEventKey
          AND snapshot_id IS :expectedSnapshotId
          AND event_kind IS :expectedEventKind
          AND expected_trigger_epoch_ms IS :expectedTriggerEpochMs
          AND state IS :expectedState
          AND dispatch_attempt_id IS :expectedDispatchAttemptId
          AND lease_owner IS :expectedLeaseOwner
          AND lease_expires_at IS :expectedLeaseExpiresAt
          AND attempt_count IS :expectedAttemptCount
          AND last_attempt_at IS :expectedLastAttemptAt
          AND failure_reason IS :expectedFailureReason
          AND armed_primary IS :expectedArmedPrimary
          AND recovery_slot_a_at IS :expectedRecoverySlotAAt
          AND recovery_slot_a_state IS :expectedRecoverySlotAState
          AND recovery_slot_a_token IS :expectedRecoverySlotAToken
          AND recovery_slot_b_at IS :expectedRecoverySlotBAt
          AND recovery_slot_b_state IS :expectedRecoverySlotBState
          AND recovery_slot_b_token IS :expectedRecoverySlotBToken
        """
    )
    fun compareAndSet(
        expectedEventKey: String,
        expectedSnapshotId: String,
        expectedEventKind: String,
        expectedTriggerEpochMs: Long,
        expectedState: String,
        expectedDispatchAttemptId: Long,
        expectedLeaseOwner: String?,
        expectedLeaseExpiresAt: Long?,
        expectedAttemptCount: Long,
        expectedLastAttemptAt: Long?,
        expectedFailureReason: String?,
        expectedArmedPrimary: Int,
        expectedRecoverySlotAAt: Long?,
        expectedRecoverySlotAState: String,
        expectedRecoverySlotAToken: Long,
        expectedRecoverySlotBAt: Long?,
        expectedRecoverySlotBState: String,
        expectedRecoverySlotBToken: Long,
        nextState: String,
        nextDispatchAttemptId: Long,
        nextLeaseOwner: String?,
        nextLeaseExpiresAt: Long?,
        nextAttemptCount: Long,
        nextLastAttemptAt: Long?,
        nextFailureReason: String?,
        nextArmedPrimary: Int,
        nextRecoverySlotAAt: Long?,
        nextRecoverySlotAState: String,
        nextRecoverySlotAToken: Long,
        nextRecoverySlotBAt: Long?,
        nextRecoverySlotBState: String,
        nextRecoverySlotBToken: Long,
    ): Int
}
