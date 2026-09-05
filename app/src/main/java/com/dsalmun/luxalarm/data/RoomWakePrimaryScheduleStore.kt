/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS
import com.dsalmun.luxalarm.wake.INITIAL_DYNAMIC_RECOVERY_DELAY_MILLIS
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId

internal data class WakeDynamicScheduleRequest(
    val event: WakeEventIdentity,
    val slot: WakeRecoverySlotId,
    val token: Long,
    val triggerEpochMillis: Long,
)

internal data class WakePrimarySchedulePlan(
    val primaryEvents: List<WakeEventIdentity>,
    val anchorKinds: List<WakeRecoveryAnchorKind>,
    val dynamicRequests: List<WakeDynamicScheduleRequest>,
)

private data class CanonicalAnchorProgress(
    val expectedGoalDispatchState: String,
    val primaryProjections: Map<WakeEventKind, ProjectionEvidence>,
    val anchorProjections: Map<WakeRecoveryAnchorKind, ProjectionEvidence>,
    val dynamicProjections: Map<WakeEventKind, ProjectionEvidence>,
)

private enum class ProjectionEvidence {
    MISSING,
    API_RETURNED,
    PROGRESSED,
}

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
            val dynamicRequests = canonicalInitialDynamicRequests(snapshot)
            val primaryProjections = primaries.associateWith { event ->
                anchorProgress.primaryProjections.getValue(event.kind)
            }
            check(
                primaryProjections.none { (event, evidence) ->
                    event.expectedTriggerEpochMillis <= nowEpochMillis &&
                        evidence == ProjectionEvidence.MISSING
                }
            ) {
                "Expired primary lacks durable API-return evidence"
            }
            check(
                anchorTriggers.none { (kind, trigger) ->
                    trigger <= nowEpochMillis &&
                        anchorProgress.anchorProjections.getValue(kind) ==
                            ProjectionEvidence.MISSING
                }
            ) {
                "Expired immutable anchor lacks durable API-return evidence"
            }
            dynamicRequests
                .filter { it.triggerEpochMillis <= nowEpochMillis }
                .forEach { request ->
                    check(
                        anchorProgress.dynamicProjections.getValue(request.event.kind) !=
                            ProjectionEvidence.MISSING
                    ) {
                        "Expired dynamic recovery lacks durable API-return evidence"
                    }
                }
            WakePrimarySchedulePlan(
                primaryEvents =
                    primaries.filter {
                        it.expectedTriggerEpochMillis > nowEpochMillis &&
                            primaryProjections.getValue(it) != ProjectionEvidence.PROGRESSED
                    },
                anchorKinds =
                    CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.filter {
                        anchorTriggers.getValue(it) > nowEpochMillis &&
                            anchorProgress.anchorProjections.getValue(it) !=
                                ProjectionEvidence.PROGRESSED
                    },
                dynamicRequests =
                    dynamicRequests.filter {
                        it.triggerEpochMillis > nowEpochMillis &&
                            anchorProgress.dynamicProjections.getValue(it.event.kind) !=
                                ProjectionEvidence.PROGRESSED
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
            val progress = requireCanonicalPrimaryRows(dao, snapshot, current)
            val row = checkNotNull(current.singleOrNull { it.eventKey == event.canonicalKey() })
            check(row.expectedTriggerEpochMs == event.expectedTriggerEpochMillis) {
                "Primary trigger conflicts with the desired snapshot"
            }
            when (progress.primaryProjections.getValue(event.kind)) {
                ProjectionEvidence.API_RETURNED,
                ProjectionEvidence.PROGRESSED -> Unit
                ProjectionEvidence.MISSING -> {
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
            val progress = requireCanonicalPrimaryRows(dao, snapshot, current)
            check(progress.primaryProjections.values.none { it == ProjectionEvidence.MISSING }) {
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
            val progress = requireCanonicalPrimaryRows(dao, snapshot, current)
            check(progress.primaryProjections.values.none { it == ProjectionEvidence.MISSING }) {
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

    fun preflightDynamicApiCall(
        snapshot: WakeRunSnapshotEntity,
        request: WakeDynamicScheduleRequest,
    ) {
        requireInitialDynamicRequest(snapshot, request)
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            val progress = requireCanonicalPrimaryRows(dao, snapshot, current)
            check(progress.primaryProjections.values.none { it == ProjectionEvidence.MISSING }) {
                "Dynamic recovery requires successful GOAL and START primary API returns"
            }
            val goal = WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
            check(
                dao.anchors(goal.canonicalKey()).size ==
                    CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.size
            ) {
                "Dynamic recovery requires all immutable anchor API returns"
            }
        }
    }

    fun recordDynamicApiReturn(
        snapshot: WakeRunSnapshotEntity,
        request: WakeDynamicScheduleRequest,
    ) {
        requireInitialDynamicRequest(snapshot, request)
        database.runInTransaction {
            requirePreparingAggregate(snapshot)
            val dao = database.wakePrimaryScheduleDao()
            val current = dao.dispatches(snapshot.id)
            val progress = requireCanonicalPrimaryRows(dao, snapshot, current)
            check(progress.primaryProjections.values.none { it == ProjectionEvidence.MISSING }) {
                "Dynamic recovery requires successful GOAL and START primary API returns"
            }
            val goal = WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs)
            check(
                dao.anchors(goal.canonicalKey()).size ==
                    CANONICAL_IMMUTABLE_WAKE_RECOVERY_ANCHOR_KINDS.size
            ) {
                "Dynamic recovery requires all immutable anchor API returns"
            }
            when (progress.dynamicProjections.getValue(request.event.kind)) {
                ProjectionEvidence.API_RETURNED,
                ProjectionEvidence.PROGRESSED -> Unit
                ProjectionEvidence.MISSING -> {
                    val row = current.single { it.eventKey == request.event.canonicalKey() }
                    check(
                        dao.markInitialDynamicAApiReturned(
                            row.eventKey,
                            request.triggerEpochMillis,
                        ) == 1
                    ) {
                        "Dynamic API-return record lost its compare-and-set"
                    }
                    faultHook("AFTER_${request.event.kind.name}_DYNAMIC_A_API_RETURN_RECORD")
                }
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
        check(
            current.size == 2 &&
                current.map { it.eventKind }.toSet() == setOf("START", "GOAL") &&
                current.all { it.armedPrimary in 0..1 }
        ) {
            "Primary scheduling requires exactly canonical START and GOAL dispatch rows"
        }
        val anchorProgress = requireCanonicalAnchors(dao, snapshot, current)
        val canonical = canonicalDispatches(snapshot, armedStart = 0, armedGoal = 0)
        val dynamicProjections = canonical.associate { expected ->
            val actual = current.single { it.eventKey == expected.eventKey }
            val trigger = initialDynamicTrigger(expected.expectedTriggerEpochMs)
            val projection =
                when {
                    isInitialDynamicAApiReturn(actual, trigger) -> ProjectionEvidence.API_RETURNED
                    isProgressedInitialDynamicA(actual) -> ProjectionEvidence.PROGRESSED
                    else -> ProjectionEvidence.MISSING
                }
            WakeEventKind.valueOf(expected.eventKind) to projection
        }
        val hasDependentApiReturnEvidence =
            anchorProgress.anchorProjections.values.any { it != ProjectionEvidence.MISSING } ||
                dynamicProjections.values.any { it != ProjectionEvidence.MISSING }
        val primaryProjections = canonical.associate { expected ->
            val kind = WakeEventKind.valueOf(expected.eventKind)
            val dynamicProjection = dynamicProjections.getValue(kind)
            val dynamicExpected =
                when (dynamicProjection) {
                    ProjectionEvidence.MISSING -> expected
                    ProjectionEvidence.API_RETURNED ->
                        expected.copy(
                            recoverySlotAAt =
                                initialDynamicTrigger(expected.expectedTriggerEpochMs),
                            recoverySlotAState = "ARMED",
                        )
                    ProjectionEvidence.PROGRESSED ->
                        expected.copy(state = "DEFERRED", recoverySlotAToken = 1L)
                }
            val hasNonPrimaryProgress = dynamicProjection == ProjectionEvidence.PROGRESSED
            val expectedDispatchState =
                if (kind == WakeEventKind.GOAL) {
                    anchorProgress.expectedGoalDispatchState
                } else if (hasNonPrimaryProgress) {
                    "DEFERRED"
                } else {
                    dynamicExpected.state
                }
            val apiReturnedExpected =
                dynamicExpected.copy(
                    state = expectedDispatchState,
                    armedPrimary = 1,
                )
            val progressedExpected = apiReturnedExpected.copy(state = "DEFERRED", armedPrimary = 0)
            val actual = current.single { it.eventKey == expected.eventKey }
            val projection =
                when {
                    actual == apiReturnedExpected -> ProjectionEvidence.API_RETURNED
                    kind == WakeEventKind.START && actual == progressedExpected ->
                        ProjectionEvidence.PROGRESSED
                    !hasDependentApiReturnEvidence && actual == expected ->
                        ProjectionEvidence.MISSING
                    else -> error("Primary scheduling found noncanonical dispatch rows")
                }
            kind to projection
        }
        if (anchorProgress.anchorProjections.values.any { it != ProjectionEvidence.MISSING }) {
            check(primaryProjections.values.none { it == ProjectionEvidence.MISSING }) {
                "Immutable anchor records require both primary API returns"
            }
        }
        if (dynamicProjections.values.any { it != ProjectionEvidence.MISSING }) {
            check(
                primaryProjections.values.none { it == ProjectionEvidence.MISSING } &&
                    anchorProgress.anchorProjections.values.none {
                        it == ProjectionEvidence.MISSING
                    }
            ) {
                "Dynamic recovery evidence requires all prerequisite API returns"
            }
        }
        val eventKeys = current.map { it.eventKey }
        check(dao.outboxCount(eventKeys) == 0L) {
            "Primary scheduling found unsupported outbox rows"
        }
        return anchorProgress.copy(
            primaryProjections = primaryProjections,
            dynamicProjections = dynamicProjections,
        )
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
        return CanonicalAnchorProgress(
            expectedGoalDispatchState =
                if (anchors.any { it.state == "CONSUMED" }) "DEFERRED" else "RECEIVED",
            primaryProjections = emptyMap(),
            anchorProjections =
                desired.associate { expected ->
                    val kind = WakeRecoveryAnchorKind.valueOf(expected.anchorKind)
                    val actual = anchors.singleOrNull { it.anchorKind == expected.anchorKind }
                    kind to
                        when (actual?.state) {
                            null -> ProjectionEvidence.MISSING
                            "ARMED" -> ProjectionEvidence.API_RETURNED
                            "FIRED",
                            "CONSUMED" -> ProjectionEvidence.PROGRESSED
                            else -> error("Unsupported immutable anchor state")
                        }
                },
            dynamicProjections = emptyMap(),
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

    private fun requireInitialDynamicRequest(
        snapshot: WakeRunSnapshotEntity,
        request: WakeDynamicScheduleRequest,
    ) {
        val expected =
            canonicalInitialDynamicRequests(snapshot).singleOrNull {
                it.event.kind == request.event.kind
            }
        require(request == expected) { "Dynamic recovery request is not canonical" }
    }

    private fun canonicalInitialDynamicRequests(snapshot: WakeRunSnapshotEntity) =
        listOf(
                WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs),
                WakeEventIdentity(snapshot.id, WakeEventKind.GOAL, snapshot.goalEpochMs),
            )
            .map { event ->
                WakeDynamicScheduleRequest(
                    event = event,
                    slot = WakeRecoverySlotId.A,
                    token = 0L,
                    triggerEpochMillis = initialDynamicTrigger(event.expectedTriggerEpochMillis),
                )
            }

    private fun initialDynamicTrigger(primaryTriggerEpochMillis: Long): Long =
        checkNotNull(
            primaryTriggerEpochMillis
                .takeIf { it <= Long.MAX_VALUE - INITIAL_DYNAMIC_RECOVERY_DELAY_MILLIS }
                ?.plus(INITIAL_DYNAMIC_RECOVERY_DELAY_MILLIS)
        ) {
            "Dynamic recovery trigger overflows epoch range"
        }

    private fun isInitialDynamicAApiReturn(
        row: WakeEventDispatchEntity,
        triggerEpochMillis: Long,
    ): Boolean =
        row.recoverySlotAAt == triggerEpochMillis &&
            row.recoverySlotAState == "ARMED" &&
            row.recoverySlotAToken == 0L &&
            row.recoverySlotBAt == null &&
            row.recoverySlotBState == "CONSUMED" &&
            row.recoverySlotBToken == 0L

    private fun isProgressedInitialDynamicA(row: WakeEventDispatchEntity): Boolean =
        row.recoverySlotAAt == null &&
            row.recoverySlotAState == "CONSUMED" &&
            row.recoverySlotAToken == 1L &&
            row.recoverySlotBAt == null &&
            row.recoverySlotBState == "CONSUMED" &&
            row.recoverySlotBToken == 0L

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
