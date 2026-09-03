/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeDispatchState
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorState
import com.dsalmun.luxalarm.wake.WakeRecoverySlotState
import com.dsalmun.luxalarm.wake.WakeRunState
import com.dsalmun.luxalarm.wake.WakeScheduleOwner
import com.dsalmun.luxalarm.wake.isValidOwnerToken

internal enum class WakeRecoveryAnchorProcessingOutcome {
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

internal data class WakeRecoveryAnchorProcessingResult(
    val outcome: WakeRecoveryAnchorProcessingOutcome,
    val dispatchRequest: WakeRecoveryAnchorDispatchRequest? = null,
) {
    val recommendation: WakeRecoveryAnchorProcessingRecommendation =
        if (outcome == WakeRecoveryAnchorProcessingOutcome.OUT_OF_SCOPE_DEADLINE) {
            WakeRecoveryAnchorProcessingRecommendation.DEFER_TO_TERMINAL
        } else {
            WakeRecoveryAnchorProcessingRecommendation.NONE
        }

    init {
        require(
            (outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST) ==
                (dispatchRequest != null)
        ) {
            "Only a new dispatch outcome carries exactly one dispatch request"
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

    fun processFired(
        delivery: WakeRecoveryAnchorDelivery,
        proposedDispatchLeaseOwner: String,
        proposedDispatchLeaseExpiresAtEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeRecoveryAnchorProcessingResult {
        require(proposedDispatchLeaseOwner.isValidOwnerToken()) {
            "Proposed dispatch lease owner is invalid"
        }
        require(proposedDispatchLeaseExpiresAtEpochMillis > delivery.receivedAtEpochMillis) {
            "Proposed dispatch lease must expire after receipt"
        }
        require(maxHeartbeatAgeMillis > 0L) { "Heartbeat age bound must be positive" }

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

            val exactDelivery = anchor.matches(delivery)
            if (!exactDelivery) {
                return@runInTransaction result(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY)
            }
            if (
                context.anchorState == WakeRecoveryAnchorState.CONSUMED &&
                    delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY &&
                    dispatch.armedPrimary != 0
            ) {
                return@runInTransaction resultFailClosed()
            }
            if (context.statusState in terminalStates()) {
                return@runInTransaction result(WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL)
            }

            if (context.anchorState == WakeRecoveryAnchorState.CONSUMED) {
                return@runInTransaction duplicateResult(
                    context.owner,
                    dispatch,
                    status,
                    delivery.receivedAtEpochMillis,
                    maxHeartbeatAgeMillis,
                )
            }
            if (context.anchorState != WakeRecoveryAnchorState.FIRED) {
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
            if (decision.mutation == DispatchMutation.REQUEST) {
                if (
                    current.dispatchAttemptId == Long.MAX_VALUE ||
                        current.attemptCount == Long.MAX_VALUE ||
                        (delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY &&
                            current.armedPrimary != 1)
                ) {
                    return@runInTransaction resultFailClosed()
                }
                faultHook("BEFORE_DISPATCH_CAS")
                val changed =
                    dao.compareAndSetDispatchRequest(
                        current,
                        proposedDispatchLeaseOwner,
                        proposedDispatchLeaseExpiresAtEpochMillis,
                        delivery.receivedAtEpochMillis,
                        clearPrimary = delivery.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY,
                    )
                if (changed != 1) return@runInTransaction casResult(changed)
                faultHook("AFTER_DISPATCH_CAS")
                current = checkNotNull(dao.dispatch(eventKey))
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

            if (decision.mutation == DispatchMutation.REQUEST) {
                val applied = current
                return@runInTransaction WakeRecoveryAnchorProcessingResult(
                    WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                    WakeRecoveryAnchorDispatchRequest(
                        eventKey,
                        applied.dispatchAttemptId,
                        checkNotNull(applied.leaseOwner),
                        checkNotNull(applied.leaseExpiresAt),
                    ),
                )
            }
            result(decision.outcome)
        }
    }

    private fun validateRows(
        delivery: WakeRecoveryAnchorDelivery,
        dispatch: WakeEventDispatchEntity,
        snapshot: WakeRunSnapshotEntity,
        status: WakeRunStatusEntity,
        migration: MigrationStateEntity,
        anchor: WakeRecoveryAnchorEntity,
    ): ValidatedContext {
        val event = delivery.event
        require(dispatch.eventKey == event.canonicalKey())
        require(dispatch.snapshotId == event.snapshotId)
        require(WakeEventKind.valueOf(dispatch.eventKind) == WakeEventKind.GOAL)
        require(dispatch.expectedTriggerEpochMs == event.expectedTriggerEpochMillis)
        require(dispatch.expectedTriggerEpochMs >= 0L)
        WakeDispatchState.valueOf(dispatch.state)
        require(dispatch.dispatchAttemptId >= 0L && dispatch.attemptCount >= 0L)
        require(dispatch.armedPrimary in 0..1)
        requireNonNegative(dispatch.leaseExpiresAt, dispatch.lastAttemptAt)
        require((dispatch.leaseOwner == null) == (dispatch.leaseExpiresAt == null))
        validateSlot(
            dispatch.recoverySlotAState,
            dispatch.recoverySlotAAt,
            dispatch.recoverySlotAToken,
        )
        validateSlot(
            dispatch.recoverySlotBState,
            dispatch.recoverySlotBAt,
            dispatch.recoverySlotBToken,
        )

        require(snapshot.id == event.snapshotId)
        require(snapshot.goalEpochMs == event.expectedTriggerEpochMillis)
        require(snapshot.scheduleGeneration >= 0L)
        require(snapshot.routineRevision >= 0L && snapshot.calculationRuleVersion >= 0L)
        require(snapshot.wakeStartEpochMs >= 0L && snapshot.goalEpochMs >= 0L)
        require(snapshot.createdAt >= 0L)

        require(status.snapshotId == snapshot.id)
        val statusState = WakeRunState.valueOf(status.state)
        require(status.executionEpoch >= 0L)
        require(status.armedStart in 0..1 && status.armedGoal in 0..1)
        requireNonNegative(
            status.processedStartAt,
            status.processedGoalAt,
            status.serviceLeaseExpiresAt,
            status.heartbeatAt,
            status.startedAt,
            status.completedAt,
            status.cancelledAt,
        )
        require((status.serviceLeaseOwner == null) == (status.serviceLeaseExpiresAt == null))
        require(status.heartbeatAt == null || status.serviceLeaseOwner != null)
        listOf(status.activeServiceOwnerToken, status.serviceLeaseOwner).forEach { owner ->
            require(owner == null || owner.isValidOwnerToken())
        }
        if (statusState in terminalStates()) {
            require(status.activeServiceOwnerToken == null)
            require(status.serviceLeaseOwner == null && status.serviceLeaseExpiresAt == null)
            require(status.heartbeatAt == null)
            require(status.armedStart == 0 && status.armedGoal == 0)
        } else {
            require(status.completedAt == null && status.cancelledAt == null)
            require(status.failureReason == null)
        }

        require(migration.id == 1)
        val owner = WakeScheduleOwner.valueOf(migration.scheduleOwner)
        val storedKind = WakeRecoveryAnchorKind.valueOf(anchor.anchorKind)
        val anchorState = WakeRecoveryAnchorState.valueOf(anchor.state)
        require(anchor.eventKey == event.canonicalKey())
        require(storedKind == delivery.kind)
        require(anchor.triggerEpochMs >= 0L)
        require(
            anchor.triggerEpochMs ==
                requireNotNull(storedKind.triggerForGoalOrNull(event.expectedTriggerEpochMillis))
        )
        require(anchor.pendingIntentIdentity.isNotEmpty())
        return ValidatedContext(owner, statusState, anchorState)
    }

    private fun WakeRecoveryAnchorEntity.matches(delivery: WakeRecoveryAnchorDelivery): Boolean =
        eventKey == delivery.event.canonicalKey() &&
            anchorKind == delivery.kind.name &&
            triggerEpochMs == delivery.triggerEpochMillis &&
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

    private fun casResult(changed: Int): WakeRecoveryAnchorProcessingResult {
        check(changed == 0) { "Room processing CAS changed more than one row" }
        return result(WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED)
    }

    private fun resultFailClosed() = result(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED)

    private fun result(outcome: WakeRecoveryAnchorProcessingOutcome) =
        WakeRecoveryAnchorProcessingResult(outcome)

    private data class ValidatedContext(
        val owner: WakeScheduleOwner,
        val statusState: WakeRunState,
        val anchorState: WakeRecoveryAnchorState,
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
