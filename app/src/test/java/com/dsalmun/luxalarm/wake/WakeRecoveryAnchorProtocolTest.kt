/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WakeRecoveryAnchorProtocolTest {
    @Test
    fun everyV6AnchorKindMapsToItsExactGoalRelativeTrigger() {
        val goal = 10_000_000L
        val expected =
            mapOf(
                WakeRecoveryAnchorKind.GOAL_PRIMARY to goal,
                WakeRecoveryAnchorKind.GOAL_PLUS_1M to goal + 60_000L,
                WakeRecoveryAnchorKind.GOAL_PLUS_5M to goal + 300_000L,
                WakeRecoveryAnchorKind.GOAL_PLUS_15M to goal + 900_000L,
                WakeRecoveryAnchorKind.GOAL_PLUS_30M to goal + 1_800_000L,
            )

        assertEquals(expected.keys, WakeRecoveryAnchorKind.entries.toSet())
        expected.forEach { (kind, trigger) ->
            assertEquals(trigger, kind.triggerForGoalOrNull(goal), kind.name)
        }
        assertNull(WakeRecoveryAnchorKind.GOAL_PLUS_30M.triggerForGoalOrNull(Long.MAX_VALUE))
    }

    @Test
    fun exactReceiptsClassifyEveryDurableStateWithoutMutatingInputs() {
        val expected =
            mapOf(
                WakeRecoveryAnchorState.ARMED to WakeRecoveryAnchorReceiptAction.CLAIM_FIRED,
                WakeRecoveryAnchorState.FIRED to WakeRecoveryAnchorReceiptAction.RESUME_PROCESSING,
                WakeRecoveryAnchorState.CONSUMED to WakeRecoveryAnchorReceiptAction.DUPLICATE_NO_OP,
                WakeRecoveryAnchorState.CANCELLED to WakeRecoveryAnchorReceiptAction.STALE_NO_OP,
            )
        expected.forEach { (state, action) ->
            val row = anchor(state = state)
            val delivery = delivery()
            val rowBefore = row.copy()
            val deliveryBefore = delivery.copy()

            val result = WakeRecoveryAnchorReceiptClassifier.classify(row, delivery)

            assertEquals(action, result.action, state.name)
            assertEquals(
                if (state == WakeRecoveryAnchorState.ARMED) {
                    WakeRecoveryAnchorStateTransition(
                        expectedState = WakeRecoveryAnchorState.ARMED,
                        nextState = WakeRecoveryAnchorState.FIRED,
                    )
                } else {
                    null
                },
                result.transition,
                state.name,
            )
            assertEquals(rowBefore, row)
            assertEquals(deliveryBefore, delivery)
        }
    }

    @Test
    fun startOwnedRecoveryAnchorDeliveryIsRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            delivery(event = WakeEventIdentity("snapshot", WakeEventKind.START, GOAL))
        }
    }

    @Test
    fun everyExactIdentityFieldMismatchIsStaleWithoutTransition() {
        val row = anchor()
        val mismatches =
            listOf(
                delivery(event = WakeEventIdentity("other", WakeEventKind.GOAL, GOAL)),
                delivery(event = WakeEventIdentity("snapshot", WakeEventKind.GOAL, GOAL + 1L)),
                delivery(kind = WakeRecoveryAnchorKind.GOAL_PLUS_5M),
                delivery(trigger = ANCHOR_TRIGGER + 1L),
                delivery(pendingIntentIdentity = "pi:other"),
                delivery(receivedAt = ANCHOR_TRIGGER - 1L),
            )

        mismatches.forEach { mismatch ->
            val result = WakeRecoveryAnchorReceiptClassifier.classify(row, mismatch)
            assertEquals(
                WakeRecoveryAnchorReceiptAction.STALE_NO_OP,
                result.action,
                mismatch.toString(),
            )
            assertNull(result.transition, mismatch.toString())
        }
    }

    @Test
    fun anchorIdentityIsGoalOwnedExactAndPendingIntentIdentityIsBoundedCanonicalAscii() {
        assertFailsWith<IllegalArgumentException> {
            anchor(event = WakeEventIdentity("snapshot", WakeEventKind.START, GOAL))
        }
        assertFailsWith<IllegalArgumentException> { anchor(trigger = ANCHOR_TRIGGER + 1L) }
        listOf(
                "",
                " ",
                "pi with space",
                "pi:😀",
                "x".repeat(MAX_WAKE_ANCHOR_PI_IDENTITY_ASCII_CHARS + 1),
            )
            .forEach { invalid ->
                assertFailsWith<IllegalArgumentException>(invalid) {
                    anchor(pendingIntentIdentity = invalid)
                }
                assertFailsWith<IllegalArgumentException>(invalid) {
                    delivery(pendingIntentIdentity = invalid)
                }
            }
        val max = "x".repeat(MAX_WAKE_ANCHOR_PI_IDENTITY_ASCII_CHARS)
        assertEquals(max, anchor(pendingIntentIdentity = max).pendingIntentIdentity)
        assertTrue(anchor().pendingIntentIdentity.all { it.code in 0x21..0x7e })
    }

    @Test
    fun recoveryWindowEndsExactlyAtGoalPlusThirtyMinutes() {
        val status = status()
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val before =
            delivery(
                kind = WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                trigger = GOAL + 900_000L,
                receivedAt = deadline - 1L,
            )
        val at =
            delivery(
                kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M,
                trigger = deadline,
                receivedAt = deadline,
            )
        val after = at.copy(receivedAtEpochMillis = deadline + 1L)

        assertEquals(
            WakeRecoveryPlanAction.RECOVER,
            WakeRecoveryAnchorPlanner.plan(
                    anchor(kind = before.kind, trigger = before.triggerEpochMillis),
                    before,
                    status,
                )
                .action,
        )
        listOf(at, after).forEach { terminalDelivery ->
            val plan =
                WakeRecoveryAnchorPlanner.plan(
                    anchor(
                        kind = terminalDelivery.kind,
                        trigger = terminalDelivery.triggerEpochMillis,
                    ),
                    terminalDelivery,
                    status,
                )
            assertEquals(WakeRecoveryPlanAction.TERMINAL_NO_CONFIRMATION, plan.action)
            assertNull(plan.stimulus)
            assertEquals(WakeRunState.NO_CONFIRMATION, plan.statusTransition?.next?.state)
            assertEquals(
                terminalDelivery.receivedAtEpochMillis,
                plan.statusTransition?.next?.completedAtEpochMillis,
            )
            assertEquals(
                WakeFailureReason.NO_CONFIRMATION_DEADLINE,
                plan.statusTransition?.next?.failureReason,
            )
        }
    }

    @Test
    fun terminalPlanClearsOwnershipIncrementsEpochPreservesOccurrenceTimestampsAndRecommendsCommands() {
        val status =
            status(
                processedStartAt = 1L,
                processedGoalAt = 2L,
                startedAt = 3L,
                executionEpoch = 7L,
            )
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val row = anchor(kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M, trigger = deadline)
        val plan =
            WakeRecoveryAnchorPlanner.plan(
                row,
                delivery(kind = row.kind, trigger = deadline),
                status,
            )
        val next = requireNotNull(plan.statusTransition).next

        assertEquals(WakeRunState.NO_CONFIRMATION, next.state)
        assertEquals(8L, next.executionEpoch)
        assertNull(next.activeServiceOwnerToken)
        assertNull(next.serviceLeaseOwner)
        assertNull(next.serviceLeaseExpiresAtEpochMillis)
        assertNull(next.heartbeatAtEpochMillis)
        assertEquals(false, next.armedStart)
        assertEquals(false, next.armedGoal)
        assertEquals(1L, next.processedStartAtEpochMillis)
        assertEquals(2L, next.processedGoalAtEpochMillis)
        assertEquals(3L, next.startedAtEpochMillis)
        assertNull(next.cancelledAtEpochMillis)
        assertEquals(deadline, next.completedAtEpochMillis)
        assertEquals(WakeFailureReason.NO_CONFIRMATION_DEADLINE, next.failureReason)
        assertEquals(
            setOf(
                WakeRecoveryRecommendation.TERMINALIZE_DISPATCHES,
                WakeRecoveryRecommendation.CANCEL_PRIMARY_SLOTS,
                WakeRecoveryRecommendation.CANCEL_DYNAMIC_RECOVERY_SLOTS,
                WakeRecoveryRecommendation.CANCEL_IMMUTABLE_ANCHORS,
                WakeRecoveryRecommendation.CREATE_NEXT,
            ),
            plan.recommendations,
        )
    }

    @Test
    fun everyNonterminalRunStateCanTransitionToNoConfirmationAtDeadline() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val row = anchor(kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M, trigger = deadline)
        setOf(WakeRunState.PREPARED, WakeRunState.ACTIVE, WakeRunState.GOAL_REACHED).forEach { state
            ->
            val plan =
                WakeRecoveryAnchorPlanner.plan(
                    row,
                    delivery(kind = row.kind, trigger = deadline),
                    status(state = state),
                )

            assertEquals(WakeRecoveryPlanAction.TERMINAL_NO_CONFIRMATION, plan.action, state.name)
            assertEquals(
                WakeRunState.NO_CONFIRMATION,
                plan.statusTransition?.next?.state,
                state.name,
            )
        }
    }

    @Test
    fun overflowedDeadlineOrExecutionEpochFailsClosedWithoutWrapping() {
        val maxGoal = WakeEventIdentity("snapshot", WakeEventKind.GOAL, Long.MAX_VALUE)
        val row =
            WakeRecoveryAnchorRow(
                maxGoal,
                WakeRecoveryAnchorKind.GOAL_PRIMARY,
                Long.MAX_VALUE,
                WakeRecoveryAnchorState.ARMED,
                "pi:max",
            )
        val maxDelivery =
            WakeRecoveryAnchorDelivery(maxGoal, row.kind, Long.MAX_VALUE, "pi:max", Long.MAX_VALUE)
        assertEquals(
            WakeRecoveryPlanAction.FAIL_CLOSED,
            WakeRecoveryAnchorPlanner.plan(row, maxDelivery, status()).action,
        )

        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val terminalRow = anchor(kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M, trigger = deadline)
        val inputStatus = status(executionEpoch = Long.MAX_VALUE)
        val before = inputStatus.copy()
        val plan =
            WakeRecoveryAnchorPlanner.plan(
                terminalRow,
                delivery(kind = terminalRow.kind, trigger = deadline),
                inputStatus,
            )
        assertEquals(WakeRecoveryPlanAction.FAIL_CLOSED, plan.action)
        assertNull(plan.statusTransition)
        assertEquals(before, inputStatus)
    }

    @Test
    fun terminalPlanningIsDeterministicAndDoesNotMutateAnyInput() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val row = anchor(kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M, trigger = deadline)
        val delivery = delivery(kind = row.kind, trigger = deadline)
        val status = status()
        val copies = Triple(row.copy(), delivery.copy(), status.copy())

        val first = WakeRecoveryAnchorPlanner.plan(row, delivery, status)
        val second = WakeRecoveryAnchorPlanner.plan(row, delivery, status)

        assertEquals(first, second)
        assertEquals(copies.first, row)
        assertEquals(copies.second, delivery)
        assertEquals(copies.third, status)
    }

    @Test
    fun nonterminalSnapshotHasExactUniqueKindsAndAFutureArmedAnchorAcrossWindow() {
        val intervals =
            listOf(
                GOAL - 1L to WakeRecoveryAnchorKind.GOAL_PRIMARY,
                GOAL to WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                GOAL + 60_000L to WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                GOAL + 300_000L to WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                GOAL + 900_000L to WakeRecoveryAnchorKind.GOAL_PLUS_30M,
                GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS - 1L to
                    WakeRecoveryAnchorKind.GOAL_PLUS_30M,
            )
        intervals.forEach { (now, futureKind) ->
            val anchors =
                allAnchors().map { row ->
                    row.copy(
                        state =
                            if (row.kind == futureKind || row.triggerEpochMillis > now) {
                                WakeRecoveryAnchorState.ARMED
                            } else {
                                WakeRecoveryAnchorState.CONSUMED
                            }
                    )
                }
            assertTrue(
                WakeRecoveryAnchorInvariant.validateNonterminalSnapshot(
                        nowEpochMillis = now,
                        goalEvent = goalEvent(),
                        goalPrimaryPendingIntentIdentity = "pi:goal-primary",
                        anchors = anchors,
                    )
                    .isValid,
                "$now/$futureKind",
            )
        }
    }

    @Test
    fun invariantViolationsForMissingDuplicateAndNoFutureAnchorsAreExactAndIsolated() {
        val all = allAnchors()
        val missing = all.dropLast(1)
        val duplicate =
            all + all.last().copy(pendingIntentIdentity = "pi:duplicate-kind-distinct-identity")
        val noFuture = all.map { row -> row.copy(state = WakeRecoveryAnchorState.CONSUMED) }

        val cases =
            listOf(
                Triple(
                    GOAL - 1L,
                    missing,
                    setOf(WakeRecoveryInvariantViolation.INVALID_KIND_SET),
                ),
                Triple(
                    GOAL - 1L,
                    duplicate,
                    setOf(WakeRecoveryInvariantViolation.DUPLICATE_KIND),
                ),
                Triple(
                    GOAL + 900_000L,
                    noFuture,
                    setOf(WakeRecoveryInvariantViolation.NO_FUTURE_ARMED_ANCHOR),
                ),
            )
        cases.forEach { (now, anchors, expected) ->
            val result =
                WakeRecoveryAnchorInvariant.validateNonterminalSnapshot(
                    nowEpochMillis = now,
                    goalEvent = goalEvent(),
                    goalPrimaryPendingIntentIdentity = "pi:goal-primary",
                    anchors = anchors,
                )
            assertEquals(expected, result.violations)
        }
    }

    @Test
    fun invariantReportsEarlyCancelledAnchorEvenWhenAnotherFutureArmedAnchorSurvives() {
        val anchors =
            allAnchors().map { row ->
                if (row.kind == WakeRecoveryAnchorKind.GOAL_PLUS_1M) {
                    row.copy(state = WakeRecoveryAnchorState.CANCELLED)
                } else {
                    row
                }
            }

        val result =
            WakeRecoveryAnchorInvariant.validateNonterminalSnapshot(
                nowEpochMillis = GOAL,
                goalEvent = goalEvent(),
                goalPrimaryPendingIntentIdentity = "pi:goal-primary",
                anchors = anchors,
            )

        assertEquals(
            setOf(WakeRecoveryInvariantViolation.EARLY_CANCELLED_ANCHOR),
            result.violations,
        )
    }

    @Test
    fun invariantRequiresGoalPrimaryRowToReuseTheGoalPrimaryPendingIntentIdentity() {
        val result =
            WakeRecoveryAnchorInvariant.validateNonterminalSnapshot(
                nowEpochMillis = GOAL - 1L,
                goalEvent = goalEvent(),
                goalPrimaryPendingIntentIdentity = "pi:different-primary",
                anchors = allAnchors(),
            )

        assertEquals(
            setOf(WakeRecoveryInvariantViolation.GOAL_PRIMARY_IDENTITY_MISMATCH),
            result.violations,
        )
    }

    private fun goalEvent() = WakeEventIdentity("snapshot", WakeEventKind.GOAL, GOAL)

    private fun allAnchors(): List<WakeRecoveryAnchorRow> =
        WakeRecoveryAnchorKind.entries.map { kind ->
            val trigger = requireNotNull(kind.triggerForGoalOrNull(GOAL))
            WakeRecoveryAnchorRow(
                event = goalEvent(),
                kind = kind,
                triggerEpochMillis = trigger,
                state = WakeRecoveryAnchorState.ARMED,
                pendingIntentIdentity =
                    if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) "pi:goal-primary"
                    else "pi:${kind.name}",
            )
        }

    @Test
    fun anyGoalAnchorAtOrAfterDeadlineIsTerminalWithNoStimulus() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        allAnchors().forEach { row ->
            val envelope =
                WakeRecoveryAnchorDelivery(
                    row.event,
                    row.kind,
                    row.triggerEpochMillis,
                    row.pendingIntentIdentity,
                    deadline,
                )
            val plan = WakeRecoveryAnchorPlanner.plan(row, envelope, status())
            assertEquals(
                WakeRecoveryPlanAction.TERMINAL_NO_CONFIRMATION,
                plan.action,
                row.kind.name,
            )
            assertNull(plan.stimulus, row.kind.name)
        }
    }

    @Test
    fun lateStartIsEventExpiredButConvergesToSameNoConfirmationOccurrenceAsAnchorArrival() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val start = WakeEventIdentity("snapshot", WakeEventKind.START, GOAL - 1_000L)
        val goal = goalEvent()
        val status = status()
        val startPlan = WakeLateStartPlanner.plan(start, goal, deadline, status)
        val terminalAnchor = allAnchors().single { it.kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M }
        val anchorPlan =
            WakeRecoveryAnchorPlanner.plan(
                terminalAnchor,
                WakeRecoveryAnchorDelivery(
                    goal,
                    terminalAnchor.kind,
                    deadline,
                    terminalAnchor.pendingIntentIdentity,
                    deadline,
                ),
                status,
            )

        assertEquals(WakeLateStartEventAction.START_EXPIRED, startPlan.eventAction)
        assertEquals(
            WakeRecoveryPlanAction.TERMINAL_NO_CONFIRMATION,
            startPlan.occurrencePlan.action,
        )
        assertEquals(
            WakeRunState.NO_CONFIRMATION,
            startPlan.occurrencePlan.statusTransition?.next?.state,
        )
        assertEquals(anchorPlan.statusTransition, startPlan.occurrencePlan.statusTransition)
        assertNull(startPlan.occurrencePlan.stimulus)
    }

    @Test
    fun lateStartAndDeadlineAnchorArrivalOrdersConvergeWithoutASecondTerminalMutation() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val start = WakeEventIdentity("snapshot", WakeEventKind.START, GOAL - 1_000L)
        val terminalAnchor = allAnchors().single { it.kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M }
        val envelope =
            WakeRecoveryAnchorDelivery(
                goalEvent(),
                terminalAnchor.kind,
                deadline,
                terminalAnchor.pendingIntentIdentity,
                deadline,
            )

        val afterStart =
            requireNotNull(
                    WakeLateStartPlanner.plan(start, goalEvent(), deadline, status())
                        .occurrencePlan
                        .statusTransition
                )
                .next
        val anchorSecond = WakeRecoveryAnchorPlanner.plan(terminalAnchor, envelope, afterStart)
        val afterAnchor =
            requireNotNull(
                    WakeRecoveryAnchorPlanner.plan(terminalAnchor, envelope, status())
                        .statusTransition
                )
                .next
        val startSecond =
            WakeLateStartPlanner.plan(start, goalEvent(), deadline, afterAnchor).occurrencePlan

        assertEquals(afterStart, afterAnchor)
        assertEquals(WakeRecoveryPlanAction.STALE_NO_OP, anchorSecond.action)
        assertNull(anchorSecond.statusTransition)
        assertEquals(WakeRecoveryPlanAction.STALE_NO_OP, startSecond.action)
        assertNull(startSecond.statusTransition)
    }

    @Test
    fun everyExistingTerminalStateIsAZeroMutationStalePlanForAnchorAndLateStart() {
        val deadline = GOAL + WAKE_RECOVERY_DEADLINE_OFFSET_MILLIS
        val row = anchor(kind = WakeRecoveryAnchorKind.GOAL_PLUS_30M, trigger = deadline)
        val envelope = delivery(kind = row.kind, trigger = deadline)
        val start = WakeEventIdentity("snapshot", WakeEventKind.START, GOAL - 1_000L)

        terminalStates().forEach { state ->
            val status = terminalStatus(state = state, executionEpoch = Long.MAX_VALUE)
            val before = status.copy()
            val anchorPlan = WakeRecoveryAnchorPlanner.plan(row, envelope, status)
            val startPlan = WakeLateStartPlanner.plan(start, goalEvent(), deadline, status)

            assertEquals(WakeRecoveryPlanAction.STALE_NO_OP, anchorPlan.action, state.name)
            assertNull(anchorPlan.anchorTransition, state.name)
            assertNull(anchorPlan.statusTransition, state.name)
            assertNull(anchorPlan.stimulus, state.name)
            assertEquals(emptySet(), anchorPlan.recommendations, state.name)
            assertEquals(WakeLateStartEventAction.STALE_NO_OP, startPlan.eventAction, state.name)
            assertEquals(
                WakeRecoveryPlanAction.STALE_NO_OP,
                startPlan.occurrencePlan.action,
                state.name,
            )
            assertNull(startPlan.occurrencePlan.anchorTransition, state.name)
            assertNull(startPlan.occurrencePlan.statusTransition, state.name)
            assertNull(startPlan.occurrencePlan.stimulus, state.name)
            assertEquals(emptySet(), startPlan.occurrencePlan.recommendations, state.name)
            assertEquals(before, status, state.name)
        }
    }

    @Test
    fun runStatusRejectsUnreachableLeaseAndLifecycleCombinations() {
        val invalid =
            listOf<() -> WakeRecoveryRunStatus>(
                { status().copy(serviceLeaseOwner = null) },
                { status().copy(serviceLeaseExpiresAtEpochMillis = null) },
                {
                    status()
                        .copy(
                            serviceLeaseOwner = null,
                            serviceLeaseExpiresAtEpochMillis = null,
                            heartbeatAtEpochMillis = GOAL,
                        )
                },
                { status().copy(completedAtEpochMillis = GOAL) },
                { status().copy(cancelledAtEpochMillis = GOAL) },
                { status().copy(failureReason = WakeFailureReason.NO_CONFIRMATION_DEADLINE) },
                { terminalStatus(WakeRunState.COMPLETED).copy(completedAtEpochMillis = null) },
                { terminalStatus(WakeRunState.COMPLETED).copy(cancelledAtEpochMillis = GOAL) },
                {
                    terminalStatus(WakeRunState.NO_CONFIRMATION).copy(completedAtEpochMillis = null)
                },
                {
                    terminalStatus(WakeRunState.NO_CONFIRMATION).copy(cancelledAtEpochMillis = GOAL)
                },
                { terminalStatus(WakeRunState.NO_CONFIRMATION).copy(failureReason = null) },
                { terminalStatus(WakeRunState.CANCELLED).copy(cancelledAtEpochMillis = null) },
                { terminalStatus(WakeRunState.CANCELLED).copy(completedAtEpochMillis = GOAL) },
            )
        invalid.forEachIndexed { index, create ->
            assertFailsWith<IllegalArgumentException>("case $index") { create() }
        }
    }

    @Test
    fun everyTerminalStatusRejectsOwnershipLeaseHeartbeatAndArmedFlags() {
        terminalStates().forEach { state ->
            val base = terminalStatus(state)
            listOf<() -> WakeRecoveryRunStatus>(
                    { base.copy(activeServiceOwnerToken = "owner") },
                    {
                        base.copy(
                            serviceLeaseOwner = "lease-owner",
                            serviceLeaseExpiresAtEpochMillis = GOAL,
                        )
                    },
                    {
                        base.copy(
                            serviceLeaseOwner = "lease-owner",
                            serviceLeaseExpiresAtEpochMillis = GOAL,
                            heartbeatAtEpochMillis = GOAL,
                        )
                    },
                    { base.copy(armedStart = true) },
                    { base.copy(armedGoal = true) },
                )
                .forEachIndexed { index, create ->
                    assertFailsWith<IllegalArgumentException>("${state.name}/$index") { create() }
                }
        }
    }

    @Test
    fun runStatusAcceptsRecoverableStaleOrMismatchedLeaseOwnership() {
        val status =
            status()
                .copy(
                    activeServiceOwnerToken = "active-owner",
                    serviceLeaseOwner = "stale-lease-owner",
                    serviceLeaseExpiresAtEpochMillis = GOAL - 1L,
                    heartbeatAtEpochMillis = GOAL - 2L,
                )

        assertEquals("active-owner", status.activeServiceOwnerToken)
        assertEquals("stale-lease-owner", status.serviceLeaseOwner)
    }

    @Test
    fun everyRunStatusTimestampAndExecutionEpochRejectNegativeValues() {
        val invalid =
            listOf<() -> WakeRecoveryRunStatus>(
                { status().copy(processedStartAtEpochMillis = -1L) },
                { status().copy(processedGoalAtEpochMillis = -1L) },
                { status().copy(executionEpoch = -1L) },
                { status().copy(serviceLeaseExpiresAtEpochMillis = -1L) },
                { status().copy(heartbeatAtEpochMillis = -1L) },
                { status().copy(startedAtEpochMillis = -1L) },
                {
                    terminalStatus(WakeRunState.COMPLETED).copy(completedAtEpochMillis = -1L)
                },
                {
                    terminalStatus(WakeRunState.CANCELLED).copy(cancelledAtEpochMillis = -1L)
                },
            )

        invalid.forEachIndexed { index, create ->
            assertFailsWith<IllegalArgumentException>("case $index") { create() }
        }
    }

    @Test
    fun epochsAreNonnegativeAndAnchorStateTransitionsCannotRearmImmutableRows() {
        assertFailsWith<IllegalArgumentException> {
            WakeRecoveryAnchorKind.GOAL_PRIMARY.triggerForGoalOrNull(-1L)
        }
        assertFailsWith<IllegalArgumentException> {
            delivery(trigger = -1L, receivedAt = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            delivery(receivedAt = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            status().copy(activeServiceOwnerToken = "x".repeat(MAX_WAKE_OWNER_TOKEN_UTF8_BYTES + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            status().copy(serviceLeaseOwner = "bad\u0000owner")
        }
        listOf(WakeRecoveryAnchorState.CONSUMED, WakeRecoveryAnchorState.CANCELLED).forEach {
            terminal ->
            assertFailsWith<IllegalArgumentException> {
                WakeRecoveryAnchorStateTransition(terminal, WakeRecoveryAnchorState.ARMED)
            }
        }
    }

    private fun status(
        state: WakeRunState = WakeRunState.ACTIVE,
        processedStartAt: Long? = 101L,
        processedGoalAt: Long? = null,
        startedAt: Long? = 102L,
        completedAt: Long? = null,
        cancelledAt: Long? = null,
        executionEpoch: Long = 3L,
    ) =
        WakeRecoveryRunStatus(
            state = state,
            processedStartAtEpochMillis = processedStartAt,
            processedGoalAtEpochMillis = processedGoalAt,
            activeServiceOwnerToken = "owner",
            executionEpoch = executionEpoch,
            serviceLeaseOwner = "owner",
            serviceLeaseExpiresAtEpochMillis = GOAL + 2_000_000L,
            heartbeatAtEpochMillis = GOAL,
            armedStart = true,
            armedGoal = true,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = completedAt,
            cancelledAtEpochMillis = cancelledAt,
            failureReason = null,
        )

    private fun terminalStates() =
        setOf(
            WakeRunState.COMPLETED,
            WakeRunState.NO_CONFIRMATION,
            WakeRunState.FAILED,
            WakeRunState.CANCELLED,
            WakeRunState.SUPERSEDED,
            WakeRunState.EXPIRED,
        )

    private fun terminalStatus(
        state: WakeRunState,
        executionEpoch: Long = 4L,
    ): WakeRecoveryRunStatus {
        require(state in terminalStates())
        return WakeRecoveryRunStatus(
            state = state,
            processedStartAtEpochMillis = 101L,
            processedGoalAtEpochMillis = 103L,
            activeServiceOwnerToken = null,
            executionEpoch = executionEpoch,
            serviceLeaseOwner = null,
            serviceLeaseExpiresAtEpochMillis = null,
            heartbeatAtEpochMillis = null,
            armedStart = false,
            armedGoal = false,
            startedAtEpochMillis = 102L,
            completedAtEpochMillis =
                if (state in setOf(WakeRunState.COMPLETED, WakeRunState.NO_CONFIRMATION)) 104L
                else null,
            cancelledAtEpochMillis = if (state == WakeRunState.CANCELLED) 104L else null,
            failureReason =
                if (state == WakeRunState.NO_CONFIRMATION) {
                    WakeFailureReason.NO_CONFIRMATION_DEADLINE
                } else {
                    null
                },
        )
    }

    private fun anchor(
        event: WakeEventIdentity = WakeEventIdentity("snapshot", WakeEventKind.GOAL, GOAL),
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PLUS_1M,
        trigger: Long = ANCHOR_TRIGGER,
        state: WakeRecoveryAnchorState = WakeRecoveryAnchorState.ARMED,
        pendingIntentIdentity: String = "wake-anchor:v1:snapshot:goal-plus-1m",
    ) = WakeRecoveryAnchorRow(event, kind, trigger, state, pendingIntentIdentity)

    private fun delivery(
        event: WakeEventIdentity = WakeEventIdentity("snapshot", WakeEventKind.GOAL, GOAL),
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PLUS_1M,
        trigger: Long = ANCHOR_TRIGGER,
        pendingIntentIdentity: String = "wake-anchor:v1:snapshot:goal-plus-1m",
        receivedAt: Long = trigger,
    ) = WakeRecoveryAnchorDelivery(event, kind, trigger, pendingIntentIdentity, receivedAt)

    private companion object {
        const val GOAL = 10_000_000L
        const val ANCHOR_TRIGGER = GOAL + 60_000L
    }
}
