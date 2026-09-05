/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind

internal data class WakePrimarySchedulePlan(
    val primaryEvents: List<WakeEventIdentity>,
    val anchorKinds: List<WakeRecoveryAnchorKind>,
)

private data class CanonicalAnchorProgress(
    val hasAnyApiReturn: Boolean,
    val hasProgressedState: Boolean,
)

/**
 * Durable successful-API-return records for primary wake alarms and immutable GOAL anchors.
 *
 * `expected_trigger_epoch_ms` remains immutable primary desired time. `armed_primary=1` means only
 * that AlarmManager returned for that exact desired trigger; it does not assert current OS
 * registration. Zero means no successful return is durably known. Reconciliation therefore safely
 * reissues a future desired primary even when its marker is already one.
 *
 * An immutable-anchor row exists only after its exact canonical AlarmManager call returns. Its
 * `ARMED` state is that durable API-return marker and likewise does not claim the token is
 * currently registered with the OS; PendingIntent existence alone is never recorded as scheduling
 * success.
 */
internal class RoomWakePrimaryScheduleStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    /**
     * Decides from durable API-return evidence which strictly-future calls remain safe to issue.
     */
    fun prepareSchedule(
        snapshot: WakeRunSnapshotEntity,
        nowEpochMillis: Long,
    ): WakePrimarySchedulePlan {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        return database.runInTransaction<WakePrimarySchedulePlan> {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            var current = dao.dispatches(snapshot.id)
            if (current.isEmpty()) {
                dao.insertDispatches(canonicalDispatches(snapshot, armedStart = 0, armedGoal = 0))
                faultHook("AFTER_PRIMARY_DISPATCH_INSERT")
                current = dao.dispatches(snapshot.id)
            }
            val anchorProgress = requireCanonicalPrimaryRows(dao, snapshot, current)
            val primaries =
                listOf(
                    WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs),
                    WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs),
                )
            val goal = primaries.first()
            val anchorTriggers =
                CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.associateWith { kind ->
                    checkNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)) {
                        "Immutable anchor trigger overflows epoch range"
                    }
                }
            if (!anchorProgress.hasAnyApiReturn) {
                check(primaries.all { it.expectedTriggerEpochMillis > nowEpochMillis }) {
                    "Primary trigger is not strictly in the future"
                }
                check(anchorTriggers.values.all { it > nowEpochMillis }) {
                    "Immutable anchor trigger is not strictly in the future"
                }
            } else {
                primaries
                    .filter { it.expectedTriggerEpochMillis <= nowEpochMillis }
                    .forEach { event ->
                        check(
                            current.single { it.eventKey == event.canonicalKey() }.armedPrimary == 1
                        ) {
                            "Expired primary lacks durable API-return evidence"
                        }
                    }
                val anchorsByKind = dao.anchors(goal.canonicalKey()).associateBy { it.anchorKind }
                anchorTriggers
                    .filterValues { it <= nowEpochMillis }
                    .keys
                    .forEach { kind ->
                        check(anchorsByKind.containsKey(kind.name)) {
                            "Expired immutable anchor lacks durable API-return evidence"
                        }
                    }
            }
            WakePrimarySchedulePlan(
                primaryEvents = primaries.filter { it.expectedTriggerEpochMillis > nowEpochMillis },
                anchorKinds =
                    CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.filter {
                        anchorTriggers.getValue(it) > nowEpochMillis
                    },
            )
        }
    }

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

    /** Fences authority and requires both primary API returns before an immutable anchor call. */
    fun preflightAnchorApiCall(
        snapshot: WakeRunSnapshotEntity,
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ) {
        requireAnchorRequest(snapshot, event, kind)
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            requireCanonicalPrimaryRows(dao, snapshot, current)
            check(current.all { it.armedPrimary == 1 }) {
                "Immutable anchors require successful GOAL and START primary API returns"
            }
        }
    }

    /** ARMED means this exact canonical anchor's AlarmManager call returned successfully. */
    fun recordAnchorApiReturn(
        snapshot: WakeRunSnapshotEntity,
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ) {
        requireAnchorRequest(snapshot, event, kind)
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            requireCanonicalPrimaryRows(dao, snapshot, current)
            check(current.all { it.armedPrimary == 1 }) {
                "Immutable anchors require successful GOAL and START primary API returns"
            }
            val desired = canonicalAnchor(event, kind)
            if (dao.anchors(event.canonicalKey()).none { it.anchorKind == kind.name }) {
                check(dao.insertAnchor(desired) != -1L) {
                    "Anchor API-return record lost its insert"
                }
                faultHook("AFTER_${kind.name}_API_RETURN_RECORD")
            }
            check(
                isCanonicalAnchor(
                    dao.anchors(event.canonicalKey()).single { it.anchorKind == kind.name },
                    desired,
                )
            ) {
                "Anchor API-return record is noncanonical"
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
    ): CanonicalAnchorProgress {
        val armedByKind = current.associate { it.eventKind to it.armedPrimary }
        check(
            armedByKind.keys == setOf("START", "GOAL") &&
                current.size == 2 &&
                armedByKind.values.all { it in 0..1 }
        ) {
            "Primary scheduling requires exactly canonical START and GOAL dispatch rows"
        }
        val anchorProgress = requireCanonicalAnchors(dao, snapshot, current)
        val canonical =
            canonicalDispatches(
                snapshot,
                armedByKind.getValue("START"),
                armedByKind.getValue("GOAL"),
            )
        val goalState = current.single { it.eventKind == "GOAL" }.state
        val accepted =
            if (anchorProgress.hasProgressedState && goalState == "DEFERRED") {
                canonical.map { if (it.eventKind == "GOAL") it.copy(state = "DEFERRED") else it }
            } else {
                canonical
            }
        check(current == accepted) {
            "Primary scheduling found noncanonical dispatch rows"
        }
        val eventKeys = current.map { it.eventKey }
        check(dao.outboxCount(eventKeys) == 0L) {
            "Primary scheduling found unsupported outbox rows"
        }
        return anchorProgress
    }

    private fun requireCanonicalAnchors(
        dao: WakePrimaryScheduleDao,
        snapshot: WakeRunSnapshotEntity,
        dispatches: List<WakeEventDispatchEntity>,
    ): CanonicalAnchorProgress {
        val goal = WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
        val anchors = dao.anchors(goal.canonicalKey())
        val desired = CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.map {
            canonicalAnchor(goal, it)
        }
        check(
            anchors.size <= desired.size &&
                anchors.zip(desired).all { (actual, expected) ->
                    isCanonicalAnchor(actual, expected)
                }
        ) {
            "Primary scheduling found noncanonical immutable anchor records"
        }
        check(
            dispatches.none { it.eventKind == "START" && dao.anchors(it.eventKey).isNotEmpty() }
        ) {
            "Primary scheduling found immutable anchors outside GOAL"
        }
        if (anchors.isNotEmpty()) {
            check(dispatches.all { it.armedPrimary == 1 }) {
                "Immutable anchor records require both primary API returns"
            }
        }
        return CanonicalAnchorProgress(
            hasAnyApiReturn = anchors.isNotEmpty(),
            hasProgressedState = anchors.any { it.state == "FIRED" || it.state == "CONSUMED" },
        )
    }

    private fun isCanonicalAnchor(
        actual: WakeRecoveryAnchorEntity,
        armedCanonical: WakeRecoveryAnchorEntity,
    ): Boolean =
        actual.state in setOf("ARMED", "FIRED", "CONSUMED") &&
            actual == armedCanonical.copy(state = actual.state)

    private fun requireAnchorRequest(
        snapshot: WakeRunSnapshotEntity,
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ) {
        require(event == WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)) {
            "Immutable recovery anchor must identify the snapshot GOAL"
        }
        require(kind in CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS) {
            "GOAL_PRIMARY is not an immutable recovery anchor"
        }
    }

    private fun canonicalAnchor(
        event: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
    ) =
        WakeRecoveryAnchorEntity(
            eventKey = event.canonicalKey(),
            anchorKind = kind.name,
            triggerEpochMs =
                checkNotNull(kind.triggerForGoalOrNull(event.expectedTriggerEpochMillis)),
            state = "ARMED",
            pendingIntentIdentity = WakePendingIntentData.anchor(event, kind),
        )

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
