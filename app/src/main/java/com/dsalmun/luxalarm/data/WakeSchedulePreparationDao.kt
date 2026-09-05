/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Query

/** Raw Room access for [RoomWakeSchedulePreparationStore]. */
@Dao
internal interface WakeSchedulePreparationDao {
    @Query("SELECT * FROM migration_state WHERE id = 1") fun migrationState(): MigrationStateEntity?

    @Query("SELECT * FROM wake_run_snapshot WHERE schedule_generation = :generation")
    fun snapshotsForGeneration(generation: Long): List<WakeRunSnapshotEntity>

    @Query("SELECT * FROM wake_run_status WHERE snapshot_id = :snapshotId")
    fun status(snapshotId: String): WakeRunStatusEntity?

    @Query("SELECT * FROM track_lease WHERE snapshot_id = :snapshotId")
    fun leases(snapshotId: String): List<TrackLeaseEntity>

    @Query("SELECT * FROM imported_track WHERE id = :trackId")
    fun importedTrack(trackId: String): ImportedTrackEntity?

    @Query("SELECT COUNT(*) FROM track_lease WHERE track_id = :trackId")
    fun leaseCountForTrack(trackId: String): Long

    @Query(
        """
        UPDATE migration_state
        SET schedule_owner = 'PREPARING_WAKE', active_generation = :generation
        WHERE id = 1
          AND schedule_owner = 'LEGACY'
          AND active_generation IS NULL
        """
    )
    fun beginFirstWakeGeneration(generation: Long): Int

    @Query(
        """
        UPDATE migration_state
        SET schedule_owner = 'PREPARING_WAKE', active_generation = :generation
        WHERE id = 1
          AND schedule_owner = 'WAKE'
          AND active_generation = :expectedGeneration
        """
    )
    fun beginNextWakeGeneration(expectedGeneration: Long, generation: Long): Int
}
