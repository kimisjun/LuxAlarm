/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.wake.WakeDispatchState
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeFailureReason
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorReceiptAction
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorReceiptClassifier
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorRow
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorState
import com.dsalmun.luxalarm.wake.WakeRecoveryRunStatus
import com.dsalmun.luxalarm.wake.WakeRecoverySlotState
import com.dsalmun.luxalarm.wake.WakeRunState

internal enum class WakeRecoveryAnchorReceiptStoreOutcome {
    APPLIED,
    RESUME_PROCESSING,
    DUPLICATE,
    STALE_DELIVERY,
    FAIL_CLOSED,
    STALE_RETRY_REQUIRED,
}

internal data class WakeRecoveryAnchorReceiptStoreResult(
    val outcome: WakeRecoveryAnchorReceiptStoreOutcome,
    val anchor: WakeRecoveryAnchorRow? = null,
)

/**
 * Claims only the immutable anchor receipt. Processing FIRED work is deliberately a later
 * transaction.
 *
 * Room serializes this store's reads and CAS in one transaction, so ordinary callers cannot
 * deterministically produce CAS=0 between them. A zero count is nevertheless handled by exactly one
 * bounded reread, with no second write and no replaceable CAS executor.
 */
internal class RoomWakeRecoveryAnchorReceiptStore
private constructor(
    private val database: AlarmDatabase,
    private val faultHook: (String) -> Unit,
) {
    internal constructor(database: AlarmDatabase) : this(database, {})

    fun claim(delivery: WakeRecoveryAnchorDelivery): WakeRecoveryAnchorReceiptStoreResult =
        database.runInTransaction<WakeRecoveryAnchorReceiptStoreResult> {
            val dao = database.wakeRecoveryAnchorDao()
            val eventKey = delivery.event.canonicalKey()
            val dispatch =
                dao.dispatch(eventKey)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            val context =
                loadContext(dao, delivery, dispatch)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            if (context.status.state in TERMINAL_STATES) {
                return@runInTransaction result(WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY)
            }
            val initial =
                dao.anchor(eventKey, delivery.kind.name)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            val row =
                initial.toPureRow(delivery)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            when (WakeRecoveryAnchorReceiptClassifier.classify(row, delivery).action) {
                WakeRecoveryAnchorReceiptAction.STALE_NO_OP ->
                    return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
                        row,
                    )
                WakeRecoveryAnchorReceiptAction.RESUME_PROCESSING ->
                    return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING,
                        row,
                    )
                WakeRecoveryAnchorReceiptAction.DUPLICATE_NO_OP ->
                    return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.DUPLICATE,
                        row,
                    )
                WakeRecoveryAnchorReceiptAction.CLAIM_FIRED -> Unit
            }

            faultHook(BEFORE_CAS)
            val changed =
                dao.compareAndSetArmedToFired(
                    eventKey,
                    delivery.kind.name,
                    delivery.triggerEpochMillis,
                    delivery.pendingIntentIdentity,
                )
            if (changed == 1) {
                faultHook(AFTER_CAS)
                val fresh = checkNotNull(dao.anchor(eventKey, delivery.kind.name))
                val freshRow = checkNotNull(fresh.toPureRow(delivery))
                check(freshRow.state == WakeRecoveryAnchorState.FIRED)
                return@runInTransaction result(
                    WakeRecoveryAnchorReceiptStoreOutcome.APPLIED,
                    freshRow,
                )
            }
            check(changed == 0) { "Room CAS changed more than one recovery anchor row" }

            val current =
                dao.anchor(eventKey, delivery.kind.name)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            val currentContext =
                loadContext(
                    dao,
                    delivery,
                    dao.dispatch(eventKey)
                        ?: return@runInTransaction result(
                            WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                        ),
                )
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            if (currentContext.status.state in TERMINAL_STATES) {
                return@runInTransaction result(WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY)
            }
            val currentRow =
                current.toPureRow(delivery)
                    ?: return@runInTransaction result(
                        WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED
                    )
            return@runInTransaction when (
                WakeRecoveryAnchorReceiptClassifier.classify(currentRow, delivery).action
            ) {
                WakeRecoveryAnchorReceiptAction.RESUME_PROCESSING ->
                    result(WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING, currentRow)
                WakeRecoveryAnchorReceiptAction.DUPLICATE_NO_OP ->
                    result(WakeRecoveryAnchorReceiptStoreOutcome.DUPLICATE, currentRow)
                WakeRecoveryAnchorReceiptAction.STALE_NO_OP ->
                    result(WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY, currentRow)
                WakeRecoveryAnchorReceiptAction.CLAIM_FIRED ->
                    result(WakeRecoveryAnchorReceiptStoreOutcome.STALE_RETRY_REQUIRED, currentRow)
            }
        }

    private fun loadContext(
        dao: WakeRecoveryAnchorDao,
        delivery: WakeRecoveryAnchorDelivery,
        dispatch: WakeEventDispatchEntity,
    ): ReceiptContext? =
        runCatching {
                val event = delivery.event
                check(dispatch.eventKey == event.canonicalKey())
                check(dispatch.snapshotId == event.snapshotId)
                check(WakeEventKind.valueOf(dispatch.eventKind) == WakeEventKind.GOAL)
                check(dispatch.expectedTriggerEpochMs == event.expectedTriggerEpochMillis)
                check(dispatch.expectedTriggerEpochMs >= 0L)
                WakeDispatchState.valueOf(dispatch.state)
                check(dispatch.dispatchAttemptId >= 0L && dispatch.attemptCount >= 0L)
                check(dispatch.armedPrimary in 0..1)
                checkNonNegative(dispatch.leaseExpiresAt, dispatch.lastAttemptAt)
                check((dispatch.leaseOwner == null) == (dispatch.leaseExpiresAt == null))
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

                val snapshot = checkNotNull(dao.snapshot(event.snapshotId))
                check(snapshot.id == event.snapshotId)
                check(snapshot.goalEpochMs == event.expectedTriggerEpochMillis)
                check(snapshot.scheduleGeneration >= 0L)
                check(snapshot.routineRevision >= 0L && snapshot.calculationRuleVersion >= 0L)
                check(snapshot.wakeStartEpochMs >= 0L && snapshot.goalEpochMs >= 0L)
                check(snapshot.createdAt >= 0L)

                val statusEntity = checkNotNull(dao.status(event.snapshotId))
                check(statusEntity.snapshotId == snapshot.id)
                ReceiptContext(snapshot, statusEntity.toPureStatus())
            }
            .getOrNull()

    private fun WakeRunStatusEntity.toPureStatus(): WakeRecoveryRunStatus =
        WakeRecoveryRunStatus(
            state = WakeRunState.valueOf(state),
            processedStartAtEpochMillis = processedStartAt,
            processedGoalAtEpochMillis = processedGoalAt,
            activeServiceOwnerToken = activeServiceOwnerToken,
            executionEpoch = executionEpoch,
            serviceLeaseOwner = serviceLeaseOwner,
            serviceLeaseExpiresAtEpochMillis = serviceLeaseExpiresAt,
            heartbeatAtEpochMillis = heartbeatAt,
            armedStart = armedStart.toBooleanFlag(),
            armedGoal = armedGoal.toBooleanFlag(),
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
            cancelledAtEpochMillis = cancelledAt,
            failureReason = failureReason?.let(WakeFailureReason::valueOf),
        )

    private fun WakeRecoveryAnchorEntity.toPureRow(
        delivery: WakeRecoveryAnchorDelivery
    ): WakeRecoveryAnchorRow? =
        runCatching {
                check(eventKey == delivery.event.canonicalKey())
                val storedKind = WakeRecoveryAnchorKind.valueOf(anchorKind)
                check(storedKind == delivery.kind)
                WakeRecoveryAnchorRow(
                    event = delivery.event,
                    kind = storedKind,
                    triggerEpochMillis = triggerEpochMs,
                    state = WakeRecoveryAnchorState.valueOf(state),
                    pendingIntentIdentity = pendingIntentIdentity,
                )
            }
            .getOrNull()

    private fun validateSlot(stateText: String, trigger: Long?, token: Long) {
        val state = WakeRecoverySlotState.valueOf(stateText)
        check(token >= 0L)
        check(trigger == null || trigger >= 0L)
        check((state in LIVE_SLOT_STATES) == (trigger != null))
    }

    private fun Int.toBooleanFlag(): Boolean {
        check(this in 0..1)
        return this == 1
    }

    private fun checkNonNegative(vararg epochs: Long?) {
        check(epochs.all { it == null || it >= 0L })
    }

    private fun result(
        outcome: WakeRecoveryAnchorReceiptStoreOutcome,
        anchor: WakeRecoveryAnchorRow? = null,
    ) = WakeRecoveryAnchorReceiptStoreResult(outcome, anchor)

    private data class ReceiptContext(
        val snapshot: WakeRunSnapshotEntity,
        val status: WakeRecoveryRunStatus,
    )

    private companion object {
        const val BEFORE_CAS = "BEFORE_CAS"
        const val AFTER_CAS = "AFTER_CAS"
        val TERMINAL_STATES =
            setOf(
                WakeRunState.COMPLETED,
                WakeRunState.NO_CONFIRMATION,
                WakeRunState.FAILED,
                WakeRunState.CANCELLED,
                WakeRunState.SUPERSEDED,
                WakeRunState.EXPIRED,
            )
        val LIVE_SLOT_STATES =
            setOf(
                WakeRecoverySlotState.ARMED,
                WakeRecoverySlotState.FIRED,
                WakeRecoverySlotState.IN_FLIGHT,
            )
    }
}
