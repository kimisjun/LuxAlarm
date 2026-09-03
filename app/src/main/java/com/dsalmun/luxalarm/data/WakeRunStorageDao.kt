/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

enum class DeletionClaimResult {
    CLAIMED,
    ALREADY_OWNED,
    REJECTED,
}

@Dao
abstract class WakeRunStorageDao {
    @Transaction
    open fun createSnapshot(snapshot: WakeRunSnapshotEntity, acquiredAt: Long) {
        val trackId = snapshot.selectedTrackId
        if (trackId == null) {
            check(snapshot.selectedTrackStorageKey == null) {
                "Track storage key requires a selected track"
            }
        } else {
            val authoritativeStorageKey = availableTrackStorageKey(trackId)
            check(authoritativeStorageKey != null) { "Selected track is not AVAILABLE: $trackId" }
            check(snapshot.selectedTrackStorageKey == authoritativeStorageKey) {
                "Selected track storage key does not match: $trackId"
            }
        }

        insertSnapshot(snapshot)
        insertPreparedStatus(preparedStatus(snapshot.id))
        if (trackId != null) {
            insertLease(TrackLeaseEntity(snapshot.id, trackId, acquiredAt))
            recomputeTrackRefCount(trackId)
        }
    }

    @Transaction
    open fun deleteSnapshots(snapshotIds: List<String>): Int {
        if (snapshotIds.isEmpty()) return 0
        val uniqueIds = snapshotIds.toCollection(LinkedHashSet())
        val affectedTrackIds =
            uniqueIds.chunked(SQLITE_BIND_BATCH_SIZE).flatMap(::trackIdsForSnapshots).toSet()
        val deleted = uniqueIds.chunked(SQLITE_BIND_BATCH_SIZE).sumOf(::deleteSnapshotsById)
        affectedTrackIds.forEach(::recomputeTrackRefCount)
        return deleted
    }

    @Transaction
    open fun claimTrackDeletion(trackId: String, proposedToken: String): DeletionClaimResult {
        require(proposedToken.isNotBlank()) { "Deletion token must not be blank" }
        require(proposedToken.length <= MAX_DELETION_TOKEN_LENGTH) {
            "Deletion token must be at most $MAX_DELETION_TOKEN_LENGTH characters"
        }
        recomputeTrackRefCount(trackId)
        if (ownsDeletionClaim(trackId, proposedToken)) {
            return DeletionClaimResult.ALREADY_OWNED
        }
        return if (markTrackDeleting(trackId, proposedToken) == 1) {
            DeletionClaimResult.CLAIMED
        } else {
            DeletionClaimResult.REJECTED
        }
    }

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM imported_track
            WHERE id = :trackId
              AND lifecycle_state = 'DELETING'
              AND deletion_token = :proposedToken
              AND NOT EXISTS (SELECT 1 FROM track_lease WHERE track_id = :trackId)
        )
        """
    )
    protected abstract fun ownsDeletionClaim(trackId: String, proposedToken: String): Boolean

    @Query(
        """
        UPDATE imported_track
        SET lifecycle_state = 'DELETING', deletion_token = :proposedToken
        WHERE id = :trackId
          AND lifecycle_state IN ('AVAILABLE', 'PENDING_DELETE')
          AND NOT EXISTS (SELECT 1 FROM track_lease WHERE track_id = :trackId)
          AND NOT EXISTS (
              SELECT 1 FROM imported_track
              WHERE lifecycle_state = 'DELETING' AND deletion_token = :proposedToken
          )
        """
    )
    protected abstract fun markTrackDeleting(trackId: String, proposedToken: String): Int

    @Transaction
    open fun finalizeTrackDeletion(trackId: String, deletionToken: String): Boolean {
        recomputeTrackRefCount(trackId)
        return markTrackDeleted(trackId, deletionToken) == 1
    }

    @Query(
        """
        UPDATE imported_track
        SET lifecycle_state = 'DELETED', deletion_token = NULL
        WHERE id = :trackId
          AND lifecycle_state = 'DELETING'
          AND deletion_token = :deletionToken
        """
    )
    protected abstract fun markTrackDeleted(trackId: String, deletionToken: String): Int

    @Query("SELECT DISTINCT track_id FROM track_lease WHERE snapshot_id IN (:snapshotIds)")
    protected abstract fun trackIdsForSnapshots(snapshotIds: List<String>): List<String>

    @Query("DELETE FROM wake_run_snapshot WHERE id IN (:snapshotIds)")
    protected abstract fun deleteSnapshotsById(snapshotIds: List<String>): Int

    /** Must complete before any caller schedules future wake runs. */
    @Transaction
    open fun reconstructTrackRefCountsAtStartup(): Int {
        val mismatches = countTrackRefCountMismatches()
        recomputeAllTrackRefCounts()
        return mismatches
    }

    @Query(
        """
        SELECT COUNT(*) FROM imported_track
        WHERE ref_count_cache != (
            SELECT COUNT(*) FROM track_lease WHERE track_id = imported_track.id
        )
        """
    )
    protected abstract fun countTrackRefCountMismatches(): Int

    @Query(
        """
        UPDATE imported_track
        SET ref_count_cache = (
            SELECT COUNT(*) FROM track_lease WHERE track_id = imported_track.id
        )
        """
    )
    protected abstract fun recomputeAllTrackRefCounts()

    // lifecycle_state controls leasing. availability may be MISSING_OR_BROKEN so a durable
    // snapshot preserves the user's selection while playback uses the bundled fallback.
    @Query(
        "SELECT storage_key FROM imported_track WHERE id = :trackId AND lifecycle_state = 'AVAILABLE'"
    )
    protected abstract fun availableTrackStorageKey(trackId: String): String?

    @Insert protected abstract fun insertSnapshot(snapshot: WakeRunSnapshotEntity)

    @Insert protected abstract fun insertPreparedStatus(status: WakeRunStatusEntity)

    @Insert protected abstract fun insertLease(lease: TrackLeaseEntity)

    @Query(
        """
        UPDATE imported_track
        SET ref_count_cache = (SELECT COUNT(*) FROM track_lease WHERE track_id = :trackId)
        WHERE id = :trackId
        """
    )
    protected abstract fun recomputeTrackRefCount(trackId: String)

    private fun preparedStatus(snapshotId: String) =
        WakeRunStatusEntity(
            snapshotId = snapshotId,
            state = "PREPARED",
            processedStartAt = null,
            processedGoalAt = null,
            activeServiceOwnerToken = null,
            executionEpoch = 0,
            serviceLeaseOwner = null,
            serviceLeaseExpiresAt = null,
            heartbeatAt = null,
            armedStart = 0,
            armedGoal = 0,
            startedAt = null,
            completedAt = null,
            cancelledAt = null,
            failureReason = null,
        )

    private companion object {
        const val SQLITE_BIND_BATCH_SIZE = 900
        const val MAX_DELETION_TOKEN_LENGTH = 128
    }
}
