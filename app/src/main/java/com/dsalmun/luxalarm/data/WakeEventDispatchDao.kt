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

    @Query("SELECT * FROM migration_state WHERE id = 1") fun migrationState(): MigrationStateEntity?

    @Query(
        """
        UPDATE wake_event_dispatch
        SET state = :nextState,
            dispatch_attempt_id = :nextDispatchAttemptId,
            attempt_count = CASE WHEN :requestDispatch THEN attempt_count + 1 ELSE attempt_count END,
            last_attempt_at = CASE WHEN :requestDispatch THEN :nowEpochMillis ELSE last_attempt_at END,
            failure_reason = CASE WHEN :requestDispatch THEN NULL ELSE failure_reason END,
            lease_owner = CASE WHEN :requestDispatch THEN NULL ELSE lease_owner END,
            lease_expires_at = CASE WHEN :requestDispatch THEN NULL ELSE lease_expires_at END,
            armed_primary = CASE WHEN :primaryArrival THEN 0 ELSE armed_primary END,
            recovery_slot_a_state = CASE WHEN :arrivingSlot = 'A' THEN :nextSlotState ELSE recovery_slot_a_state END,
            recovery_slot_a_at = CASE WHEN :arrivingSlot = 'A' THEN :nextSlotTrigger ELSE recovery_slot_a_at END,
            recovery_slot_a_token = CASE WHEN :arrivingSlot = 'A' THEN :nextSlotToken ELSE recovery_slot_a_token END,
            recovery_slot_b_state = CASE WHEN :arrivingSlot = 'B' THEN :nextSlotState ELSE recovery_slot_b_state END,
            recovery_slot_b_at = CASE WHEN :arrivingSlot = 'B' THEN :nextSlotTrigger ELSE recovery_slot_b_at END,
            recovery_slot_b_token = CASE WHEN :arrivingSlot = 'B' THEN :nextSlotToken ELSE recovery_slot_b_token END
        WHERE event_key = :eventKey
          AND state = :expectedState
          AND dispatch_attempt_id = :expectedDispatchAttemptId
          AND (
            (:primaryArrival AND :arrivingSlot IS NULL AND armed_primary = 1) OR
            (NOT :primaryArrival AND :arrivingSlot = 'A'
              AND recovery_slot_a_state = :expectedSlotState
              AND ((recovery_slot_a_at IS NULL AND :expectedSlotTrigger IS NULL) OR recovery_slot_a_at = :expectedSlotTrigger)
              AND recovery_slot_a_token = :expectedSlotToken) OR
            (NOT :primaryArrival AND :arrivingSlot = 'B'
              AND recovery_slot_b_state = :expectedSlotState
              AND ((recovery_slot_b_at IS NULL AND :expectedSlotTrigger IS NULL) OR recovery_slot_b_at = :expectedSlotTrigger)
              AND recovery_slot_b_token = :expectedSlotToken)
          )
        """
    )
    fun compareAndSet(
        eventKey: String,
        expectedState: String,
        expectedDispatchAttemptId: Long,
        primaryArrival: Boolean,
        arrivingSlot: String?,
        expectedSlotState: String?,
        expectedSlotTrigger: Long?,
        expectedSlotToken: Long?,
        nextState: String,
        nextDispatchAttemptId: Long,
        nextSlotState: String?,
        nextSlotTrigger: Long?,
        nextSlotToken: Long?,
        requestDispatch: Boolean,
        nowEpochMillis: Long,
    ): Int
}
