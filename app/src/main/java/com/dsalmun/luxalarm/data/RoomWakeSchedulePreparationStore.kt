/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeRunState

internal enum class WakeSchedulePreparationOutcome {
    PREPARED,
    CONVERGED,
}

/** Transactional seam that makes one desired wake generation durable before OS scheduling. */
internal class RoomWakeSchedulePreparationStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun prepare(
        snapshot: WakeRunSnapshotEntity,
        acquiredAtEpochMillis: Long,
    ): WakeSchedulePreparationOutcome {
        require(acquiredAtEpochMillis >= 0L) { "Lease acquisition epoch must not be negative" }
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs)
        )
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
        )
        return database.runInTransaction<WakeSchedulePreparationOutcome> {
            val dao = database.wakeSchedulePreparationDao()
            val migration =
                checkNotNull(dao.migrationState()) { "Missing migration_state singleton" }
            check(migration.id == 1) { "Invalid migration_state singleton" }
            check(snapshot.installEpoch == migration.installEpoch) {
                "Snapshot install epoch does not match migration state"
            }
            if (migration.scheduleOwner == "PREPARING_WAKE") {
                check(migration.activeGeneration == snapshot.scheduleGeneration) {
                    "Preparing owner is fenced to another generation"
                }
                check(dao.snapshotsForGeneration(snapshot.scheduleGeneration) == listOf(snapshot)) {
                    "Preparing generation conflicts with the durable snapshot"
                }
                val durableStatus =
                    validateDurableAggregate(
                        dao,
                        snapshot,
                        expectedLeaseAcquiredAt = acquiredAtEpochMillis,
                    )
                check(durableStatus == preparedWakeRunStatus(snapshot.id)) {
                    "Preparing generation has a noncanonical status"
                }
                return@runInTransaction WakeSchedulePreparationOutcome.CONVERGED
            }
            when (migration.scheduleOwner) {
                "LEGACY" ->
                    check(migration.activeGeneration == null && snapshot.scheduleGeneration == 1L) {
                        "First wake generation requires unfenced LEGACY ownership"
                    }
                "WAKE" -> {
                    val activeGeneration =
                        checkNotNull(migration.activeGeneration) {
                            "WAKE ownership requires an active generation"
                        }
                    val activeSnapshots = dao.snapshotsForGeneration(activeGeneration)
                    check(activeSnapshots.size == 1) {
                        "Active wake generation must identify exactly one snapshot"
                    }
                    val activeSnapshot = activeSnapshots.single()
                    val activeStatus = validateDurableAggregate(dao, activeSnapshot)
                    val pureStatus = activeStatus.toPureWakeRecoveryRunStatus()
                    check(
                        pureStatus.state in NEXT_GENERATION_READY_TERMINAL_STATES ||
                            (pureStatus.state == WakeRunState.GOAL_REACHED &&
                                pureStatus.processedGoalAtEpochMillis != null)
                    ) {
                        "Current GOAL must be processed before preparing the next generation"
                    }
                    check(activeGeneration < Long.MAX_VALUE) { "Wake generation is exhausted" }
                    check(snapshot.scheduleGeneration == activeGeneration + 1L) {
                        "Desired wake generation must advance exactly once"
                    }
                }
                else -> error("Wake schedule preparation is already in progress or restoring")
            }

            database.wakeRunStorageDao().createSnapshot(snapshot, acquiredAtEpochMillis)
            faultHook("AFTER_AGGREGATE_INSERT")
            val changed =
                when (migration.scheduleOwner) {
                    "LEGACY" -> dao.beginFirstWakeGeneration(snapshot.scheduleGeneration)
                    "WAKE" ->
                        dao.beginNextWakeGeneration(
                            checkNotNull(migration.activeGeneration),
                            snapshot.scheduleGeneration,
                        )
                    else -> error("Owner was validated before mutation")
                }
            check(changed == 1) { "Failed to fence desired wake generation" }
            WakeSchedulePreparationOutcome.PREPARED
        }
    }

    private fun validateDurableAggregate(
        dao: WakeSchedulePreparationDao,
        snapshot: WakeRunSnapshotEntity,
        expectedLeaseAcquiredAt: Long? = null,
    ): WakeRunStatusEntity {
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs)
        )
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
        )
        val status = checkNotNull(dao.status(snapshot.id)) { "Wake aggregate is missing status" }
        check(status.snapshotId == snapshot.id) { "Wake status does not correlate to its snapshot" }

        val leases = dao.leases(snapshot.id)
        val selectedTrackId = snapshot.selectedTrackId
        if (selectedTrackId == null) {
            check(leases.isEmpty()) {
                "Wake aggregate without a selected track cannot retain leases"
            }
        } else {
            check(
                leases.size == 1 &&
                    leases.single().snapshotId == snapshot.id &&
                    leases.single().trackId == selectedTrackId &&
                    (expectedLeaseAcquiredAt == null ||
                        leases.single().acquiredAt == expectedLeaseAcquiredAt)
            ) {
                "Wake aggregate has noncanonical track leases"
            }
            val track =
                checkNotNull(dao.importedTrack(selectedTrackId)) {
                    "Wake aggregate is missing its selected track"
                }
            check(track.storageKey == snapshot.selectedTrackStorageKey) {
                "Wake aggregate has a noncanonical selected track storage key"
            }
            // lifecycle_state controls leasing; MISSING_OR_BROKEN availability remains valid so
            // playback can preserve the selection and use its fallback.
            check(track.lifecycleState == "AVAILABLE") {
                "Wake aggregate selected track is not available for leasing"
            }
            check(track.refCountCache == dao.leaseCountForTrack(selectedTrackId)) {
                "Wake aggregate selected track has a stale ref-count cache"
            }
        }
        return status
    }

    private companion object {
        val NEXT_GENERATION_READY_TERMINAL_STATES =
            setOf(
                WakeRunState.COMPLETED,
                WakeRunState.NO_CONFIRMATION,
                WakeRunState.FAILED,
                WakeRunState.CANCELLED,
                WakeRunState.SUPERSEDED,
                WakeRunState.EXPIRED,
            )
    }
}
