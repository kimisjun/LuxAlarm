/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

internal const val WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS = 30L * 60L * 1_000L
internal const val MAX_WAKE_ANCHOR_PI_IDENTITY_ASCII_CHARS = 512

internal enum class WakeRecoveryAnchorKind(private val offsetMillis: Long) {
    GOAL_PRIMARY(0L),
    GOAL_PLUS_1M(60_000L),
    GOAL_PLUS_5M(300_000L),
    GOAL_PLUS_15M(900_000L),
    GOAL_PLUS_30M(WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS);

    fun triggerForGoalOrNull(goalExpectedTriggerEpochMillis: Long): Long? {
        require(goalExpectedTriggerEpochMillis >= 0L) { "Goal trigger epoch must not be negative" }
        if (goalExpectedTriggerEpochMillis > Long.MAX_VALUE - offsetMillis) return null
        return goalExpectedTriggerEpochMillis + offsetMillis
    }
}

internal enum class WakeRecoveryAnchorState {
    ARMED,
    FIRED,
    CONSUMED,
    CANCELLED,
}

internal data class WakeRecoveryAnchorRow(
    val event: WakeEventIdentity,
    val kind: WakeRecoveryAnchorKind,
    val triggerEpochMillis: Long,
    val state: WakeRecoveryAnchorState,
    val pendingIntentIdentity: String,
) {
    init {
        require(event.kind == WakeEventKind.GOAL) { "Recovery anchors must be owned by GOAL" }
        val expectedTrigger =
            requireNotNull(kind.triggerForGoalOrNull(event.expectedTriggerEpochMillis)) {
                "Anchor trigger overflows epoch range"
            }
        require(triggerEpochMillis == expectedTrigger) { "Anchor trigger does not match its kind" }
        requireCanonicalPendingIntentIdentity(pendingIntentIdentity)
    }
}

internal data class WakeRecoveryAnchorDelivery(
    val event: WakeEventIdentity,
    val kind: WakeRecoveryAnchorKind,
    val triggerEpochMillis: Long,
    val pendingIntentIdentity: String,
    val receivedAtEpochMillis: Long,
) {
    init {
        require(event.kind == WakeEventKind.GOAL) {
            "Recovery anchor deliveries must be owned by GOAL"
        }
        require(triggerEpochMillis >= 0L) { "Delivered trigger epoch must not be negative" }
        require(receivedAtEpochMillis >= 0L) { "Receipt epoch must not be negative" }
        requireCanonicalPendingIntentIdentity(pendingIntentIdentity)
    }
}

internal enum class WakeRecoveryAnchorReceiptAction {
    CLAIM_FIRED,
    RESUME_PROCESSING,
    DUPLICATE_NO_OP,
    STALE_NO_OP,
}

internal data class WakeRecoveryAnchorStateTransition(
    val expectedState: WakeRecoveryAnchorState,
    val nextState: WakeRecoveryAnchorState,
) {
    init {
        require(expectedState == WakeRecoveryAnchorState.ARMED)
        require(nextState == WakeRecoveryAnchorState.FIRED)
    }
}

internal data class WakeRecoveryAnchorReceipt(
    val action: WakeRecoveryAnchorReceiptAction,
    val transition: WakeRecoveryAnchorStateTransition?,
)

internal object WakeRecoveryAnchorReceiptClassifier {
    fun classify(
        row: WakeRecoveryAnchorRow,
        delivery: WakeRecoveryAnchorDelivery,
    ): WakeRecoveryAnchorReceipt {
        val exact =
            delivery.event.kind == WakeEventKind.GOAL &&
                delivery.event == row.event &&
                delivery.kind == row.kind &&
                delivery.triggerEpochMillis == row.triggerEpochMillis &&
                delivery.pendingIntentIdentity == row.pendingIntentIdentity &&
                delivery.receivedAtEpochMillis >= row.triggerEpochMillis
        if (!exact)
            return WakeRecoveryAnchorReceipt(WakeRecoveryAnchorReceiptAction.STALE_NO_OP, null)
        return when (row.state) {
            WakeRecoveryAnchorState.ARMED ->
                WakeRecoveryAnchorReceipt(
                    WakeRecoveryAnchorReceiptAction.CLAIM_FIRED,
                    WakeRecoveryAnchorStateTransition(
                        expectedState = WakeRecoveryAnchorState.ARMED,
                        nextState = WakeRecoveryAnchorState.FIRED,
                    ),
                )
            WakeRecoveryAnchorState.FIRED ->
                WakeRecoveryAnchorReceipt(WakeRecoveryAnchorReceiptAction.RESUME_PROCESSING, null)
            WakeRecoveryAnchorState.CONSUMED ->
                WakeRecoveryAnchorReceipt(WakeRecoveryAnchorReceiptAction.DUPLICATE_NO_OP, null)
            WakeRecoveryAnchorState.CANCELLED ->
                WakeRecoveryAnchorReceipt(WakeRecoveryAnchorReceiptAction.STALE_NO_OP, null)
        }
    }
}

internal enum class WakeRunState {
    PREPARED,
    ACTIVE,
    GOAL_REACHED,
    COMPLETED,
    NO_CONFIRMATION,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    EXPIRED,
}

internal enum class WakeFailureReason {
    NO_CONFIRMATION_DEADLINE
}

internal data class WakeRecoveryRunStatus(
    val state: WakeRunState,
    val processedStartAtEpochMillis: Long?,
    val processedGoalAtEpochMillis: Long?,
    val activeServiceOwnerToken: String?,
    val executionEpoch: Long,
    val serviceLeaseOwner: String?,
    val serviceLeaseExpiresAtEpochMillis: Long?,
    val heartbeatAtEpochMillis: Long?,
    val armedStart: Boolean,
    val armedGoal: Boolean,
    val startedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val cancelledAtEpochMillis: Long?,
    val failureReason: WakeFailureReason?,
) {
    init {
        require(executionEpoch >= 0L) { "Execution epoch must not be negative" }
        listOf(activeServiceOwnerToken, serviceLeaseOwner).forEach { owner ->
            require(owner == null || owner.isValidOwnerToken()) { "Owner token is invalid" }
        }
        listOf(
                processedStartAtEpochMillis,
                processedGoalAtEpochMillis,
                serviceLeaseExpiresAtEpochMillis,
                heartbeatAtEpochMillis,
                startedAtEpochMillis,
                completedAtEpochMillis,
                cancelledAtEpochMillis,
            )
            .forEach { epoch ->
                require(epoch == null || epoch >= 0L) { "Epoch must not be negative" }
            }
        require((serviceLeaseOwner == null) == (serviceLeaseExpiresAtEpochMillis == null)) {
            "Service lease owner and expiry must both be absent or both be present"
        }
        require(
            heartbeatAtEpochMillis == null ||
                (serviceLeaseOwner != null && serviceLeaseExpiresAtEpochMillis != null)
        ) {
            "Heartbeat requires a complete service lease"
        }

        if (state.isTerminal()) {
            require(activeServiceOwnerToken == null) { "Terminal run cannot retain active owner" }
            require(serviceLeaseOwner == null && serviceLeaseExpiresAtEpochMillis == null) {
                "Terminal run cannot retain a service lease"
            }
            require(heartbeatAtEpochMillis == null) { "Terminal run cannot retain a heartbeat" }
            require(!armedStart && !armedGoal) { "Terminal run cannot retain armed events" }
        } else {
            require(completedAtEpochMillis == null && cancelledAtEpochMillis == null) {
                "Nonterminal run cannot have terminal timestamps"
            }
            require(failureReason != WakeFailureReason.NO_CONFIRMATION_DEADLINE) {
                "Nonterminal run cannot have terminal-only failure reason"
            }
        }

        when (state) {
            WakeRunState.NO_CONFIRMATION -> {
                require(completedAtEpochMillis != null) {
                    "NO_CONFIRMATION requires completion timestamp"
                }
                require(cancelledAtEpochMillis == null) {
                    "NO_CONFIRMATION cannot have cancellation timestamp"
                }
                require(failureReason == WakeFailureReason.NO_CONFIRMATION_DEADLINE) {
                    "NO_CONFIRMATION requires deadline failure reason"
                }
            }
            WakeRunState.COMPLETED -> {
                require(completedAtEpochMillis != null) {
                    "COMPLETED requires completion timestamp"
                }
                require(cancelledAtEpochMillis == null) {
                    "COMPLETED cannot have cancellation timestamp"
                }
            }
            WakeRunState.CANCELLED -> {
                require(cancelledAtEpochMillis != null) {
                    "CANCELLED requires cancellation timestamp"
                }
                require(completedAtEpochMillis == null) {
                    "CANCELLED cannot have completion timestamp"
                }
            }
            WakeRunState.PREPARED,
            WakeRunState.ACTIVE,
            WakeRunState.GOAL_REACHED,
            WakeRunState.FAILED,
            WakeRunState.SUPERSEDED,
            WakeRunState.EXPIRED -> Unit
        }
    }
}

internal enum class WakeRecoveryPlanAction {
    RECOVER,
    TERMINAL_NO_CONFIRMATION,
    DUPLICATE_NO_OP,
    STALE_NO_OP,
    FAIL_CLOSED,
}

internal enum class WakeRecoveryStimulus {
    REQUEST_GOAL_DISPATCH
}

internal enum class WakeRecoveryRecommendation {
    TERMINALIZE_DISPATCHES,
    CANCEL_PRIMARY_SLOTS,
    CANCEL_DYNAMIC_RECOVERY_SLOTS,
    CANCEL_IMMUTABLE_ANCHORS,
    CREATE_NEXT,
}

internal data class WakeRecoveryStatusTransition(
    val expected: WakeRecoveryRunStatus,
    val next: WakeRecoveryRunStatus,
)

internal data class WakeRecoveryPlan(
    val action: WakeRecoveryPlanAction,
    val anchorTransition: WakeRecoveryAnchorStateTransition?,
    val statusTransition: WakeRecoveryStatusTransition?,
    val stimulus: WakeRecoveryStimulus?,
    val recommendations: Set<WakeRecoveryRecommendation> = emptySet(),
)

internal object WakeRecoveryAnchorPlanner {
    fun plan(
        row: WakeRecoveryAnchorRow,
        delivery: WakeRecoveryAnchorDelivery,
        status: WakeRecoveryRunStatus,
    ): WakeRecoveryPlan {
        if (status.state.isTerminal()) return staleRecoveryPlan()
        val receipt = WakeRecoveryAnchorReceiptClassifier.classify(row, delivery)
        when (receipt.action) {
            WakeRecoveryAnchorReceiptAction.STALE_NO_OP ->
                return WakeRecoveryPlan(WakeRecoveryPlanAction.STALE_NO_OP, null, null, null)
            WakeRecoveryAnchorReceiptAction.DUPLICATE_NO_OP ->
                return WakeRecoveryPlan(WakeRecoveryPlanAction.DUPLICATE_NO_OP, null, null, null)
            WakeRecoveryAnchorReceiptAction.CLAIM_FIRED,
            WakeRecoveryAnchorReceiptAction.RESUME_PROCESSING -> Unit
        }
        val deadline =
            WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(
                row.event.expectedTriggerEpochMillis
            ) ?: return WakeRecoveryPlan(WakeRecoveryPlanAction.FAIL_CLOSED, null, null, null)
        if (delivery.receivedAtEpochMillis < deadline) {
            return WakeRecoveryPlan(
                action = WakeRecoveryPlanAction.RECOVER,
                anchorTransition = receipt.transition,
                statusTransition = null,
                stimulus = WakeRecoveryStimulus.REQUEST_GOAL_DISPATCH,
            )
        }
        return terminalNoConfirmationPlan(
            status = status,
            nowEpochMillis = delivery.receivedAtEpochMillis,
            anchorTransition = receipt.transition,
        )
    }
}

internal enum class WakeLateStartEventAction {
    START_EXPIRED,
    STALE_NO_OP,
}

internal data class WakeLateStartPlan(
    val eventAction: WakeLateStartEventAction,
    val occurrencePlan: WakeRecoveryPlan,
)

internal object WakeLateStartPlanner {
    fun plan(
        startEvent: WakeEventIdentity,
        goalEvent: WakeEventIdentity,
        nowEpochMillis: Long,
        status: WakeRecoveryRunStatus,
    ): WakeLateStartPlan {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        if (status.state.isTerminal()) {
            return WakeLateStartPlan(WakeLateStartEventAction.STALE_NO_OP, staleRecoveryPlan())
        }
        val sameOccurrence =
            startEvent.kind == WakeEventKind.START &&
                goalEvent.kind == WakeEventKind.GOAL &&
                startEvent.snapshotId == goalEvent.snapshotId
        val deadline =
            WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(
                goalEvent.expectedTriggerEpochMillis
            )
        if (!sameOccurrence || deadline == null || nowEpochMillis < deadline) {
            return WakeLateStartPlan(
                WakeLateStartEventAction.STALE_NO_OP,
                WakeRecoveryPlan(WakeRecoveryPlanAction.STALE_NO_OP, null, null, null),
            )
        }
        return WakeLateStartPlan(
            WakeLateStartEventAction.START_EXPIRED,
            terminalNoConfirmationPlan(status, nowEpochMillis, anchorTransition = null),
        )
    }
}

private fun terminalNoConfirmationPlan(
    status: WakeRecoveryRunStatus,
    nowEpochMillis: Long,
    anchorTransition: WakeRecoveryAnchorStateTransition?,
): WakeRecoveryPlan {
    if (status.state.isTerminal()) return staleRecoveryPlan()
    if (status.executionEpoch == Long.MAX_VALUE) {
        return WakeRecoveryPlan(WakeRecoveryPlanAction.FAIL_CLOSED, null, null, null)
    }
    val next =
        status.copy(
            state = WakeRunState.NO_CONFIRMATION,
            activeServiceOwnerToken = null,
            executionEpoch = status.executionEpoch + 1L,
            serviceLeaseOwner = null,
            serviceLeaseExpiresAtEpochMillis = null,
            heartbeatAtEpochMillis = null,
            armedStart = false,
            armedGoal = false,
            completedAtEpochMillis = nowEpochMillis,
            failureReason = WakeFailureReason.NO_CONFIRMATION_DEADLINE,
        )
    return WakeRecoveryPlan(
        action = WakeRecoveryPlanAction.TERMINAL_NO_CONFIRMATION,
        anchorTransition = anchorTransition,
        statusTransition = WakeRecoveryStatusTransition(status, next),
        stimulus = null,
        recommendations = WakeRecoveryRecommendation.entries.toSet(),
    )
}

private fun staleRecoveryPlan() =
    WakeRecoveryPlan(WakeRecoveryPlanAction.STALE_NO_OP, null, null, null)

private fun WakeRunState.isTerminal(): Boolean =
    when (this) {
        WakeRunState.COMPLETED,
        WakeRunState.NO_CONFIRMATION,
        WakeRunState.FAILED,
        WakeRunState.CANCELLED,
        WakeRunState.SUPERSEDED,
        WakeRunState.EXPIRED -> true
        WakeRunState.PREPARED,
        WakeRunState.ACTIVE,
        WakeRunState.GOAL_REACHED -> false
    }

internal enum class WakeRecoveryInvariantViolation {
    NOT_IN_RECOVERY_WINDOW,
    INVALID_GOAL_EVENT,
    INVALID_KIND_SET,
    DUPLICATE_KIND,
    EVENT_IDENTITY_MISMATCH,
    DUPLICATE_PENDING_INTENT_IDENTITY,
    GOAL_PRIMARY_IDENTITY_MISMATCH,
    EARLY_CANCELLED_ANCHOR,
    NO_FUTURE_ARMED_ANCHOR,
    DEADLINE_OVERFLOW,
}

internal data class WakeRecoveryInvariantResult(
    val violations: Set<WakeRecoveryInvariantViolation>
) {
    val isValid: Boolean
        get() = violations.isEmpty()
}

internal object WakeRecoveryAnchorInvariant {
    fun validateNonterminalSnapshot(
        nowEpochMillis: Long,
        goalEvent: WakeEventIdentity,
        goalPrimaryPendingIntentIdentity: String,
        anchors: List<WakeRecoveryAnchorRow>,
    ): WakeRecoveryInvariantResult {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        requireCanonicalPendingIntentIdentity(goalPrimaryPendingIntentIdentity)
        val violations = linkedSetOf<WakeRecoveryInvariantViolation>()
        if (goalEvent.kind != WakeEventKind.GOAL) {
            violations += WakeRecoveryInvariantViolation.INVALID_GOAL_EVENT
        }
        val deadline =
            WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(
                goalEvent.expectedTriggerEpochMillis
            )
        if (deadline == null) {
            violations += WakeRecoveryInvariantViolation.DEADLINE_OVERFLOW
        } else if (nowEpochMillis >= deadline) {
            violations += WakeRecoveryInvariantViolation.NOT_IN_RECOVERY_WINDOW
        }
        val kinds = anchors.map { it.kind }
        if (kinds.toSet() != WakeRecoveryAnchorKind.entries.toSet()) {
            violations += WakeRecoveryInvariantViolation.INVALID_KIND_SET
        }
        if (kinds.size != kinds.toSet().size) {
            violations += WakeRecoveryInvariantViolation.DUPLICATE_KIND
        }
        if (anchors.any { it.event != goalEvent }) {
            violations += WakeRecoveryInvariantViolation.EVENT_IDENTITY_MISMATCH
        }
        val identities = anchors.map { it.pendingIntentIdentity }
        if (identities.size != identities.toSet().size) {
            violations += WakeRecoveryInvariantViolation.DUPLICATE_PENDING_INTENT_IDENTITY
        }
        val primary = anchors.singleOrNull { it.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY }
        if (primary != null && primary.pendingIntentIdentity != goalPrimaryPendingIntentIdentity) {
            violations += WakeRecoveryInvariantViolation.GOAL_PRIMARY_IDENTITY_MISMATCH
        }
        if (anchors.any { it.state == WakeRecoveryAnchorState.CANCELLED }) {
            violations += WakeRecoveryInvariantViolation.EARLY_CANCELLED_ANCHOR
        }
        if (
            deadline != null &&
                nowEpochMillis < deadline &&
                anchors.none {
                    it.state == WakeRecoveryAnchorState.ARMED &&
                        it.triggerEpochMillis > nowEpochMillis
                }
        ) {
            violations += WakeRecoveryInvariantViolation.NO_FUTURE_ARMED_ANCHOR
        }
        return WakeRecoveryInvariantResult(violations)
    }
}

private fun requireCanonicalPendingIntentIdentity(value: String) {
    require(value.isNotEmpty()) { "PendingIntent identity must not be empty" }
    require(value.length <= MAX_WAKE_ANCHOR_PI_IDENTITY_ASCII_CHARS) {
        "PendingIntent identity exceeds bound"
    }
    value.forEach { char ->
        require(char.code in 0x21..0x7e) {
            "PendingIntent identity must use canonical printable ASCII without whitespace"
        }
    }
}
