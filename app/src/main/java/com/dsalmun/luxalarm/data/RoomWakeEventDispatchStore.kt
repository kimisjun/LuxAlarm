/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeDispatchAction
import com.dsalmun.luxalarm.wake.WakeDispatchInput
import com.dsalmun.luxalarm.wake.WakeDispatchReducer
import com.dsalmun.luxalarm.wake.WakeDispatchState
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeRecoverySlot
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import com.dsalmun.luxalarm.wake.WakeRecoverySlotState
import com.dsalmun.luxalarm.wake.WakeScheduleOwner

/** Immutable identity supplied by the alarm delivery boundary. */
internal sealed interface WakeEventArrival {
    data object Primary : WakeEventArrival

    data class Recovery(
        val slot: WakeRecoverySlotId,
        val deliveredToken: Long,
    ) : WakeEventArrival
}

internal enum class WakeEventStoreOutcome {
    APPLIED,
    CONVERGED,
    NO_OP_TERMINAL,
    NO_OP_ACTIVE_DISPATCH,
    NO_OP_HEALTHY_ACK,
    FAIL_CLOSED,
    STALE_DELIVERY,
    STALE_RETRY_REQUIRED,
}

internal enum class WakeEventConvergence {
    ALREADY_CONSUMED
}

internal data class WakeEventStoreResult(
    val outcome: WakeEventStoreOutcome,
    val dispatch: WakeEventDispatchEntity? = null,
    val convergence: WakeEventConvergence? = null,
)

/**
 * Transactional adapter from durable V6 rows to the pure, slot-based wake-event reducer.
 *
 * The reducer intentionally remains slot-based. This boundary authenticates the immutable recovery
 * delivery token before exposing only its slot to the reducer.
 */
internal class RoomWakeEventDispatchStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun reduce(
        event: WakeEventIdentity,
        arrival: WakeEventArrival,
        nowEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeEventStoreResult {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        require(maxHeartbeatAgeMillis > 0L) { "Heartbeat age bound must be positive" }
        return database.runInTransaction<WakeEventStoreResult> {
            val dao = database.wakeEventDispatchDao()
            val initial =
                dao.dispatch(event.canonicalKey())
                    ?: return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.FAIL_CLOSED
                    )
            val baseContext =
                loadContext(
                    dao,
                    event,
                    initial,
                    arrivingSlot = null,
                    nowEpochMillis,
                    maxHeartbeatAgeMillis,
                )
                    ?: return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.FAIL_CLOSED,
                        initial,
                    )
            when (val gate = authenticateArrival(initial, baseContext, arrival)) {
                ArrivalGate.ALREADY_CONSUMED ->
                    return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.CONVERGED,
                        initial,
                        WakeEventConvergence.ALREADY_CONSUMED,
                    )
                ArrivalGate.STALE_DELIVERY ->
                    return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.STALE_DELIVERY,
                        initial,
                    )
                ArrivalGate.FAIL_CLOSED ->
                    return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.FAIL_CLOSED,
                        initial,
                    )
                ArrivalGate.VALID -> Unit
            }

            val context = baseContext.copy(arrivingSlot = arrival.recoverySlotOrNull())
            val reduction = WakeDispatchReducer.reduce(context)
            val transition = reduction.transition
            if (transition == null) {
                return@runInTransaction WakeEventStoreResult(
                    reduction.action.toStoreOutcome(),
                    initial,
                )
            }
            if (
                reduction.action == WakeDispatchAction.REQUEST_DISPATCH &&
                    initial.attemptCount == Long.MAX_VALUE
            ) {
                return@runInTransaction WakeEventStoreResult(
                    WakeEventStoreOutcome.FAIL_CLOSED,
                    initial,
                )
            }
            faultHook(BEFORE_CAS)
            val changed =
                dao.compareAndSet(
                    eventKey = transition.expectedEventKey,
                    expectedState = transition.expectedState.name,
                    expectedDispatchAttemptId = transition.expectedDispatchAttemptId,
                    primaryArrival = arrival is WakeEventArrival.Primary,
                    arrivingSlot = transition.expectedRecoverySlot?.name,
                    expectedSlotState = transition.expectedRecoverySlotState?.name,
                    expectedSlotTrigger = transition.expectedRecoveryTriggerAtEpochMillis,
                    expectedSlotToken = transition.expectedRecoverySlotToken,
                    nextState = transition.nextState.name,
                    nextDispatchAttemptId = transition.nextDispatchAttemptId,
                    nextSlotState = transition.nextRecoverySlotState?.name,
                    nextSlotTrigger = transition.nextRecoveryTriggerAtEpochMillis,
                    nextSlotToken = transition.nextRecoverySlotToken,
                    requestDispatch = reduction.action == WakeDispatchAction.REQUEST_DISPATCH,
                    nowEpochMillis = nowEpochMillis,
                )
            if (changed == 1) {
                faultHook(AFTER_CAS)
                val applied = checkNotNull(dao.dispatch(event.canonicalKey()))
                return@runInTransaction WakeEventStoreResult(WakeEventStoreOutcome.APPLIED, applied)
            }
            check(changed == 0) { "Room CAS changed more than one dispatch row" }

            // Room serializes this store's read and write in one transaction, so ordinary store
            // callers cannot deterministically produce CAS=0 between them. A zero count is handled
            // with one bounded reread, never a second mutation or inferred full-postimage match.
            val current =
                dao.dispatch(event.canonicalKey())
                    ?: return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.FAIL_CLOSED
                    )
            val currentContext =
                loadContext(
                    dao,
                    event,
                    current,
                    arrivingSlot = null,
                    nowEpochMillis,
                    maxHeartbeatAgeMillis,
                )
                    ?: return@runInTransaction WakeEventStoreResult(
                        WakeEventStoreOutcome.FAIL_CLOSED,
                        current,
                    )
            return@runInTransaction when (authenticateArrival(current, currentContext, arrival)) {
                ArrivalGate.STALE_DELIVERY ->
                    WakeEventStoreResult(WakeEventStoreOutcome.STALE_DELIVERY, current)
                ArrivalGate.FAIL_CLOSED ->
                    WakeEventStoreResult(WakeEventStoreOutcome.FAIL_CLOSED, current)
                ArrivalGate.ALREADY_CONSUMED ->
                    WakeEventStoreResult(
                        WakeEventStoreOutcome.CONVERGED,
                        current,
                        WakeEventConvergence.ALREADY_CONSUMED,
                    )
                ArrivalGate.VALID ->
                    WakeEventStoreResult(WakeEventStoreOutcome.STALE_RETRY_REQUIRED, current)
            }
        }
    }

    private fun authenticateArrival(
        row: WakeEventDispatchEntity,
        input: WakeDispatchInput,
        arrival: WakeEventArrival,
    ): ArrivalGate =
        when (arrival) {
            WakeEventArrival.Primary ->
                when (row.armedPrimary) {
                    1 -> ArrivalGate.VALID
                    0 -> ArrivalGate.ALREADY_CONSUMED
                    else -> ArrivalGate.FAIL_CLOSED
                }
            is WakeEventArrival.Recovery -> {
                val delivered = arrival.deliveredToken
                if (delivered < 0L || delivered == Long.MAX_VALUE) return ArrivalGate.FAIL_CLOSED
                val slot =
                    when (arrival.slot) {
                        WakeRecoverySlotId.A -> input.slotA
                        WakeRecoverySlotId.B -> input.slotB
                    }
                when {
                    slot.token == delivered ->
                        if (
                            slot.state in RECOVERY_DELIVERABLE_STATES &&
                                slot.triggerAtEpochMillis == input.event.expectedTriggerEpochMillis
                        ) {
                            ArrivalGate.VALID
                        } else {
                            ArrivalGate.FAIL_CLOSED
                        }
                    slot.token == delivered + 1L &&
                        slot.state == WakeRecoverySlotState.CONSUMED &&
                        slot.triggerAtEpochMillis == null -> ArrivalGate.ALREADY_CONSUMED
                    slot.token > delivered -> ArrivalGate.STALE_DELIVERY
                    else -> ArrivalGate.FAIL_CLOSED
                }
            }
        }

    private fun loadContext(
        dao: WakeEventDispatchDao,
        event: WakeEventIdentity,
        dispatch: WakeEventDispatchEntity,
        arrivingSlot: WakeRecoverySlotId?,
        nowEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeDispatchInput? =
        runCatching {
                check(dispatch.eventKey == event.canonicalKey())
                check(dispatch.snapshotId == event.snapshotId)
                check(dispatch.eventKind == event.kind.name)
                check(dispatch.expectedTriggerEpochMs == event.expectedTriggerEpochMillis)
                check(dispatch.expectedTriggerEpochMs >= 0L)
                WakeDispatchState.valueOf(dispatch.state)
                check(dispatch.dispatchAttemptId >= 0L)
                check(dispatch.attemptCount >= 0L)
                check(dispatch.armedPrimary in 0..1)
                checkNonNegative(dispatch.leaseExpiresAt, dispatch.lastAttemptAt)
                checkLeasePair(dispatch.leaseOwner, dispatch.leaseExpiresAt)
                val slotA = dispatch.slot(WakeRecoverySlotId.A)
                val slotB = dispatch.slot(WakeRecoverySlotId.B)
                val migration = checkNotNull(dao.migrationState())
                check(migration.id == 1)
                val status = checkNotNull(dao.status(event.snapshotId))
                check(status.snapshotId == event.snapshotId)
                check(status.state in WAKE_STATUS_STATES)
                check(status.executionEpoch >= 0L)
                check(status.armedStart in 0..1 && status.armedGoal in 0..1)
                checkNonNegative(
                    status.processedStartAt,
                    status.processedGoalAt,
                    status.serviceLeaseExpiresAt,
                    status.heartbeatAt,
                    status.startedAt,
                    status.completedAt,
                    status.cancelledAt,
                )
                checkLeasePair(status.serviceLeaseOwner, status.serviceLeaseExpiresAt)
                WakeDispatchInput(
                    event = event,
                    state = WakeDispatchState.valueOf(dispatch.state),
                    scheduleOwner = WakeScheduleOwner.valueOf(migration.scheduleOwner),
                    dispatchAttemptId = dispatch.dispatchAttemptId,
                    dispatchLeaseOwner = dispatch.leaseOwner,
                    dispatchLeaseExpiresAt = dispatch.leaseExpiresAt,
                    executionOwner = status.activeServiceOwnerToken,
                    executionEpoch = status.executionEpoch,
                    serviceLeaseOwner = status.serviceLeaseOwner,
                    serviceLeaseExpiresAt = status.serviceLeaseExpiresAt,
                    heartbeatAt = status.heartbeatAt,
                    arrivingSlot = arrivingSlot,
                    slotA = slotA,
                    slotB = slotB,
                    nowEpochMillis = nowEpochMillis,
                    maxHeartbeatAgeMillis = maxHeartbeatAgeMillis,
                )
            }
            .getOrNull()

    private fun WakeEventDispatchEntity.slot(id: WakeRecoverySlotId): WakeRecoverySlot {
        val stateText = if (id == WakeRecoverySlotId.A) recoverySlotAState else recoverySlotBState
        val trigger = if (id == WakeRecoverySlotId.A) recoverySlotAAt else recoverySlotBAt
        val token = if (id == WakeRecoverySlotId.A) recoverySlotAToken else recoverySlotBToken
        val state = WakeRecoverySlotState.valueOf(stateText)
        check(token >= 0L)
        check((state in LIVE_SLOT_STATES) == (trigger != null))
        return WakeRecoverySlot(state, trigger, token)
    }

    private fun WakeEventArrival.recoverySlotOrNull(): WakeRecoverySlotId? =
        (this as? WakeEventArrival.Recovery)?.slot

    private fun WakeDispatchAction.toStoreOutcome(): WakeEventStoreOutcome =
        when (this) {
            WakeDispatchAction.NO_OP_TERMINAL -> WakeEventStoreOutcome.NO_OP_TERMINAL
            WakeDispatchAction.NO_OP_ACTIVE_DISPATCH -> WakeEventStoreOutcome.NO_OP_ACTIVE_DISPATCH
            WakeDispatchAction.NO_OP_HEALTHY_ACK -> WakeEventStoreOutcome.NO_OP_HEALTHY_ACK
            WakeDispatchAction.FAIL_CLOSED -> WakeEventStoreOutcome.FAIL_CLOSED
            WakeDispatchAction.DEFER,
            WakeDispatchAction.REQUEST_DISPATCH -> error("Mutation action lacked transition")
        }

    private fun checkNonNegative(vararg epochs: Long?) {
        check(epochs.all { it == null || it >= 0L })
    }

    private fun checkLeasePair(owner: String?, expiresAt: Long?) {
        check((owner == null) == (expiresAt == null))
    }

    private enum class ArrivalGate {
        VALID,
        ALREADY_CONSUMED,
        STALE_DELIVERY,
        FAIL_CLOSED,
    }

    private companion object {
        const val BEFORE_CAS = "BEFORE_CAS"
        const val AFTER_CAS = "AFTER_CAS"
        val RECOVERY_DELIVERABLE_STATES =
            setOf(WakeRecoverySlotState.FIRED, WakeRecoverySlotState.IN_FLIGHT)
        val LIVE_SLOT_STATES =
            setOf(
                WakeRecoverySlotState.ARMED,
                WakeRecoverySlotState.FIRED,
                WakeRecoverySlotState.IN_FLIGHT,
            )
        val WAKE_STATUS_STATES =
            setOf(
                "PREPARED",
                "ACTIVE",
                "GOAL_REACHED",
                "COMPLETED",
                "NO_CONFIRMATION",
                "FAILED",
                "CANCELLED",
                "SUPERSEDED",
                "EXPIRED",
            )
    }
}
