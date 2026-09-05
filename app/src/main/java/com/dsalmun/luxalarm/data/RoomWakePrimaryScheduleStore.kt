/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind

/**
 * Durable successful-API-return records for the two primary wake alarms.
 *
 * `expected_trigger_epoch_ms` remains immutable desired time. `armed_primary=1` means only that
 * AlarmManager returned for that exact desired trigger; it does not assert current OS registration.
 * Zero means no successful return is durably known. Reconciliation therefore safely reissues a
 * future desired primary even when its marker is already one.
 */
internal class RoomWakePrimaryScheduleStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun ensureDesiredPrimaries(snapshot: WakeRunSnapshotEntity) {
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val expected = canonicalDispatches(snapshot, armedStart = 0, armedGoal = 0)
            val current = dao.dispatches(snapshot.id)
            if (current.isEmpty()) {
                dao.insertDispatches(expected)
                faultHook("AFTER_PRIMARY_DISPATCH_INSERT")
            } else {
                requireCanonicalPrimaryRows(dao, snapshot, current)
            }
        }
    }

    /** Revalidates the complete durable authority immediately before an external OS call. */
    fun preflightApiCall(snapshot: WakeRunSnapshotEntity, event: WakeEventIdentity) {
        require(event.snapshotId == snapshot.id) { "Primary event does not identify the snapshot" }
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            requireCanonicalPrimaryRows(dao, snapshot, current)
            val expected =
                checkNotNull(current.singleOrNull { it.eventKey == event.canonicalKey() })
            check(expected.expectedTriggerEpochMs == event.expectedTriggerEpochMillis) {
                "Primary trigger conflicts with the desired snapshot"
            }
        }
    }

    fun recordApiReturn(snapshot: WakeRunSnapshotEntity, event: WakeEventIdentity) {
        require(event.snapshotId == snapshot.id) { "Primary event does not identify the snapshot" }
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            requireCanonicalPrimaryRows(dao, snapshot, current)
            val row = checkNotNull(current.singleOrNull { it.eventKey == event.canonicalKey() })
            check(row.expectedTriggerEpochMs == event.expectedTriggerEpochMillis) {
                "Primary trigger conflicts with the desired snapshot"
            }
            if (row.armedPrimary == 0) {
                check(
                    dao.markPrimaryApiReturned(
                        event.canonicalKey(),
                        event.expectedTriggerEpochMillis,
                    ) == 1
                ) {
                    "Primary API-return record lost its compare-and-set"
                }
                faultHook("AFTER_${event.kind.name}_PRIMARY_API_RETURN_RECORD")
            }
        }
    }

    private fun requirePreparingAggregate(snapshot: WakeRunSnapshotEntity) {
        val dao = database.wakeSchedulePreparationDao()
        val migration = checkNotNull(dao.migrationState()) { "Missing migration_state singleton" }
        check(migration.id == 1) { "Invalid migration_state singleton" }
        check(migration.scheduleOwner == "PREPARING_WAKE") {
            "Primary scheduling requires PREPARING_WAKE ownership"
        }
        check(migration.activeGeneration == snapshot.scheduleGeneration) {
            "Primary scheduling is fenced to another generation"
        }
        check(snapshot.installEpoch == migration.installEpoch) {
            "Snapshot install epoch does not match migration state"
        }
        check(dao.snapshotsForGeneration(snapshot.scheduleGeneration) == listOf(snapshot)) {
            "Primary scheduling conflicts with the durable snapshot"
        }
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs)
        )
        snapshot.requireCanonicalFor(
            WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
        )
        check(dao.status(snapshot.id) == preparedWakeRunStatus(snapshot.id)) {
            "Primary scheduling requires canonical PREPARED status"
        }
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
                    leases.single().trackId == selectedTrackId
            ) {
                "Wake aggregate has noncanonical track leases"
            }
            val track = checkNotNull(dao.importedTrack(selectedTrackId))
            check(
                track.storageKey == snapshot.selectedTrackStorageKey &&
                    track.lifecycleState == "AVAILABLE" &&
                    track.refCountCache == dao.leaseCountForTrack(selectedTrackId)
            ) {
                "Wake aggregate has a noncanonical selected track"
            }
        }
    }

    private fun requireCanonicalPrimaryRows(
        dao: WakePrimaryScheduleDao,
        snapshot: WakeRunSnapshotEntity,
        current: List<WakeEventDispatchEntity>,
    ) {
        val armedByKind = current.associate { it.eventKind to it.armedPrimary }
        check(
            armedByKind.keys == setOf("START", "GOAL") &&
                current.size == 2 &&
                armedByKind.values.all { it in 0..1 }
        ) {
            "Primary scheduling requires exactly canonical START and GOAL dispatch rows"
        }
        check(
            current ==
                canonicalDispatches(
                    snapshot,
                    armedByKind.getValue("START"),
                    armedByKind.getValue("GOAL"),
                )
        ) {
            "Primary scheduling found noncanonical dispatch rows"
        }
        val eventKeys = current.map { it.eventKey }
        check(dao.anchorCount(eventKeys) == 0L && dao.outboxCount(eventKeys) == 0L) {
            "Primary scheduling found rows outside the Task 5.2B aggregate"
        }
    }

    private fun canonicalDispatches(
        snapshot: WakeRunSnapshotEntity,
        armedStart: Int,
        armedGoal: Int,
    ): List<WakeEventDispatchEntity> =
        listOf(
                canonicalDispatch(snapshot, WakeEventKind.GOAL, snapshot.goalEpochMs, armedGoal),
                canonicalDispatch(
                    snapshot,
                    WakeEventKind.START,
                    snapshot.wakeStartEpochMs,
                    armedStart,
                ),
            )
            .sortedBy { it.eventKind }

    private fun canonicalDispatch(
        snapshot: WakeRunSnapshotEntity,
        kind: WakeEventKind,
        trigger: Long,
        armedPrimary: Int,
    ): WakeEventDispatchEntity {
        val event = WakeEventIdentity(snapshot.id, kind, trigger)
        return WakeEventDispatchEntity(
            eventKey = event.canonicalKey(),
            snapshotId = snapshot.id,
            eventKind = kind.name,
            expectedTriggerEpochMs = trigger,
            state = "RECEIVED",
            dispatchAttemptId = 0L,
            leaseOwner = null,
            leaseExpiresAt = null,
            attemptCount = 0L,
            lastAttemptAt = null,
            failureReason = null,
            armedPrimary = armedPrimary,
            recoverySlotAAt = null,
            recoverySlotAState = "CONSUMED",
            recoverySlotAToken = 0L,
            recoverySlotBAt = null,
            recoverySlotBState = "CONSUMED",
            recoverySlotBToken = 0L,
        )
    }
}
