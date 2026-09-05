/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Raw Room access for primary scheduling; callers must use the transactional store. */
@Dao
internal interface WakePrimaryScheduleDao {
    @Query("SELECT * FROM wake_event_dispatch WHERE snapshot_id = :snapshotId ORDER BY event_kind")
    fun dispatches(snapshotId: String): List<WakeEventDispatchEntity>

    @Query(
        "SELECT * FROM wake_recovery_anchor WHERE event_key = :eventKey ORDER BY trigger_epoch_ms"
    )
    fun anchors(eventKey: String): List<WakeRecoveryAnchorEntity>

    @Query("SELECT COUNT(*) FROM schedule_outbox WHERE event_key IN (:eventKeys)")
    fun outboxCount(eventKeys: List<String>): Long

    @Insert fun insertDispatches(dispatches: List<WakeEventDispatchEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAnchor(anchor: WakeRecoveryAnchorEntity): Long

    @Query(
        """
        UPDATE wake_event_dispatch
        SET armed_primary = 1
        WHERE event_key = :eventKey
          AND expected_trigger_epoch_ms = :triggerEpochMillis
          AND armed_primary = 0
        """
    )
    fun markPrimaryApiReturned(eventKey: String, triggerEpochMillis: Long): Int

    @Query(
        """
        UPDATE wake_event_dispatch
        SET recovery_slot_a_at = :triggerEpochMillis,
            recovery_slot_a_state = 'ARMED'
        WHERE event_key = :eventKey
          AND recovery_slot_a_at IS NULL
          AND recovery_slot_a_state = 'CONSUMED'
          AND recovery_slot_a_token = 0
          AND recovery_slot_b_at IS NULL
          AND recovery_slot_b_state = 'CONSUMED'
          AND recovery_slot_b_token = 0
        """
    )
    fun markInitialDynamicAApiReturned(eventKey: String, triggerEpochMillis: Long): Int
}
