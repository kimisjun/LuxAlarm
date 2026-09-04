/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeDispatchAction
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorization
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchInput
import com.dsalmun.luxalarm.wake.WakeDispatchReducer
import com.dsalmun.luxalarm.wake.WakeDispatchSource
import com.dsalmun.luxalarm.wake.WakeDispatchState
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeRecoverySlot
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import com.dsalmun.luxalarm.wake.WakeRecoverySlotState
import com.dsalmun.luxalarm.wake.WakeScheduleOwner

/** Immutable identity supplied by the alarm delivery boundary. */
internal interface WakeEventArrival {
    fun <T> match(
        onPrimary: () -> T,
        onRecovery: (WakeEventIdentity, WakeRecoverySlotId, Long, Long) -> T,
    ): T

    data object Primary : WakeEventArrival {
        override fun <T> match(
            onPrimary: () -> T,
            onRecovery: (WakeEventIdentity, WakeRecoverySlotId, Long, Long) -> T,
        ): T = onPrimary()
    }
}

/**
 * Sole normal construction path for authenticated recovery arrivals.
 *
 * Task 5 may call this only after its non-exported receiver has canonically parsed and validated an
 * immutable, event-specific PendingIntent data URI. Intent extras are never authoritative input to
 * this boundary. This is an auditable in-module chokepoint, not cryptographic isolation from other
 * code in the same module.
 *
 * The implementation is anonymous and returned only here, avoiding a named constructor or static
 * creation surface. Tests lock this as an auditable, non-cryptographic same-module chokepoint.
 */
internal object AuthenticatedWakeEventArrivalFactory {
    fun fromVerifiedPendingIntentData(
        eventIdentity: WakeEventIdentity,
        slot: WakeRecoverySlotId,
        token: Long,
        triggerEpochMillis: Long,
    ): WakeEventArrival {
        require(token >= 0L && token < Long.MAX_VALUE) {
            "Delivered recovery token must be in [0, Long.MAX_VALUE)"
        }
        require(triggerEpochMillis >= 0L) {
            "Delivered recovery trigger epoch must not be negative"
        }
        return object : WakeEventArrival {
            override fun <T> match(
                onPrimary: () -> T,
                onRecovery: (WakeEventIdentity, WakeRecoverySlotId, Long, Long) -> T,
            ): T = onRecovery(eventIdentity, slot, token, triggerEpochMillis)
        }
    }
}

internal enum class WakeEventStoreOutcome {
    APPLIED,
    CONVERGED,
    AUTHORIZED_NEW,
    CONVERGED_EXACT_DUPLICATE,
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

internal interface WakeEventStoreResult {
    val outcome: WakeEventStoreOutcome
    val dispatch: WakeEventDispatchEntity?
    val convergence: WakeEventConvergence?
    val authorization: WakeDispatchAuthorization?
}

/** Sole auditable construction path for event-store result payloads. */
internal object WakeEventStoreResultFactory {
    fun create(
        outcome: WakeEventStoreOutcome,
        dispatch: WakeEventDispatchEntity?,
        convergence: WakeEventConvergence?,
        authorization: WakeDispatchAuthorization?,
    ): WakeEventStoreResult {
        require((outcome == WakeEventStoreOutcome.AUTHORIZED_NEW) == (authorization != null)) {
            "Authorization exists exactly for AUTHORIZED_NEW"
        }
        require(
            (outcome == WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE) ==
                (convergence == WakeEventConvergence.ALREADY_CONSUMED)
        ) {
            "Convergence exists exactly for an exact consumed duplicate"
        }
        return object : WakeEventStoreResult {
            override val outcome = outcome
            override val dispatch = dispatch
            override val convergence = convergence
            override val authorization = authorization
        }
    }
}

/**
 * Transactional adapter from durable V6 rows to the pure, slot-based wake-event reducer.
 *
 * The reducer intentionally remains slot-based. This boundary authenticates the immutable recovery
 * delivery token and trigger against the durable selected slot before exposing the immutable
 * delivery identity to the reducer. A consumed slot erases its trigger, so duplicate convergence
 * can only be token-fenced here; the receiver must authenticate event-specific PendingIntent data.
 */
internal class RoomWakeEventDispatchStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun reduce(
        event: WakeEventIdentity,
        source: WakeDispatchSource,
        nowEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeEventStoreResult {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        require(maxHeartbeatAgeMillis > 0L) { "Heartbeat age bound must be positive" }
        if (source.receivedAt != nowEpochMillis) {
            return result(WakeEventStoreOutcome.FAIL_CLOSED)
        }
        val canonicalSource =
            runCatching {
                    WakeDispatchAuthorizationFactory.canonicalSource(
                        event,
                        source.kind,
                        source.canonicalPendingIntentIdentity,
                        source.receivedAt,
                    )
                }
                .getOrNull() ?: return result(WakeEventStoreOutcome.FAIL_CLOSED)
        if (canonicalSource != source) {
            return result(WakeEventStoreOutcome.FAIL_CLOSED)
        }
        val parsed =
            com.dsalmun.luxalarm.wake.WakePendingIntentData.parse(
                source.canonicalPendingIntentIdentity
            ) ?: return result(WakeEventStoreOutcome.FAIL_CLOSED)
        val arrival =
            parsed.match(
                onPrimary = { parsedEvent, _ ->
                    if (parsedEvent == event) WakeEventArrival.Primary else null
                },
                onDynamic = { parsedEvent, dynamic -> if (parsedEvent == event) dynamic else null },
                onAnchor = { null },
            ) ?: return result(WakeEventStoreOutcome.FAIL_CLOSED)
        return database.runInTransaction<WakeEventStoreResult> {
            val dao = database.wakeEventDispatchDao()
            val initial =
                dao.dispatch(event.canonicalKey())
                    ?: return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED)
            val baseContext =
                loadContext(dao, event, initial, null, nowEpochMillis, maxHeartbeatAgeMillis)
                    ?: return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED, initial)
            val status = checkNotNull(dao.status(event.snapshotId))
            if (status.state in TERMINAL_STATUS_STATES) {
                return@runInTransaction result(WakeEventStoreOutcome.NO_OP_TERMINAL, initial)
            }
            when (authenticateArrival(initial, baseContext, arrival)) {
                ArrivalGate.ALREADY_CONSUMED ->
                    return@runInTransaction result(
                        WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
                        initial,
                        WakeEventConvergence.ALREADY_CONSUMED,
                    )
                ArrivalGate.STALE_DELIVERY ->
                    return@runInTransaction result(WakeEventStoreOutcome.STALE_DELIVERY, initial)
                ArrivalGate.FAIL_CLOSED ->
                    return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED, initial)
                ArrivalGate.VALID -> Unit
            }

            val context =
                baseContext.copy(
                    arrivingSlot = arrival.recoverySlotOrNull(),
                    arrivingRecoveryTriggerEpochMillis = arrival.recoveryTriggerOrNull(),
                )
            val reduction = WakeDispatchReducer.reduce(context)
            val transition =
                reduction.transition
                    ?: return@runInTransaction result(reduction.action.toStoreOutcome(), initial)
            val requesting = reduction.action == WakeDispatchAction.REQUEST_DISPATCH
            if (
                requesting &&
                    (initial.attemptCount == Long.MAX_VALUE ||
                        nowEpochMillis > Long.MAX_VALUE - DISPATCH_LEASE_MILLIS)
            ) {
                return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED, initial)
            }
            val authorization =
                if (requesting) {
                    val snapshot = checkNotNull(dao.snapshot(event.snapshotId))
                    runCatching {
                            WakeDispatchAuthorizationFactory.create(
                                event,
                                snapshot.scheduleGeneration,
                                transition.nextDispatchAttemptId,
                                status.executionEpoch,
                                nowEpochMillis + DISPATCH_LEASE_MILLIS,
                                source,
                            )
                        }
                        .getOrNull()
                        ?: return@runInTransaction result(
                            WakeEventStoreOutcome.FAIL_CLOSED,
                            initial,
                        )
                } else null
            val expectedPostimage =
                initial.copy(
                    state = transition.nextState.name,
                    dispatchAttemptId = transition.nextDispatchAttemptId,
                    leaseOwner = if (requesting) authorization?.leaseOwner else initial.leaseOwner,
                    leaseExpiresAt =
                        if (requesting) authorization?.leaseExpiresAt else initial.leaseExpiresAt,
                    attemptCount =
                        if (requesting) initial.attemptCount + 1L else initial.attemptCount,
                    lastAttemptAt = if (requesting) nowEpochMillis else initial.lastAttemptAt,
                    failureReason = if (requesting) null else initial.failureReason,
                    armedPrimary = if (arrival.isPrimary()) 0 else initial.armedPrimary,
                    recoverySlotAAt =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.A) {
                            transition.nextRecoveryTriggerAtEpochMillis
                        } else initial.recoverySlotAAt,
                    recoverySlotAState =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.A) {
                            checkNotNull(transition.nextRecoverySlotState).name
                        } else initial.recoverySlotAState,
                    recoverySlotAToken =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.A) {
                            checkNotNull(transition.nextRecoverySlotToken)
                        } else initial.recoverySlotAToken,
                    recoverySlotBAt =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.B) {
                            transition.nextRecoveryTriggerAtEpochMillis
                        } else initial.recoverySlotBAt,
                    recoverySlotBState =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.B) {
                            checkNotNull(transition.nextRecoverySlotState).name
                        } else initial.recoverySlotBState,
                    recoverySlotBToken =
                        if (transition.expectedRecoverySlot == WakeRecoverySlotId.B) {
                            checkNotNull(transition.nextRecoverySlotToken)
                        } else initial.recoverySlotBToken,
                )
            faultHook(BEFORE_CAS)
            val changed =
                dao.compareAndSet(
                    expectedEventKey = initial.eventKey,
                    expectedSnapshotId = initial.snapshotId,
                    expectedEventKind = initial.eventKind,
                    expectedTriggerEpochMs = initial.expectedTriggerEpochMs,
                    expectedState = initial.state,
                    expectedDispatchAttemptId = initial.dispatchAttemptId,
                    expectedLeaseOwner = initial.leaseOwner,
                    expectedLeaseExpiresAt = initial.leaseExpiresAt,
                    expectedAttemptCount = initial.attemptCount,
                    expectedLastAttemptAt = initial.lastAttemptAt,
                    expectedFailureReason = initial.failureReason,
                    expectedArmedPrimary = initial.armedPrimary,
                    expectedRecoverySlotAAt = initial.recoverySlotAAt,
                    expectedRecoverySlotAState = initial.recoverySlotAState,
                    expectedRecoverySlotAToken = initial.recoverySlotAToken,
                    expectedRecoverySlotBAt = initial.recoverySlotBAt,
                    expectedRecoverySlotBState = initial.recoverySlotBState,
                    expectedRecoverySlotBToken = initial.recoverySlotBToken,
                    nextState = expectedPostimage.state,
                    nextDispatchAttemptId = expectedPostimage.dispatchAttemptId,
                    nextLeaseOwner = expectedPostimage.leaseOwner,
                    nextLeaseExpiresAt = expectedPostimage.leaseExpiresAt,
                    nextAttemptCount = expectedPostimage.attemptCount,
                    nextLastAttemptAt = expectedPostimage.lastAttemptAt,
                    nextFailureReason = expectedPostimage.failureReason,
                    nextArmedPrimary = expectedPostimage.armedPrimary,
                    nextRecoverySlotAAt = expectedPostimage.recoverySlotAAt,
                    nextRecoverySlotAState = expectedPostimage.recoverySlotAState,
                    nextRecoverySlotAToken = expectedPostimage.recoverySlotAToken,
                    nextRecoverySlotBAt = expectedPostimage.recoverySlotBAt,
                    nextRecoverySlotBState = expectedPostimage.recoverySlotBState,
                    nextRecoverySlotBToken = expectedPostimage.recoverySlotBToken,
                )
            if (changed == 1) {
                faultHook(AFTER_CAS)
                val applied = checkNotNull(dao.dispatch(event.canonicalKey()))
                check(applied == expectedPostimage) { "Room CAS postimage did not match exactly" }
                if (requesting) {
                    checkNotNull(authorization)
                    faultHook(BEFORE_RETURN)
                    return@runInTransaction result(
                        WakeEventStoreOutcome.AUTHORIZED_NEW,
                        applied,
                        authorization = authorization,
                    )
                }
                faultHook(BEFORE_RETURN)
                return@runInTransaction result(WakeEventStoreOutcome.APPLIED, applied)
            }
            check(changed == 0) { "Room CAS changed more than one dispatch row" }

            // Exactly one bounded read-only reread; never perform a second mutation on CAS loss.
            val current =
                dao.dispatch(event.canonicalKey())
                    ?: return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED)
            val currentContext =
                loadContext(dao, event, current, null, nowEpochMillis, maxHeartbeatAgeMillis)
                    ?: return@runInTransaction result(WakeEventStoreOutcome.FAIL_CLOSED, current)
            return@runInTransaction when (authenticateArrival(current, currentContext, arrival)) {
                ArrivalGate.STALE_DELIVERY -> result(WakeEventStoreOutcome.STALE_DELIVERY, current)
                ArrivalGate.FAIL_CLOSED -> result(WakeEventStoreOutcome.FAIL_CLOSED, current)
                ArrivalGate.ALREADY_CONSUMED ->
                    result(
                        WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
                        current,
                        WakeEventConvergence.ALREADY_CONSUMED,
                    )
                ArrivalGate.VALID -> result(WakeEventStoreOutcome.STALE_RETRY_REQUIRED, current)
            }
        }
    }

    private fun authenticateArrival(
        row: WakeEventDispatchEntity,
        input: WakeDispatchInput,
        arrival: WakeEventArrival,
    ): ArrivalGate =
        arrival.match(
            onPrimary = {
                when (row.armedPrimary) {
                    1 -> ArrivalGate.VALID
                    0 -> ArrivalGate.ALREADY_CONSUMED
                    else -> ArrivalGate.FAIL_CLOSED
                }
            },
            onRecovery = { deliveredEvent, deliveredSlot, delivered, deliveredTrigger ->
                if (deliveredEvent != input.event) return@match ArrivalGate.FAIL_CLOSED
                if (delivered < 0L || delivered == Long.MAX_VALUE) {
                    return@match ArrivalGate.FAIL_CLOSED
                }
                val slot =
                    when (deliveredSlot) {
                        WakeRecoverySlotId.A -> input.slotA
                        WakeRecoverySlotId.B -> input.slotB
                    }
                when {
                    slot.token == delivered ->
                        if (
                            slot.state in RECOVERY_DELIVERABLE_STATES &&
                                slot.triggerAtEpochMillis == deliveredTrigger
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
            },
        )

    private fun loadContext(
        dao: WakeEventDispatchDao,
        event: WakeEventIdentity,
        dispatch: WakeEventDispatchEntity,
        arrivingSlot: WakeRecoverySlotId?,
        nowEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeDispatchInput? {
        // Keep Room reads outside the validation catch: storage failures must remain observable.
        val migration = dao.migrationState() ?: return null
        val snapshot = dao.snapshot(event.snapshotId) ?: return null
        val status = dao.status(event.snapshotId) ?: return null
        return try {
            require(dispatch.eventKey == event.canonicalKey())
            require(dispatch.snapshotId == event.snapshotId)
            require(dispatch.eventKind == event.kind.name)
            require(dispatch.expectedTriggerEpochMs == event.expectedTriggerEpochMillis)
            require(dispatch.expectedTriggerEpochMs >= 0L)
            WakeDispatchState.valueOf(dispatch.state)
            require(dispatch.dispatchAttemptId >= 0L)
            require(dispatch.attemptCount >= 0L)
            require(dispatch.armedPrimary in 0..1)
            checkNonNegative(dispatch.leaseExpiresAt, dispatch.lastAttemptAt)
            checkLeasePair(dispatch.leaseOwner, dispatch.leaseExpiresAt)
            val slotA = dispatch.slot(WakeRecoverySlotId.A)
            val slotB = dispatch.slot(WakeRecoverySlotId.B)
            require(migration.id == 1)
            snapshot.requireCanonicalFor(event)
            require(migration.activeGeneration == snapshot.scheduleGeneration)
            require(status.snapshotId == snapshot.id)
            status.toPureWakeRecoveryRunStatus()
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
                arrivingRecoveryTriggerEpochMillis = null,
                slotA = slotA,
                slotB = slotB,
                nowEpochMillis = nowEpochMillis,
                maxHeartbeatAgeMillis = maxHeartbeatAgeMillis,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun WakeEventDispatchEntity.slot(id: WakeRecoverySlotId): WakeRecoverySlot {
        val stateText = if (id == WakeRecoverySlotId.A) recoverySlotAState else recoverySlotBState
        val trigger = if (id == WakeRecoverySlotId.A) recoverySlotAAt else recoverySlotBAt
        val token = if (id == WakeRecoverySlotId.A) recoverySlotAToken else recoverySlotBToken
        val state = WakeRecoverySlotState.valueOf(stateText)
        require(token >= 0L)
        require((state in LIVE_SLOT_STATES) == (trigger != null))
        return WakeRecoverySlot(state, trigger, token)
    }

    private fun WakeEventArrival.isPrimary(): Boolean = match({ true }) { _, _, _, _ -> false }

    private fun WakeEventArrival.recoverySlotOrNull(): WakeRecoverySlotId? =
        match({ null }) { _, slot, _, _ -> slot }

    private fun WakeEventArrival.recoveryTriggerOrNull(): Long? =
        match({ null }) { _, _, _, trigger -> trigger }

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
        require(epochs.all { it == null || it >= 0L })
    }

    private fun checkLeasePair(owner: String?, expiresAt: Long?) {
        require((owner == null) == (expiresAt == null))
    }

    private fun result(
        outcome: WakeEventStoreOutcome,
        dispatch: WakeEventDispatchEntity? = null,
        convergence: WakeEventConvergence? = null,
        authorization: WakeDispatchAuthorization? = null,
    ): WakeEventStoreResult =
        WakeEventStoreResultFactory.create(outcome, dispatch, convergence, authorization)

    private enum class ArrivalGate {
        VALID,
        ALREADY_CONSUMED,
        STALE_DELIVERY,
        FAIL_CLOSED,
    }

    private companion object {
        const val DISPATCH_LEASE_MILLIS = 60_000L
        const val BEFORE_CAS = "BEFORE_CAS"
        const val AFTER_CAS = "AFTER_CAS"
        const val BEFORE_RETURN = "BEFORE_RETURN"
        val RECOVERY_DELIVERABLE_STATES =
            setOf(
                WakeRecoverySlotState.ARMED,
                WakeRecoverySlotState.FIRED,
                WakeRecoverySlotState.IN_FLIGHT,
            )
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
        val TERMINAL_STATUS_STATES =
            setOf("COMPLETED", "NO_CONFIRMATION", "FAILED", "CANCELLED", "SUPERSEDED", "EXPIRED")
    }
}
