/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeDispatchAuthorization
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSource
import com.dsalmun.luxalarm.wake.WakeDispatchState
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeFailureReason
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorRow
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorState
import com.dsalmun.luxalarm.wake.WakeRecoveryRunStatus
import com.dsalmun.luxalarm.wake.WakeRecoverySlotState
import com.dsalmun.luxalarm.wake.WakeRunState
import com.dsalmun.luxalarm.wake.WakeScheduleOwner
import com.dsalmun.luxalarm.wake.isValidOwnerToken

private const val DISPATCH_LEASE_MILLIS = 60_000L

internal enum class WakeRecoveryAnchorProcessingOutcome {
    TERMINALIZED_NO_CONFIRMATION,
    NEW_DISPATCH_REQUEST,
    EXISTING_DURABLE_REQUEST,
    HEALTHY_EXECUTION,
    DEFERRED_DURABLE,
    OUT_OF_SCOPE_DEADLINE,
    STALE_TERMINAL,
    STALE_DELIVERY,
    FAIL_CLOSED,
    RETRY_REQUIRED,
}

internal enum class WakeRecoveryAnchorProcessingRecommendation {
    NONE,
    DEFER_TO_TERMINAL,
}

internal data class WakeRecoveryAnchorDispatchRequest(
    val eventKey: String,
    val dispatchAttemptId: Long,
    val leaseOwner: String,
    val leaseExpiresAtEpochMillis: Long,
)

internal interface WakeRecoveryAnchorProcessingResult {
    val outcome: WakeRecoveryAnchorProcessingOutcome
    val dispatchRequest: WakeRecoveryAnchorDispatchRequest?
    val authorization: WakeDispatchAuthorization?
    val recommendation: WakeRecoveryAnchorProcessingRecommendation
}

/** Sole auditable construction path for anchor-processing result payloads. */
internal object WakeRecoveryAnchorProcessingResultFactory {
    fun create(
        outcome: WakeRecoveryAnchorProcessingOutcome,
        dispatchRequest: WakeRecoveryAnchorDispatchRequest?,
        authorization: WakeDispatchAuthorization?,
    ): WakeRecoveryAnchorProcessingResult {
        val carriesDispatch = outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST
        require(carriesDispatch == (authorization != null)) {
            "Authorization exists exactly for NEW_DISPATCH_REQUEST"
        }
        require(carriesDispatch == (dispatchRequest != null)) {
            "Compatibility request exists exactly for NEW_DISPATCH_REQUEST"
        }
        if (authorization != null && dispatchRequest != null) {
            require(dispatchRequest.eventKey == authorization.eventKey)
            require(dispatchRequest.dispatchAttemptId == authorization.dispatchAttemptId)
            require(dispatchRequest.leaseOwner == authorization.leaseOwner)
            require(dispatchRequest.leaseExpiresAtEpochMillis == authorization.leaseExpiresAt)
        }
        val recommendation =
            if (outcome == WakeRecoveryAnchorProcessingOutcome.OUT_OF_SCOPE_DEADLINE) {
                WakeRecoveryAnchorProcessingRecommendation.DEFER_TO_TERMINAL
            } else {
                WakeRecoveryAnchorProcessingRecommendation.NONE
            }
        return object : WakeRecoveryAnchorProcessingResult {
            override val outcome = outcome
            override val dispatchRequest = dispatchRequest
            override val authorization = authorization
            override val recommendation = recommendation
        }
    }
}

/** Atomically converts an exact FIRED immutable anchor into already-durable recovery work. */
internal class RoomWakeRecoveryAnchorProcessingStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun processDeadline(delivery: WakeRecoveryAnchorDelivery): WakeRecoveryAnchorProcessingResult {
        try {
            return database.runInTransaction<WakeRecoveryAnchorProcessingResult> {
                val dao = database.wakeRecoveryAnchorDao()
                val eventKey = delivery.event.canonicalKey()
                val anchor =
                    dao.anchor(eventKey, delivery.kind.name)
                        ?: return@runInTransaction resultFailClosed()
                if (
                    anchor.eventKey != delivery.event.canonicalKey() ||
                        anchor.anchorKind != delivery.kind.name ||
                        anchor.triggerEpochMs != delivery.triggerEpochMillis ||
                        anchor.pendingIntentIdentity != delivery.pendingIntentIdentity ||
                        delivery.receivedAtEpochMillis < delivery.triggerEpochMillis
                ) {
                    return@runInTransaction result(
                        WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY
                    )
                }
                val dispatch = dao.dispatch(eventKey) ?: return@runInTransaction resultFailClosed()
                val snapshot =
                    dao.snapshot(delivery.event.snapshotId)
                        ?: return@runInTransaction resultFailClosed()
                val status =
                    dao.status(delivery.event.snapshotId)
                        ?: return@runInTransaction resultFailClosed()
                val migration = dao.migrationState() ?: return@runInTransaction resultFailClosed()
                val anchors = dao.anchors(eventKey)
                val dispatches = dao.dispatches(delivery.event.snapshotId)
                val context =
                    try {
                        validateRows(delivery, dispatch, snapshot, status, migration, anchor)
                    } catch (_: IllegalArgumentException) {
                        return@runInTransaction resultFailClosed()
                    }
                check(context.anchor.matches(delivery))
                val deadlineDispatches =
                    try {
                        validateDeadlineAnchors(
                            delivery.event,
                            anchors,
                            requireCanonicalSet = true,
                        )
                        validateDeadlineDispatches(
                            delivery,
                            snapshot,
                            dispatches,
                            requireTerminalState = context.status.state in terminalStates(),
                        )
                    } catch (_: IllegalArgumentException) {
                        return@runInTransaction resultFailClosed()
                    }
                if (context.owner != WakeScheduleOwner.WAKE) {
                    return@runInTransaction resultFailClosed()
                }
                if (context.status.state in terminalStates()) {
                    return@runInTransaction result(
                        WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL
                    )
                }
                if (context.anchor.state != WakeRecoveryAnchorState.FIRED) {
                    return@runInTransaction resultFailClosed()
                }
                val deadline =
                    WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(
                        delivery.event.expectedTriggerEpochMillis
                    ) ?: return@runInTransaction resultFailClosed()
                if (delivery.receivedAtEpochMillis < deadline) {
                    return@runInTransaction result(
                        WakeRecoveryAnchorProcessingOutcome.OUT_OF_SCOPE_DEADLINE
                    )
                }
                if (status.executionEpoch == Long.MAX_VALUE)
                    return@runInTransaction resultFailClosed()
                val outboxRows =
                    try {
                        deadlineOutboxRows(
                            snapshot,
                            listOf(deadlineDispatches.start, deadlineDispatches.goal),
                            anchors,
                            delivery.receivedAtEpochMillis,
                        )
                    } catch (_: IllegalArgumentException) {
                        return@runInTransaction resultFailClosed()
                    }
                val expectedPoststate =
                    DeadlineExpectedPoststate(
                        prestate =
                            DeadlinePrestate(
                                snapshot = snapshot,
                                status = status,
                                currentAnchor = anchor,
                                dispatches = deadlineDispatches,
                                anchors = anchors,
                            ),
                        status =
                            status.copy(
                                state = WakeRunState.NO_CONFIRMATION.name,
                                activeServiceOwnerToken = null,
                                executionEpoch = status.executionEpoch + 1L,
                                serviceLeaseOwner = null,
                                serviceLeaseExpiresAt = null,
                                heartbeatAt = null,
                                armedStart = 0,
                                armedGoal = 0,
                                completedAt = delivery.receivedAtEpochMillis,
                                cancelledAt = null,
                                failureReason = WakeFailureReason.NO_CONFIRMATION_DEADLINE.name,
                            ),
                        dispatches =
                            DeadlineDispatches(
                                terminalDispatch(deadlineDispatches.start),
                                terminalDispatch(deadlineDispatches.goal),
                            ),
                        anchors = deadlineAnchorPostimages(anchors, delivery.kind),
                        outboxRows = outboxRows,
                    )

                faultHook("BEFORE_STATUS_CAS")
                val changed =
                    dao.compareAndSetStatusNoConfirmation(status, delivery.receivedAtEpochMillis)
                if (changed != 1) deadlineCasMiss(changed, delivery, expectedPoststate)
                faultHook("AFTER_STATUS_CAS")
                faultHook("BEFORE_START_DISPATCH")
                val startChanged =
                    dao.compareAndSetDispatchPostimage(
                        deadlineDispatches.start,
                        expectedPoststate.dispatches.start,
                    )
                if (startChanged != 1) deadlineCasMiss(startChanged, delivery, expectedPoststate)
                faultHook("AFTER_START_DISPATCH")
                faultHook("BEFORE_GOAL_DISPATCH")
                val goalChanged =
                    dao.compareAndSetDispatchPostimage(
                        deadlineDispatches.goal,
                        expectedPoststate.dispatches.goal,
                    )
                if (goalChanged != 1) deadlineCasMiss(goalChanged, delivery, expectedPoststate)
                faultHook("AFTER_GOAL_DISPATCH")
                faultHook("BEFORE_ANCHORS")
                anchors.forEach { sibling ->
                    val nextState =
                        expectedPoststate.anchors
                            .single { it.anchorKind == sibling.anchorKind }
                            .state
                    if (nextState != sibling.state) {
                        val anchorChanged = dao.compareAndSetAnchorState(sibling, nextState)
                        if (anchorChanged != 1) {
                            deadlineCasMiss(anchorChanged, delivery, expectedPoststate)
                        }
                    }
                }
                faultHook("AFTER_ANCHORS")
                outboxRows.forEachIndexed { index, row ->
                    faultHook("BEFORE_OUTBOX_INSERT_$index")
                    val inserted = dao.insertOutbox(row)
                    check(inserted != -1L || dao.outbox(row.id) == row) {
                        "Conflicting schedule outbox row"
                    }
                    faultHook("AFTER_OUTBOX_INSERT_$index")
                }
                faultHook("BEFORE_RETURN")
                result(WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION)
            }
        } catch (miss: DeadlineCasMiss) {
            return classifyDeadlineCasMiss(miss)
        }
    }

    fun processFired(
        delivery: WakeRecoveryAnchorDelivery,
        source: WakeDispatchSource,
        maxHeartbeatAgeMillis: Long,
    ): WakeRecoveryAnchorProcessingResult {
        if (
            source.receivedAt != delivery.receivedAtEpochMillis ||
                source.canonicalPendingIntentIdentity != delivery.pendingIntentIdentity
        ) {
            return resultFailClosed()
        }
        require(maxHeartbeatAgeMillis > 0L) { "Heartbeat age bound must be positive" }
        val canonicalSource =
            runCatching {
                    WakeDispatchAuthorizationFactory.canonicalSource(
                        delivery.event,
                        source.kind,
                        source.canonicalPendingIntentIdentity,
                        source.receivedAt,
                    )
                }
                .getOrNull() ?: return resultFailClosed()
        if (canonicalSource != source) return resultFailClosed()

        return database.runInTransaction<WakeRecoveryAnchorProcessingResult> {
            val dao = database.wakeRecoveryAnchorDao()
            val eventKey = delivery.event.canonicalKey()

            // Storage calls deliberately remain outside the row-domain validation catch.
            val dispatch = dao.dispatch(eventKey) ?: return@runInTransaction resultFailClosed()
            val snapshot =
                dao.snapshot(delivery.event.snapshotId)
                    ?: return@runInTransaction resultFailClosed()
            val status =
                dao.status(delivery.event.snapshotId) ?: return@runInTransaction resultFailClosed()
            val migration = dao.migrationState() ?: return@runInTransaction resultFailClosed()
            val anchor =
                dao.anchor(eventKey, delivery.kind.name)
                    ?: return@runInTransaction resultFailClosed()

            val context =
                try {
                    validateRows(delivery, dispatch, snapshot, status, migration, anchor)
                } catch (_: IllegalArgumentException) {
                    return@runInTransaction resultFailClosed()
                }
            if (migration.activeGeneration != snapshot.scheduleGeneration) {

                return@runInTransaction resultFailClosed()
            }

            val exactDelivery = context.anchor.matches(delivery)
            if (!exactDelivery) {
                return@runInTransaction result(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY)
            }
            val anchors = dao.anchors(eventKey)
            try {
                val anchorRows =
                    validateDeadlineAnchors(delivery.event, anchors, requireCanonicalSet = false)
                require(
                    anchorRows.any {
                        it.kind == context.anchor.kind &&
                            it.triggerEpochMillis == context.anchor.triggerEpochMillis &&
                            it.pendingIntentIdentity == context.anchor.pendingIntentIdentity
                    }
                )
            } catch (_: IllegalArgumentException) {

                return@runInTransaction resultFailClosed()
            }
            if (
                context.anchor.state == WakeRecoveryAnchorState.CONSUMED &&
                    delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY &&
                    dispatch.armedPrimary != 0
            ) {
                return@runInTransaction resultFailClosed()
            }
            if (context.status.state in terminalStates()) {
                return@runInTransaction result(WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL)
            }

            if (context.anchor.state == WakeRecoveryAnchorState.CONSUMED) {
                return@runInTransaction duplicateResult(
                    context.owner,
                    dispatch,
                    status,
                    delivery.receivedAtEpochMillis,
                    maxHeartbeatAgeMillis,
                )
            }
            if (context.anchor.state != WakeRecoveryAnchorState.FIRED) {
                return@runInTransaction result(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY)
            }

            val deadline =
                WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(
                    delivery.event.expectedTriggerEpochMillis
                ) ?: return@runInTransaction resultFailClosed()
            if (delivery.receivedAtEpochMillis >= deadline) {
                return@runInTransaction result(
                    WakeRecoveryAnchorProcessingOutcome.OUT_OF_SCOPE_DEADLINE
                )
            }
            if (dispatch.state == WakeDispatchState.TERMINAL.name) {
                return@runInTransaction resultFailClosed()
            }

            val decision =
                decide(
                    context.owner,
                    dispatch,
                    status,
                    delivery.receivedAtEpochMillis,
                    maxHeartbeatAgeMillis,
                )
            if (decision.outcome == WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED) {
                return@runInTransaction resultFailClosed()
            }

            var current = dispatch
            var authorization: WakeDispatchAuthorization? = null
            if (decision.mutation == DispatchMutation.REQUEST) {
                if (
                    current.dispatchAttemptId == Long.MAX_VALUE ||
                        current.attemptCount == Long.MAX_VALUE ||
                        delivery.receivedAtEpochMillis > Long.MAX_VALUE - DISPATCH_LEASE_MILLIS ||
                        (delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY &&
                            current.armedPrimary != 1)
                ) {
                    return@runInTransaction resultFailClosed()
                }
                authorization =
                    runCatching {
                            WakeDispatchAuthorizationFactory.create(
                                delivery.event,
                                snapshot.scheduleGeneration,
                                current.dispatchAttemptId + 1L,
                                status.executionEpoch,
                                delivery.receivedAtEpochMillis + DISPATCH_LEASE_MILLIS,
                                source,
                            )
                        }
                        .getOrNull() ?: return@runInTransaction resultFailClosed()
                faultHook("BEFORE_DISPATCH_CAS")
                val changed =
                    dao.compareAndSetDispatchRequest(
                        current,
                        authorization.leaseOwner,
                        authorization.leaseExpiresAt,
                        delivery.receivedAtEpochMillis,
                        clearPrimary = delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY,
                    )
                if (changed != 1) return@runInTransaction casResult(changed)
                faultHook("AFTER_DISPATCH_CAS")
                current = checkNotNull(dao.dispatch(eventKey))
                check(current.dispatchAttemptId == authorization.dispatchAttemptId)
                check(current.leaseOwner == authorization.leaseOwner)
                check(current.leaseExpiresAt == authorization.leaseExpiresAt)
                check(current.lastAttemptAt == authorization.requestedAt)
            } else if (decision.mutation == DispatchMutation.DEFER) {
                val changed = dao.compareAndSetReceivedToDeferred(current)
                if (changed != 1) return@runInTransaction casResult(changed)
                faultHook("AFTER_DISPATCH_CAS")
                current = checkNotNull(dao.dispatch(eventKey))
            }

            if (delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY && current.armedPrimary == 1) {
                val changed = dao.compareAndSetPrimaryClear(current)
                if (changed != 1) return@runInTransaction casResult(changed)
                faultHook("AFTER_PRIMARY_CLEAR_CAS")
                current = checkNotNull(dao.dispatch(eventKey))
            }
            if (delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY && current.armedPrimary != 0) {
                return@runInTransaction resultFailClosed()
            }

            val anchorChanged = dao.compareAndSetFiredToConsumed(anchor)
            if (anchorChanged != 1) return@runInTransaction casResult(anchorChanged)
            faultHook("AFTER_ANCHOR_CAS")
            faultHook("BEFORE_RETURN")

            if (decision.mutation == DispatchMutation.REQUEST) {
                val exactAuthorization = checkNotNull(authorization)
                return@runInTransaction WakeRecoveryAnchorProcessingResultFactory.create(
                    WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                    WakeRecoveryAnchorDispatchRequest(
                        eventKey,
                        current.dispatchAttemptId,
                        checkNotNull(current.leaseOwner),
                        checkNotNull(current.leaseExpiresAt),
                    ),
                    exactAuthorization,
                )
            }
            result(decision.outcome)
        }
    }

    private fun deadlineCasMiss(
        changed: Int,
        delivery: WakeRecoveryAnchorDelivery,
        expectedPoststate: DeadlineExpectedPoststate,
    ): Nothing {
        check(changed == 0) { "Room deadline CAS changed more than one row" }
        throw DeadlineCasMiss(delivery, expectedPoststate)
    }

    private fun classifyDeadlineCasMiss(miss: DeadlineCasMiss): WakeRecoveryAnchorProcessingResult {
        val poststate =
            database.runInTransaction<DeadlinePoststate> {
                val dao = database.wakeRecoveryAnchorDao()
                DeadlinePoststate(
                    status = dao.status(miss.delivery.event.snapshotId),
                    currentAnchor =
                        dao.anchor(
                            miss.delivery.event.canonicalKey(),
                            miss.delivery.kind.name,
                        ),
                    dispatches = dao.dispatches(miss.delivery.event.snapshotId),
                    anchors = dao.anchors(miss.delivery.event.canonicalKey()),
                    outboxRows = miss.expectedPoststate.outboxRows.map { dao.outbox(it.id) },
                )
            }
        val snapshot = miss.expectedPoststate.prestate.snapshot
        val status = poststate.status ?: return resultFailClosed()
        val currentAnchor = poststate.currentAnchor ?: return resultFailClosed()
        val pureStatus =
            try {
                snapshot.requireCanonicalFor(miss.delivery.event)
                require(status.snapshotId == snapshot.id)
                status.toPureWakeRecoveryRunStatus()
            } catch (_: IllegalArgumentException) {
                return resultFailClosed()
            }
        val dispatches =
            try {
                validateDeadlineAnchors(
                    miss.delivery.event,
                    poststate.anchors,
                    requireCanonicalSet = true,
                )
                require(
                    currentAnchor ==
                        poststate.anchors.single { it.anchorKind == miss.delivery.kind.name }
                )
                currentAnchor.toPureRow(miss.delivery.event)
                validateDeadlineDispatches(
                    miss.delivery,
                    snapshot,
                    poststate.dispatches,
                    requireTerminalState = pureStatus.state in terminalStates(),
                )
            } catch (_: IllegalArgumentException) {
                return resultFailClosed()
            }
        if (pureStatus.state in terminalStates()) {
            if (status != miss.expectedPoststate.status) return resultFailClosed()
            if (dispatches != miss.expectedPoststate.dispatches) return resultFailClosed()
            if (poststate.anchors != miss.expectedPoststate.anchors) return resultFailClosed()
            if (
                poststate.outboxRows.indices.any { index ->
                    poststate.outboxRows[index] != miss.expectedPoststate.outboxRows[index]
                }
            ) {
                return resultFailClosed()
            }
            return result(WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL)
        }
        if (
            pureStatus.state !in
                setOf(
                    WakeRunState.PREPARED,
                    WakeRunState.ACTIVE,
                    WakeRunState.GOAL_REACHED,
                )
        ) {
            return resultFailClosed()
        }
        val prestate = miss.expectedPoststate.prestate
        return if (
            status == prestate.status &&
                currentAnchor == prestate.currentAnchor &&
                dispatches == prestate.dispatches &&
                poststate.anchors == prestate.anchors &&
                poststate.outboxRows.all { it == null }
        ) {
            result(WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED)
        } else {
            resultFailClosed()
        }
    }

    private class DeadlineCasMiss(
        val delivery: WakeRecoveryAnchorDelivery,
        val expectedPoststate: DeadlineExpectedPoststate,
    ) : RuntimeException(null, null, false, false)

    private data class DeadlinePoststate(
        val status: WakeRunStatusEntity?,
        val currentAnchor: WakeRecoveryAnchorEntity?,
        val dispatches: List<WakeEventDispatchEntity>,
        val anchors: List<WakeRecoveryAnchorEntity>,
        val outboxRows: List<ScheduleOutboxEntity?>,
    )

    private data class DeadlinePrestate(
        val snapshot: WakeRunSnapshotEntity,
        val status: WakeRunStatusEntity,
        val currentAnchor: WakeRecoveryAnchorEntity,
        val dispatches: DeadlineDispatches,
        val anchors: List<WakeRecoveryAnchorEntity>,
    )

    private data class DeadlineExpectedPoststate(
        val prestate: DeadlinePrestate,
        val status: WakeRunStatusEntity,
        val dispatches: DeadlineDispatches,
        val anchors: List<WakeRecoveryAnchorEntity>,
        val outboxRows: List<ScheduleOutboxEntity>,
    )

    private fun validateRows(
        delivery: WakeRecoveryAnchorDelivery,
        dispatch: WakeEventDispatchEntity,
        snapshot: WakeRunSnapshotEntity,
        status: WakeRunStatusEntity,
        migration: MigrationStateEntity,
        anchor: WakeRecoveryAnchorEntity,
    ): ValidatedContext {
        val event = delivery.event
        validateDispatchRow(event, dispatch, requireCanonicalLeaseOwner = false)

        snapshot.requireCanonicalFor(event)

        require(status.snapshotId == snapshot.id)
        val canonicalStatus = status.toPureWakeRecoveryRunStatus()

        require(migration.id == 1)
        val owner = WakeScheduleOwner.valueOf(migration.scheduleOwner)
        require(WakeRecoveryAnchorKind.valueOf(anchor.anchorKind) == delivery.kind)
        return ValidatedContext(owner, canonicalStatus, anchor.toPureRow(event))
    }

    private fun validateDeadlineAnchors(
        event: WakeEventIdentity,
        anchors: List<WakeRecoveryAnchorEntity>,
        requireCanonicalSet: Boolean,
    ): List<WakeRecoveryAnchorRow> {
        val rows = anchors.map { it.toPureRow(event) }
        require(rows.map { it.kind }.toSet().size == rows.size)
        require(rows.map { it.pendingIntentIdentity }.toSet().size == rows.size)
        if (requireCanonicalSet) {
            require(rows.size == WakeRecoveryAnchorKind.entries.size)
            require(rows.map { it.kind }.toSet() == WakeRecoveryAnchorKind.entries.toSet())
        }
        return rows
    }

    private fun validateDeadlineDispatches(
        delivery: WakeRecoveryAnchorDelivery,
        snapshot: WakeRunSnapshotEntity,
        rows: List<WakeEventDispatchEntity>,
        requireTerminalState: Boolean,
    ): DeadlineDispatches {
        val startEvent =
            WakeEventIdentity(snapshot.id, WakeEventKind.START, snapshot.wakeStartEpochMs)
        require(rows.size == 2)
        val rowsByKind =
            rows
                .associateBy { row -> WakeEventKind.valueOf(row.eventKind) }
                .also {
                    require(it.size == 2)
                }
        val startDispatch = requireNotNull(rowsByKind[WakeEventKind.START])
        val goalDispatch = requireNotNull(rowsByKind[WakeEventKind.GOAL])
        val startState =
            validateDispatchRow(startEvent, startDispatch, requireCanonicalLeaseOwner = true)
        val goalState =
            validateDispatchRow(
                delivery.event,
                goalDispatch,
                requireCanonicalLeaseOwner = true,
            )
        if (requireTerminalState) {
            require(startState == WakeDispatchState.TERMINAL)
            require(goalState == WakeDispatchState.TERMINAL)
        } else {
            require(startState != WakeDispatchState.TERMINAL)
            require(goalState != WakeDispatchState.TERMINAL)
        }
        return DeadlineDispatches(startDispatch, goalDispatch)
    }

    private fun validateDispatchRow(
        event: WakeEventIdentity,
        row: WakeEventDispatchEntity,
        requireCanonicalLeaseOwner: Boolean,
    ): WakeDispatchState {
        require(row.eventKey == event.canonicalKey())
        require(row.snapshotId == event.snapshotId)
        require(WakeEventKind.valueOf(row.eventKind) == event.kind)
        require(row.expectedTriggerEpochMs == event.expectedTriggerEpochMillis)
        require(row.expectedTriggerEpochMs >= 0L)
        val state = WakeDispatchState.valueOf(row.state)
        require(row.dispatchAttemptId >= 0L && row.attemptCount >= 0L)
        require(row.armedPrimary in 0..1)
        requireNonNegative(row.leaseExpiresAt, row.lastAttemptAt)
        if (requireCanonicalLeaseOwner) {
            require((row.leaseOwner == null) == (row.leaseExpiresAt == null))
            require(row.leaseOwner == null || row.leaseOwner.isValidOwnerToken())
            when (state) {
                WakeDispatchState.DISPATCH_REQUESTED,
                WakeDispatchState.SERVICE_ACKED -> require(row.leaseOwner != null)
                WakeDispatchState.RECEIVED,
                WakeDispatchState.DEFERRED,
                WakeDispatchState.TERMINAL -> require(row.leaseOwner == null)
            }
        }
        if (state == WakeDispatchState.TERMINAL) require(row.armedPrimary == 0)
        validateSlot(row.recoverySlotAState, row.recoverySlotAAt, row.recoverySlotAToken)
        validateSlot(row.recoverySlotBState, row.recoverySlotBAt, row.recoverySlotBToken)
        if (state == WakeDispatchState.TERMINAL) {
            require(
                row.recoverySlotAState == WakeRecoverySlotState.CONSUMED.name ||
                    row.recoverySlotAState == WakeRecoverySlotState.CANCELLED.name
            )
            require(row.recoverySlotAAt == null)
            require(
                row.recoverySlotBState == WakeRecoverySlotState.CONSUMED.name ||
                    row.recoverySlotBState == WakeRecoverySlotState.CANCELLED.name
            )
            require(row.recoverySlotBAt == null)
        }
        return state
    }

    private fun terminalDispatch(row: WakeEventDispatchEntity): WakeEventDispatchEntity =
        row.copy(
            state = WakeDispatchState.TERMINAL.name,
            leaseOwner = null,
            leaseExpiresAt = null,
            failureReason = "NO_CONFIRMATION_DEADLINE",
            armedPrimary = 0,
            recoverySlotAAt = null,
            recoverySlotAState = terminalSlotState(row.recoverySlotAState),
            recoverySlotBAt = null,
            recoverySlotBState = terminalSlotState(row.recoverySlotBState),
        )

    private fun terminalSlotState(state: String): String =
        if (state == WakeRecoverySlotState.CONSUMED.name) {
            WakeRecoverySlotState.CONSUMED.name
        } else {
            WakeRecoverySlotState.CANCELLED.name
        }

    private fun deadlineOutboxRows(
        snapshot: WakeRunSnapshotEntity,
        dispatches: List<WakeEventDispatchEntity>,
        anchors: List<WakeRecoveryAnchorEntity>,
        now: Long,
    ): List<ScheduleOutboxEntity> {
        val rows = mutableListOf<ScheduleOutboxEntity>()
        val goalPrimary = anchors.single {
            it.anchorKind == WakeRecoveryAnchorKind.GOAL_PRIMARY.name
        }
        dispatches.forEach { dispatch ->
            val isGoal = dispatch.eventKind == WakeEventKind.GOAL.name
            val hasArmedPrimary =
                if (isGoal) {
                    dispatch.armedPrimary == 1 &&
                        goalPrimary.state == WakeRecoveryAnchorState.ARMED.name
                } else {
                    dispatch.armedPrimary == 1
                }
            if (hasArmedPrimary) {
                rows +=
                    cancellationOutbox(
                        snapshot.scheduleGeneration,
                        "CANCEL_PRIMARY",
                        dispatch.eventKey,
                        "${dispatch.eventKind}_PRIMARY",
                        "PRIMARY",
                        dispatch.expectedTriggerEpochMs,
                        if (isGoal) goalPrimary.pendingIntentIdentity else dispatch.eventKey,
                        null,
                        now,
                    )
            }
            listOf(
                    Triple("A", dispatch.recoverySlotAState, dispatch.recoverySlotAAt) to
                        dispatch.recoverySlotAToken,
                    Triple("B", dispatch.recoverySlotBState, dispatch.recoverySlotBAt) to
                        dispatch.recoverySlotBToken,
                )
                .forEach { (slotFields, token) ->
                    val (slot, state, trigger) = slotFields
                    if (state == WakeRecoverySlotState.ARMED.name) {
                        rows +=
                            cancellationOutbox(
                                snapshot.scheduleGeneration,
                                "CANCEL_RECOVERY",
                                dispatch.eventKey,
                                "DYNAMIC",
                                slot,
                                requireNotNull(trigger),
                                dispatch.eventKey,
                                token,
                                now,
                            )
                    }
                }
        }
        anchors
            .filter {
                it.state == WakeRecoveryAnchorState.ARMED.name &&
                    it.anchorKind != WakeRecoveryAnchorKind.GOAL_PRIMARY.name
            }
            .forEach { anchor ->
                rows +=
                    cancellationOutbox(
                        snapshot.scheduleGeneration,
                        "CANCEL_RECOVERY",
                        anchor.eventKey,
                        anchor.anchorKind,
                        "IMMUTABLE",
                        anchor.triggerEpochMs,
                        anchor.pendingIntentIdentity,
                        null,
                        now,
                    )
            }
        rows += createNextOutbox(snapshot, now)
        check(rows.map { it.id }.toSet().size == rows.size) { "Duplicate schedule outbox target" }
        return rows
    }

    private fun cancellationOutbox(
        generation: Long,
        command: String,
        eventKey: String,
        targetKind: String,
        targetSlot: String,
        trigger: Long,
        pendingIntentIdentity: String,
        token: Long?,
        now: Long,
    ): ScheduleOutboxEntity {
        require(generation >= 0L && trigger >= 0L && now >= 0L)
        val id =
            ScheduleOutboxCanonicalizer.id(
                "command" to command,
                "generation" to generation.toString(),
                "event" to eventKey,
                "target_kind" to targetKind,
                "target_slot" to targetSlot,
                "token" to (token?.toString() ?: "-"),
                "trigger" to trigger.toString(),
                "pi_utf8_hex" to ScheduleOutboxCanonicalizer.hexUtf8(pendingIntentIdentity),
            )
        return pendingOutbox(id, generation, command, eventKey, now)
    }

    private fun createNextOutbox(
        snapshot: WakeRunSnapshotEntity,
        now: Long,
    ): ScheduleOutboxEntity {
        require(snapshot.scheduleGeneration >= 0L && now >= 0L)
        val id =
            ScheduleOutboxCanonicalizer.id(
                "command" to "CREATE_NEXT",
                "generation" to snapshot.scheduleGeneration.toString(),
                "snapshot_utf8_hex" to ScheduleOutboxCanonicalizer.hexUtf8(snapshot.id),
                "occurrence_utf8_hex" to ScheduleOutboxCanonicalizer.hexUtf8(snapshot.occurrenceId),
            )
        return pendingOutbox(id, snapshot.scheduleGeneration, "CREATE_NEXT", null, now)
    }

    private fun pendingOutbox(
        id: String,
        generation: Long,
        command: String,
        eventKey: String?,
        now: Long,
    ) =
        ScheduleOutboxEntity(
            id = id,
            generation = generation,
            command = command,
            eventKey = eventKey,
            state = "PENDING",
            attemptCount = 0L,
            notBeforeEpochMs = now,
            createdAt = now,
            lastError = null,
        )

    private fun WakeRecoveryAnchorRow.matches(delivery: WakeRecoveryAnchorDelivery): Boolean =
        event == delivery.event &&
            kind == delivery.kind &&
            triggerEpochMillis == delivery.triggerEpochMillis &&
            pendingIntentIdentity == delivery.pendingIntentIdentity &&
            delivery.receivedAtEpochMillis >= delivery.triggerEpochMillis

    private fun decide(
        owner: WakeScheduleOwner,
        dispatch: WakeEventDispatchEntity,
        status: WakeRunStatusEntity,
        now: Long,
        maxHeartbeatAgeMillis: Long,
    ): Decision {
        val state = WakeDispatchState.valueOf(dispatch.state)
        if (owner == WakeScheduleOwner.LEGACY || owner == WakeScheduleOwner.RESTORING) {
            return Decision(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)
        }
        val activeRequest = hasActiveDispatchRequest(dispatch, now)
        val healthyAck = hasHealthyServiceAck(dispatch, status, now, maxHeartbeatAgeMillis)
        if (owner == WakeScheduleOwner.PREPARING_WAKE) {
            return when (state) {
                WakeDispatchState.RECEIVED ->
                    Decision(
                        WakeRecoveryAnchorProcessingOutcome.DEFERRED_DURABLE,
                        DispatchMutation.DEFER,
                    )
                WakeDispatchState.DEFERRED ->
                    Decision(WakeRecoveryAnchorProcessingOutcome.DEFERRED_DURABLE)
                WakeDispatchState.DISPATCH_REQUESTED ->
                    if (activeRequest) {
                        Decision(WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST)
                    } else {
                        Decision(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)
                    }
                WakeDispatchState.SERVICE_ACKED ->
                    if (healthyAck) {
                        Decision(WakeRecoveryAnchorProcessingOutcome.HEALTHY_EXECUTION)
                    } else {
                        Decision(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)
                    }
                WakeDispatchState.TERMINAL ->
                    Decision(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)
            }
        }
        return when (state) {
            WakeDispatchState.RECEIVED,
            WakeDispatchState.DEFERRED ->
                Decision(
                    WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                    DispatchMutation.REQUEST,
                )
            WakeDispatchState.DISPATCH_REQUESTED ->
                if (activeRequest) {
                    Decision(WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST)
                } else {
                    Decision(
                        WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                        DispatchMutation.REQUEST,
                    )
                }
            WakeDispatchState.SERVICE_ACKED ->
                if (healthyAck) {
                    Decision(WakeRecoveryAnchorProcessingOutcome.HEALTHY_EXECUTION)
                } else {
                    Decision(
                        WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                        DispatchMutation.REQUEST,
                    )
                }
            WakeDispatchState.TERMINAL -> Decision(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)
        }
    }

    private fun duplicateResult(
        owner: WakeScheduleOwner,
        dispatch: WakeEventDispatchEntity,
        status: WakeRunStatusEntity,
        now: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeRecoveryAnchorProcessingResult {
        val decision = decide(owner, dispatch, status, now, maxHeartbeatAgeMillis)
        return when {
            decision.mutation != DispatchMutation.NONE ->
                result(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY)
            decision.outcome == WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED ->
                resultFailClosed()
            else -> result(decision.outcome)
        }
    }

    private fun hasActiveDispatchRequest(row: WakeEventDispatchEntity, now: Long): Boolean =
        row.leaseOwner?.isValidOwnerToken() == true &&
            row.leaseExpiresAt != null &&
            row.leaseExpiresAt > now

    private fun hasHealthyServiceAck(
        dispatch: WakeEventDispatchEntity,
        status: WakeRunStatusEntity,
        now: Long,
        maxHeartbeatAgeMillis: Long,
    ): Boolean {
        val owner = dispatch.leaseOwner ?: return false
        val serviceExpiry = status.serviceLeaseExpiresAt ?: return false
        val heartbeat = status.heartbeatAt ?: return false
        return owner.isValidOwnerToken() &&
            status.executionEpoch > 0L &&
            status.activeServiceOwnerToken == owner &&
            status.serviceLeaseOwner == owner &&
            serviceExpiry > now &&
            heartbeat <= now &&
            now - heartbeat <= maxHeartbeatAgeMillis
    }

    private fun validateSlot(state: String, trigger: Long?, token: Long) {
        val parsed = WakeRecoverySlotState.valueOf(state)
        require(token >= 0L)
        require(trigger == null || trigger >= 0L)
        require((parsed in liveSlotStates()) == (trigger != null))
    }

    private fun requireNonNegative(vararg values: Long?) {
        require(values.all { it == null || it >= 0L })
    }

    private fun WakeRecoveryAnchorEntity.toPureRow(
        event: WakeEventIdentity
    ): WakeRecoveryAnchorRow {
        require(eventKey == event.canonicalKey())
        val kind = WakeRecoveryAnchorKind.valueOf(anchorKind)
        return WakeRecoveryAnchorRow(
            event = event,
            kind = kind,
            triggerEpochMillis = triggerEpochMs,
            state = WakeRecoveryAnchorState.valueOf(state),
            pendingIntentIdentity = pendingIntentIdentity,
        )
    }

    private fun deadlineAnchorPostimages(
        anchors: List<WakeRecoveryAnchorEntity>,
        currentKind: WakeRecoveryAnchorKind,
    ): List<WakeRecoveryAnchorEntity> = anchors.map { anchor ->
        anchor.copy(
            state =
                if (anchor.anchorKind == currentKind.name) {
                    WakeRecoveryAnchorState.CONSUMED.name
                } else if (
                    anchor.state == WakeRecoveryAnchorState.ARMED.name ||
                        anchor.state == WakeRecoveryAnchorState.FIRED.name
                ) {
                    WakeRecoveryAnchorState.CANCELLED.name
                } else {
                    anchor.state
                }
        )
    }

    private fun casResult(changed: Int): WakeRecoveryAnchorProcessingResult {
        check(changed == 0) { "Room processing CAS changed more than one row" }
        return result(WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED)
    }

    private fun resultFailClosed() = result(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)

    private fun result(outcome: WakeRecoveryAnchorProcessingOutcome) =
        WakeRecoveryAnchorProcessingResultFactory.create(outcome, null, null)

    private data class ValidatedContext(
        val owner: WakeScheduleOwner,
        val status: WakeRecoveryRunStatus,
        val anchor: WakeRecoveryAnchorRow,
    )

    private data class DeadlineDispatches(
        val start: WakeEventDispatchEntity,
        val goal: WakeEventDispatchEntity,
    )

    private data class Decision(
        val outcome: WakeRecoveryAnchorProcessingOutcome,
        val mutation: DispatchMutation = DispatchMutation.NONE,
    )

    private enum class DispatchMutation {
        NONE,
        REQUEST,
        DEFER,
    }
}

private fun terminalStates(): Set<WakeRunState> =
    setOf(
        WakeRunState.COMPLETED,
        WakeRunState.NO_CONFIRMATION,
        WakeRunState.FAILED,
        WakeRunState.CANCELLED,
        WakeRunState.SUPERSEDED,
        WakeRunState.EXPIRED,
    )

private fun liveSlotStates(): Set<WakeRecoverySlotState> =
    setOf(
        WakeRecoverySlotState.ARMED,
        WakeRecoverySlotState.FIRED,
        WakeRecoverySlotState.IN_FLIGHT,
    )
