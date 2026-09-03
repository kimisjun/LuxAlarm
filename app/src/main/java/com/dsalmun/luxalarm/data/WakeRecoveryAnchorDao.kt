/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Query

/** Raw Room access for [RoomWakeRecoveryAnchorReceiptStore]; use the transactional store. */
@Dao
internal interface WakeRecoveryAnchorDao {
    @Query("SELECT * FROM wake_event_dispatch WHERE event_key = :eventKey")
    fun dispatch(eventKey: String): WakeEventDispatchEntity?

    @Query("SELECT * FROM wake_run_snapshot WHERE id = :snapshotId")
    fun snapshot(snapshotId: String): WakeRunSnapshotEntity?

    @Query("SELECT * FROM wake_run_status WHERE snapshot_id = :snapshotId")
    fun status(snapshotId: String): WakeRunStatusEntity?

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
}
