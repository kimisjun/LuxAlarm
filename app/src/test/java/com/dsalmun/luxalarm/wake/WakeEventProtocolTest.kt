/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class WakeEventProtocolTest {
    @Test
    fun recoveryArrivalUsesDeliveredTriggerWithoutChangingPrimaryIdentity() {
        val event = WakeEventIdentity("snapshot", WakeEventKind.START, 1_000L)
        listOf(WakeRecoverySlotId.A to 1_015L, WakeRecoverySlotId.B to 1_030L).forEach {
            (arrivingSlot, deliveredTrigger) ->
            val selected = WakeRecoverySlot(WakeRecoverySlotState.FIRED, deliveredTrigger, 7L)
            val result =
                WakeDispatchReducer.reduce(
                    input(
                        event = event,
                        arrivingSlot = arrivingSlot,
                        arrivingRecoveryTriggerEpochMillis = deliveredTrigger,
                        slotA =
                            if (arrivingSlot == WakeRecoverySlotId.A) selected else input().slotA,
                        slotB =
                            if (arrivingSlot == WakeRecoverySlotId.B) selected else input().slotB,
                    )
                )

            assertEquals(WakeDispatchAction.REQUEST_DISPATCH, result.action, arrivingSlot.name)
            assertEquals(event.canonicalKey(), result.transition?.expectedEventKey)
            assertEquals(deliveredTrigger, result.transition?.expectedRecoveryTriggerAtEpochMillis)
            assertEquals(1_000L, event.expectedTriggerEpochMillis)
        }
    }

    @Test
    fun recoveryArrivalRejectsPrimaryTriggerWhenDurableSlotHasRecoveryTrigger() {
        val result =
            WakeDispatchReducer.reduce(
                input(
                    event = WakeEventIdentity("snapshot", WakeEventKind.START, 1_000L),
                    arrivingRecoveryTriggerEpochMillis = 1_000L,
                    slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1_015L, 7L),
                )
            )

        assertEquals(WakeDispatchAction.FAIL_CLOSED, result.action)
        assertEquals(null, result.transition)
    }

    @Test
    fun recoveryArrivalRequiresPairedNonnegativeSlotAndDeliveredTrigger() {
        assertFailsWith<IllegalArgumentException> {
            input(arrivingSlot = null, arrivingRecoveryTriggerEpochMillis = 1_015L)
        }
        assertFailsWith<IllegalArgumentException> {
            input(arrivingSlot = WakeRecoverySlotId.A, arrivingRecoveryTriggerEpochMillis = null)
        }
        assertFailsWith<IllegalArgumentException> {
            input(arrivingRecoveryTriggerEpochMillis = -1L)
        }
        input(arrivingSlot = null, arrivingRecoveryTriggerEpochMillis = null)
    }

    @Test
    fun reducerDoesNotDeriveRecoveryTriggerFromPrimaryEpoch() {
        val input =
            input(
                event = WakeEventIdentity("snapshot", WakeEventKind.START, Long.MAX_VALUE),
                arrivingRecoveryTriggerEpochMillis = 0L,
                slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 0L, 7L),
            )

        val result = WakeDispatchReducer.reduce(input)

        assertEquals(WakeDispatchAction.REQUEST_DISPATCH, result.action)
        assertEquals(0L, result.transition?.expectedRecoveryTriggerAtEpochMillis)
        assertEquals(Long.MAX_VALUE, input.event.expectedTriggerEpochMillis)
    }

    @Test
    fun canonicalEventKeyIsDeterministicAsciiAndBounded() {
        val identity = WakeEventIdentity("snapshot-한글:/|", WakeEventKind.START, 1_234L)

        val first = identity.canonicalKey()
        val second = identity.canonicalKey()

        assertEquals(first, second)
        assertTrue(first.startsWith("wake-event-v1:"))
        assertTrue(first.all { it.code in 0x20..0x7e })
        assertTrue(first.length <= MAX_WAKE_EVENT_KEY_ASCII_CHARS)
    }

    @Test
    fun canonicalEventKeyUsesExactLowercaseUtf8HexEncoding() {
        assertEquals(
            "wake-event-v1:3:41c3a9:START:7",
            WakeEventIdentity("Aé", WakeEventKind.START, 7L).canonicalKey(),
        )
        val utf8Hex = WakeEventIdentity("ÿ", WakeEventKind.GOAL, 9L).canonicalKey().split(':')[2]
        assertTrue(utf8Hex.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun canonicalEventKeyFramesSnapshotWithoutDelimiterCollisions() {
        val left = WakeEventIdentity("alpha:GOAL:12", WakeEventKind.START, 3L).canonicalKey()
        val right = WakeEventIdentity("alpha", WakeEventKind.GOAL, 12_003L).canonicalKey()

        assertNotEquals(left, right)
    }

    @Test
    fun canonicalEventKeyDistinguishesKindAndTrigger() {
        val start = WakeEventIdentity("snapshot", WakeEventKind.START, 100L).canonicalKey()
        val goal = WakeEventIdentity("snapshot", WakeEventKind.GOAL, 100L).canonicalKey()
        val movedStart = WakeEventIdentity("snapshot", WakeEventKind.START, 101L).canonicalKey()

        assertNotEquals(start, goal)
        assertNotEquals(start, movedStart)
    }

    @Test
    fun eventIdentityRejectsBlankOversizedOrMalformedSnapshotAndNegativeEpoch() {
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("", WakeEventKind.START, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity(" ", WakeEventKind.START, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity(
                "x".repeat(MAX_WAKE_SNAPSHOT_ID_UTF8_BYTES + 1),
                WakeEventKind.START,
                0L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("bad\u0000id", WakeEventKind.START, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("snapshot", WakeEventKind.START, -1L)
        }
    }

    @Test
    fun eventIdentityAcceptsMaximumSnapshotAndZeroEpoch() {
        val identity =
            WakeEventIdentity(
                "x".repeat(MAX_WAKE_SNAPSHOT_ID_UTF8_BYTES),
                WakeEventKind.GOAL,
                0L,
            )

        assertFalse(identity.canonicalKey().isEmpty())
    }

    @Test
    fun eventIdentityCountsAsciiBmpAndEmojiAtExactUtf8Boundary() {
        listOf(
                "x".repeat(192),
                "가".repeat(64),
                "😀".repeat(48),
                "ok😀",
            )
            .forEach { snapshot ->
                assertFalse(
                    WakeEventIdentity(snapshot, WakeEventKind.START, 0L).canonicalKey().isEmpty()
                )
            }

        listOf(
                "x".repeat(193),
                "가".repeat(64) + "x",
                "😀".repeat(48) + "x",
            )
            .forEach { snapshot ->
                assertFailsWith<IllegalArgumentException> {
                    WakeEventIdentity(snapshot, WakeEventKind.START, 0L)
                }
            }
    }

    @Test
    fun eventIdentityRejectsOnlyMalformedSurrogatesNotValidPairs() {
        WakeEventIdentity("valid-😀-pair", WakeEventKind.START, 0L)
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("isolated-high-\uD83D", WakeEventKind.START, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("isolated-low-\uDE00", WakeEventKind.START, 0L)
        }
    }

    @Test
    fun eventIdentityRejectsHugeSnapshotAtBoundedValidationLimit() {
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("x".repeat(10_000_000), WakeEventKind.START, 0L)
        }
    }

    @Test
    fun terminalIsAlwaysANoOpEvenWhenLeaseEvidenceIsStaleAndMismatched() {
        val input =
            input(
                state = WakeDispatchState.TERMINAL,
                scheduleOwner = WakeScheduleOwner.RESTORING,
                dispatchLeaseOwner = "old-dispatch",
                dispatchLeaseExpiresAt = 1L,
                executionOwner = "different-execution",
                serviceLeaseOwner = null,
                serviceLeaseExpiresAt = null,
                heartbeatAt = null,
                arrivingSlot = WakeRecoverySlotId.B,
                arrivingRecoveryTriggerEpochMillis = 500L,
                slotB = WakeRecoverySlot(WakeRecoverySlotState.CANCELLED, null, Long.MAX_VALUE),
                nowEpochMillis = 10_000L,
            )
        val before = input.copy()

        val result = WakeDispatchReducer.reduce(input)

        assertEquals(WakeDispatchAction.NO_OP_TERMINAL, result.action)
        assertEquals(null, result.transition)
        assertEquals(before, input)
    }

    @Test
    fun armedArrivingRecoverySlotFailsClosedWithoutMutatingInput() {
        val input = input(slotA = WakeRecoverySlot(WakeRecoverySlotState.ARMED, 500L, 1L))
        val before = input.copy()

        val result = WakeDispatchReducer.reduce(input)

        assertEquals(WakeDispatchAction.FAIL_CLOSED, result.action)
        assertEquals(null, result.transition)
        assertEquals(before, input)
    }

    @Test
    fun firedAndInFlightArrivalsEncodeCompleteCasForBothSlotsAndOwners() {
        listOf(WakeScheduleOwner.PREPARING_WAKE, WakeScheduleOwner.WAKE).forEach { owner ->
            WakeRecoverySlotId.entries.forEach { arrivingSlot ->
                listOf(WakeRecoverySlotState.FIRED, WakeRecoverySlotState.IN_FLIGHT).forEach {
                    slotState ->
                    val arriving = WakeRecoverySlot(slotState, 500L, 41L)
                    val input =
                        input(
                            scheduleOwner = owner,
                            arrivingSlot = arrivingSlot,
                            slotA =
                                if (arrivingSlot == WakeRecoverySlotId.A) {
                                    arriving
                                } else {
                                    WakeRecoverySlot(WakeRecoverySlotState.ARMED, 2_000L, 7L)
                                },
                            slotB =
                                if (arrivingSlot == WakeRecoverySlotId.B) {
                                    arriving
                                } else {
                                    WakeRecoverySlot(WakeRecoverySlotState.ARMED, 2_000L, 7L)
                                },
                        )
                    val before = input.copy()
                    val expectedAction =
                        if (owner == WakeScheduleOwner.PREPARING_WAKE) {
                            WakeDispatchAction.DEFER
                        } else {
                            WakeDispatchAction.REQUEST_DISPATCH
                        }
                    val expectedState =
                        if (owner == WakeScheduleOwner.PREPARING_WAKE) {
                            WakeDispatchState.DEFERRED
                        } else {
                            WakeDispatchState.DISPATCH_REQUESTED
                        }
                    val expectedAttempt = if (owner == WakeScheduleOwner.PREPARING_WAKE) 0L else 1L

                    val result = WakeDispatchReducer.reduce(input)

                    assertEquals(expectedAction, result.action, "$owner/$arrivingSlot/$slotState")
                    assertEquals(
                        WakeDispatchTransition(
                            expectedEventKey = input.event.canonicalKey(),
                            expectedState = WakeDispatchState.RECEIVED,
                            expectedDispatchAttemptId = 0L,
                            nextState = expectedState,
                            nextDispatchAttemptId = expectedAttempt,
                            needsRecovery = false,
                            expectedRecoverySlot = arrivingSlot,
                            expectedRecoverySlotState = slotState,
                            expectedRecoveryTriggerAtEpochMillis = 500L,
                            expectedRecoverySlotToken = 41L,
                            nextRecoverySlotState = WakeRecoverySlotState.CONSUMED,
                            nextRecoveryTriggerAtEpochMillis = null,
                            nextRecoverySlotToken = 42L,
                        ),
                        result.transition,
                        "$owner/$arrivingSlot/$slotState",
                    )
                    assertEquals(before, input)
                }
            }
        }
    }

    @Test
    fun invalidArrivalStateTriggerAndOverflowFailClosedSymmetrically() {
        WakeRecoverySlotId.entries.forEach { arrivingSlot ->
            val invalidSlots =
                WakeRecoverySlotState.entries
                    .filterNot {
                        it == WakeRecoverySlotState.FIRED || it == WakeRecoverySlotState.IN_FLIGHT
                    }
                    .map { state -> WakeRecoverySlot(state, 500L, 9L) } +
                    listOf(
                        WakeRecoverySlot(WakeRecoverySlotState.FIRED, null, 9L),
                        WakeRecoverySlot(WakeRecoverySlotState.FIRED, 501L, 9L),
                        WakeRecoverySlot(WakeRecoverySlotState.IN_FLIGHT, 500L, Long.MAX_VALUE),
                    )

            invalidSlots.forEach { arriving ->
                val input =
                    input(
                        state = WakeDispatchState.SERVICE_ACKED,
                        arrivingSlot = arrivingSlot,
                        arrivingRecoveryTriggerEpochMillis = 500L,
                        slotA =
                            if (arrivingSlot == WakeRecoverySlotId.A) arriving else input().slotA,
                        slotB =
                            if (arrivingSlot == WakeRecoverySlotId.B) arriving else input().slotB,
                    )
                val before = input.copy()

                val result = WakeDispatchReducer.reduce(input)

                assertEquals(
                    WakeDispatchAction.FAIL_CLOSED,
                    result.action,
                    "$arrivingSlot/$arriving",
                )
                assertEquals(null, result.transition, "$arrivingSlot/$arriving")
                assertEquals(before, input, "$arrivingSlot/$arriving")
            }
        }
    }

    @Test
    fun immutableAnchorArrivalDispatchesWithoutRecoverySlotFence() {
        val input = input(arrivingSlot = null)

        val result = WakeDispatchReducer.reduce(input)

        assertEquals(WakeDispatchAction.REQUEST_DISPATCH, result.action)
        assertEquals(null, result.transition?.expectedRecoverySlot)
        assertEquals(null, result.transition?.expectedRecoverySlotState)
        assertEquals(null, result.transition?.expectedRecoveryTriggerAtEpochMillis)
        assertEquals(null, result.transition?.expectedRecoverySlotToken)
        assertEquals(null, result.transition?.nextRecoverySlotState)
        assertEquals(null, result.transition?.nextRecoveryTriggerAtEpochMillis)
        assertEquals(null, result.transition?.nextRecoverySlotToken)
    }

    @Test
    fun preparingWakeDefersEveryNonterminalArrivalWithoutChangingAttempt() {
        WakeDispatchState.entries
            .filterNot { it == WakeDispatchState.TERMINAL }
            .forEach { state ->
                val input =
                    input(
                        state = state,
                        scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                        dispatchAttemptId = 7L,
                    )

                val result = WakeDispatchReducer.reduce(input)

                assertEquals(WakeDispatchAction.DEFER, result.action, state.name)
                assertEquals(
                    WakeDispatchTransition(
                        expectedEventKey = input.event.canonicalKey(),
                        expectedState = state,
                        expectedDispatchAttemptId = 7L,
                        nextState = WakeDispatchState.DEFERRED,
                        nextDispatchAttemptId = 7L,
                        needsRecovery = false,
                        expectedRecoverySlot = WakeRecoverySlotId.A,
                        expectedRecoverySlotState = WakeRecoverySlotState.FIRED,
                        expectedRecoveryTriggerAtEpochMillis = 500L,
                        expectedRecoverySlotToken = 1L,
                        nextRecoverySlotState = WakeRecoverySlotState.CONSUMED,
                        nextRecoveryTriggerAtEpochMillis = null,
                        nextRecoverySlotToken = 2L,
                    ),
                    result.transition,
                    state.name,
                )
            }
    }

    @Test
    fun preparingWakeDeferredArrivalFencesEitherSlotAndAnchorUsesNoFence() {
        val cases =
            listOf(
                input(scheduleOwner = WakeScheduleOwner.PREPARING_WAKE) to WakeRecoverySlotId.A,
                input(
                    scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                    arrivingSlot = WakeRecoverySlotId.B,
                    slotB = WakeRecoverySlot(WakeRecoverySlotState.IN_FLIGHT, 500L, 12L),
                ) to WakeRecoverySlotId.B,
                input(
                    scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                    arrivingSlot = null,
                ) to null,
            )

        cases.forEach { (input, expectedSlot) ->
            val before = input.copy()
            val result = WakeDispatchReducer.reduce(input)
            val expectedToken =
                when (expectedSlot) {
                    WakeRecoverySlotId.A -> 1L
                    WakeRecoverySlotId.B -> 12L
                    null -> null
                }

            assertEquals(WakeDispatchAction.DEFER, result.action, expectedSlot.toString())
            assertEquals(WakeDispatchState.DEFERRED, result.transition?.nextState)
            assertEquals(input.dispatchAttemptId, result.transition?.nextDispatchAttemptId)
            assertEquals(expectedSlot, result.transition?.expectedRecoverySlot)
            assertEquals(
                if (expectedSlot == null) null else input.arrivingRecoverySlot().state,
                result.transition?.expectedRecoverySlotState,
            )
            assertEquals(
                if (expectedSlot == null) null else 500L,
                result.transition?.expectedRecoveryTriggerAtEpochMillis,
            )
            assertEquals(expectedToken, result.transition?.expectedRecoverySlotToken)
            assertEquals(
                if (expectedSlot == null) null else WakeRecoverySlotState.CONSUMED,
                result.transition?.nextRecoverySlotState,
            )
            assertEquals(null, result.transition?.nextRecoveryTriggerAtEpochMillis)
            assertEquals(expectedToken?.plus(1L), result.transition?.nextRecoverySlotToken)
            assertEquals(before, input)
        }
    }

    @Test
    fun preparingWakeInvalidOrOverflowArrivalFailsClosed() {
        val cases =
            listOf(
                input(
                    scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                    slotA = WakeRecoverySlot(WakeRecoverySlotState.CONSUMED, 500L, 1L),
                ),
                input(
                    scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                    arrivingSlot = WakeRecoverySlotId.B,
                    arrivingRecoveryTriggerEpochMillis = 500L,
                    slotB = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 501L, 2L),
                ),
                input(
                    scheduleOwner = WakeScheduleOwner.PREPARING_WAKE,
                    arrivingSlot = WakeRecoverySlotId.B,
                    slotB = WakeRecoverySlot(WakeRecoverySlotState.IN_FLIGHT, 500L, Long.MAX_VALUE),
                ),
            )

        cases.forEach { input ->
            val before = input.copy()
            val result = WakeDispatchReducer.reduce(input)

            assertEquals(WakeDispatchAction.FAIL_CLOSED, result.action, input.toString())
            assertEquals(null, result.transition, input.toString())
            assertEquals(before, input)
        }
    }

    @Test
    fun serviceAckIsNoOpOnlyForMatchingLiveLeaseAndFreshHeartbeat() {
        val healthy =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                dispatchAttemptId = 4L,
                dispatchLeaseOwner = "owner-4",
                dispatchLeaseExpiresAt = 900L,
                executionOwner = "owner-4",
                serviceLeaseOwner = "owner-4",
                serviceLeaseExpiresAt = 1_001L,
                heartbeatAt = 900L,
                nowEpochMillis = 1_000L,
            )

        val result = WakeDispatchReducer.reduce(healthy)

        assertEquals(WakeDispatchAction.NO_OP_HEALTHY_ACK, result.action)
        assertEquals(null, result.transition)
    }

    @Test
    fun serviceAckOwnerLeaseAndHeartbeatFailuresRequestRedelivery() {
        val healthy =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                dispatchAttemptId = 4L,
                dispatchLeaseOwner = "owner-4",
                executionOwner = "owner-4",
                serviceLeaseOwner = "owner-4",
                serviceLeaseExpiresAt = 1_001L,
                heartbeatAt = 900L,
                nowEpochMillis = 1_000L,
            )
        val invalidCases =
            linkedMapOf(
                "null dispatch owner" to healthy.copy(dispatchLeaseOwner = null),
                "execution owner mismatch" to healthy.copy(executionOwner = "other"),
                "null execution owner" to healthy.copy(executionOwner = null),
                "service owner mismatch" to healthy.copy(serviceLeaseOwner = "other"),
                "null service owner" to healthy.copy(serviceLeaseOwner = null),
                "null service expiry" to healthy.copy(serviceLeaseExpiresAt = null),
                "expired service lease" to healthy.copy(serviceLeaseExpiresAt = 1_000L),
                "null heartbeat" to healthy.copy(heartbeatAt = null),
                "stale heartbeat" to healthy.copy(heartbeatAt = 899L),
                "future heartbeat" to healthy.copy(heartbeatAt = 1_001L),
                "missing execution epoch" to healthy.copy(executionEpoch = 0L),
            )

        invalidCases.forEach { (label, input) ->
            val result = WakeDispatchReducer.reduce(input)

            assertEquals(WakeDispatchAction.REQUEST_DISPATCH, result.action, label)
            assertEquals(5L, result.transition?.nextDispatchAttemptId, label)
            assertEquals(WakeDispatchState.DISPATCH_REQUESTED, result.transition?.nextState, label)
        }
    }

    @Test
    fun oppositeSlotIsUsableRecoveryOnlyWhenArmedStrictlyAfterNow() {
        val base =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                event = WakeEventIdentity("snapshot", WakeEventKind.START, 1_000L),
                arrivingSlot = WakeRecoverySlotId.A,
                slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1_000L, 4L),
                nowEpochMillis = 1_000L,
            )
        val cases =
            listOf(
                WakeRecoverySlot(WakeRecoverySlotState.ARMED, 1_001L, 5L) to false,
                WakeRecoverySlot(WakeRecoverySlotState.ARMED, 1_000L, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.ARMED, 999L, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.ARMED, null, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1_001L, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.IN_FLIGHT, 1_001L, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.CONSUMED, 1_001L, 5L) to true,
                WakeRecoverySlot(WakeRecoverySlotState.CANCELLED, 1_001L, 5L) to true,
            )

        cases.forEach { (opposite, expectedNeedsRecovery) ->
            assertEquals(
                !expectedNeedsRecovery,
                opposite.isUsableFutureRecovery(1_000L),
                opposite.toString(),
            )
            val result = WakeDispatchReducer.reduce(base.copy(slotB = opposite))

            assertEquals(
                expectedNeedsRecovery,
                result.transition?.needsRecovery,
                opposite.toString(),
            )
        }
    }

    @Test
    fun oppositeSlotSelectionIsSymmetricAndSimultaneousOverdueSlotsAreNotFuture() {
        val slotA = WakeRecoverySlot(WakeRecoverySlotState.ARMED, 1_000L, 8L)
        val arrivingSlotB = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1_001L, 9L)
        val arrivingB =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                event = WakeEventIdentity("snapshot", WakeEventKind.START, 1_001L),
                arrivingSlot = WakeRecoverySlotId.B,
                slotA = slotA,
                slotB = arrivingSlotB,
                nowEpochMillis = 1_000L,
            )
        val simultaneousOverdue =
            arrivingB.copy(
                event = WakeEventIdentity("snapshot", WakeEventKind.START, 1_000L),
                arrivingRecoveryTriggerEpochMillis = 1_000L,
                slotB = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1_000L, 9L),
            )

        assertEquals(true, WakeDispatchReducer.reduce(arrivingB).transition?.needsRecovery)
        assertEquals(
            true,
            WakeDispatchReducer.reduce(simultaneousOverdue).transition?.needsRecovery,
        )
    }

    @Test
    fun attemptOrRecoveryTokenOverflowFailsClosedWithoutMutatingInput() {
        val maxAttempt =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                dispatchAttemptId = Long.MAX_VALUE,
            )
        val maxToken =
            input(
                state = WakeDispatchState.SERVICE_ACKED,
                slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 500L, Long.MAX_VALUE),
            )

        listOf(maxAttempt, maxToken).forEach { input ->
            val before = input.copy()
            val result = WakeDispatchReducer.reduce(input)

            assertEquals(WakeDispatchAction.FAIL_CLOSED, result.action)
            assertEquals(null, result.transition)
            assertEquals(before, input)
        }
    }

    @Test
    fun wakeOwnerStateMatrixRequestsFreshAndRecoverableDispatches() {
        val cases =
            linkedMapOf(
                WakeDispatchState.RECEIVED to input(state = WakeDispatchState.RECEIVED),
                WakeDispatchState.DEFERRED to input(state = WakeDispatchState.DEFERRED),
                WakeDispatchState.DISPATCH_REQUESTED to
                    input(
                        state = WakeDispatchState.DISPATCH_REQUESTED,
                        dispatchLeaseOwner = "expired",
                        dispatchLeaseExpiresAt = 1_000L,
                    ),
                WakeDispatchState.SERVICE_ACKED to input(state = WakeDispatchState.SERVICE_ACKED),
            )

        cases.forEach { (state, input) ->
            val first = WakeDispatchReducer.reduce(input)
            val duplicate = WakeDispatchReducer.reduce(input)

            assertEquals(WakeDispatchAction.REQUEST_DISPATCH, first.action, state.name)
            assertEquals(first, duplicate, state.name)
            assertEquals(state, first.transition?.expectedState, state.name)
            assertEquals(0L, first.transition?.expectedDispatchAttemptId, state.name)
            assertEquals(1L, first.transition?.nextDispatchAttemptId, state.name)
            assertEquals(WakeRecoverySlotId.A, first.transition?.expectedRecoverySlot, state.name)
            assertEquals(WakeRecoverySlotState.FIRED, first.transition?.expectedRecoverySlotState)
            assertEquals(500L, first.transition?.expectedRecoveryTriggerAtEpochMillis)
            assertEquals(1L, first.transition?.expectedRecoverySlotToken, state.name)
            assertEquals(WakeRecoverySlotState.CONSUMED, first.transition?.nextRecoverySlotState)
            assertEquals(null, first.transition?.nextRecoveryTriggerAtEpochMillis)
            assertEquals(2L, first.transition?.nextRecoverySlotToken, state.name)
            assertEquals(WakeScheduleOwner.WAKE, input.scheduleOwner, state.name)
        }
    }

    @Test
    fun activeDispatchLeaseIsTheOnlyDispatchRequestedNoOp() {
        val active =
            input(
                state = WakeDispatchState.DISPATCH_REQUESTED,
                dispatchLeaseOwner = "dispatcher",
                dispatchLeaseExpiresAt = 1_001L,
            )

        val result = WakeDispatchReducer.reduce(active)

        assertEquals(WakeDispatchAction.NO_OP_ACTIVE_DISPATCH, result.action)
        assertEquals(null, result.transition)
    }

    @Test
    fun legacyAndRestoringOwnersFailClosedWithoutOwnerMutation() {
        listOf(WakeScheduleOwner.LEGACY, WakeScheduleOwner.RESTORING).forEach { owner ->
            WakeDispatchState.entries
                .filterNot { it == WakeDispatchState.TERMINAL }
                .forEach { state ->
                    val input = input(state = state, scheduleOwner = owner)

                    val result = WakeDispatchReducer.reduce(input)

                    assertEquals(WakeDispatchAction.FAIL_CLOSED, result.action, "$owner/$state")
                    assertEquals(null, result.transition, "$owner/$state")
                    assertEquals(owner, input.scheduleOwner, "$owner/$state")
                }
        }
    }

    @Test
    fun inputValidationBoundsTimesCountersOwnersAndHeartbeatAge() {
        assertFailsWith<IllegalArgumentException> { input(dispatchAttemptId = -1L) }
        assertFailsWith<IllegalArgumentException> {
            input(slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 1L, -1L))
        }
        assertFailsWith<IllegalArgumentException> { input(nowEpochMillis = -1L) }
        assertFailsWith<IllegalArgumentException> { input(dispatchLeaseExpiresAt = -1L) }
        assertFailsWith<IllegalArgumentException> { input(dispatchLeaseOwner = " ") }
        input(dispatchLeaseOwner = "a".repeat(256))
        assertFailsWith<IllegalArgumentException> {
            input(dispatchLeaseOwner = "a".repeat(257))
        }
        input(dispatchLeaseOwner = "가".repeat(85) + "a")
        assertFailsWith<IllegalArgumentException> {
            input(dispatchLeaseOwner = "가".repeat(85) + "aa")
        }
        input(dispatchLeaseOwner = "owner-😀")
        assertFailsWith<IllegalArgumentException> { input(dispatchLeaseOwner = "bad-\uD83D") }
        assertFailsWith<IllegalArgumentException> { input(dispatchLeaseOwner = "bad-\uDE00") }
        assertFailsWith<IllegalArgumentException> { input(maxHeartbeatAgeMillis = 0L) }
    }

    @Test
    fun recoveryCasExpectationIncludesStateAndRejectsChangedTrigger() {
        val fired = input(slotA = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 500L, 9L))
        val inFlight = fired.copy(slotA = fired.slotA.copy(state = WakeRecoverySlotState.IN_FLIGHT))

        val firedTransition = WakeDispatchReducer.reduce(fired).transition
        val inFlightTransition = WakeDispatchReducer.reduce(inFlight).transition

        assertNotEquals(firedTransition, inFlightTransition)
        assertEquals(WakeRecoverySlotState.FIRED, firedTransition?.expectedRecoverySlotState)
        assertEquals(WakeRecoverySlotState.IN_FLIGHT, inFlightTransition?.expectedRecoverySlotState)
        val changedTrigger = fired.copy(slotA = fired.slotA.copy(triggerAtEpochMillis = 501L))
        assertEquals(
            WakeDispatchAction.FAIL_CLOSED,
            WakeDispatchReducer.reduce(changedTrigger).action,
        )
        assertEquals(null, WakeDispatchReducer.reduce(changedTrigger).transition)
    }

    private fun WakeDispatchInput.arrivingRecoverySlot(): WakeRecoverySlot =
        when (arrivingSlot) {
            WakeRecoverySlotId.A -> slotA
            WakeRecoverySlotId.B -> slotB
            null -> error("Anchor has no recovery slot")
        }

    private fun input(
        event: WakeEventIdentity = WakeEventIdentity("snapshot", WakeEventKind.START, 500L),
        state: WakeDispatchState = WakeDispatchState.RECEIVED,
        scheduleOwner: WakeScheduleOwner = WakeScheduleOwner.WAKE,
        dispatchAttemptId: Long = 0L,
        dispatchLeaseOwner: String? = null,
        dispatchLeaseExpiresAt: Long? = null,
        executionOwner: String? = null,
        executionEpoch: Long = 1L,
        serviceLeaseOwner: String? = null,
        serviceLeaseExpiresAt: Long? = null,
        heartbeatAt: Long? = null,
        arrivingSlot: WakeRecoverySlotId? = WakeRecoverySlotId.A,
        slotA: WakeRecoverySlot = WakeRecoverySlot(WakeRecoverySlotState.FIRED, 500L, 1L),
        slotB: WakeRecoverySlot = WakeRecoverySlot(WakeRecoverySlotState.ARMED, 2_000L, 2L),
        arrivingRecoveryTriggerEpochMillis: Long? =
            when (arrivingSlot) {
                WakeRecoverySlotId.A -> slotA.triggerAtEpochMillis
                WakeRecoverySlotId.B -> slotB.triggerAtEpochMillis
                null -> null
            },
        nowEpochMillis: Long = 1_000L,
        maxHeartbeatAgeMillis: Long = 100L,
    ): WakeDispatchInput =
        WakeDispatchInput(
            event = event,
            state = state,
            scheduleOwner = scheduleOwner,
            dispatchAttemptId = dispatchAttemptId,
            dispatchLeaseOwner = dispatchLeaseOwner,
            dispatchLeaseExpiresAt = dispatchLeaseExpiresAt,
            executionOwner = executionOwner,
            executionEpoch = executionEpoch,
            serviceLeaseOwner = serviceLeaseOwner,
            serviceLeaseExpiresAt = serviceLeaseExpiresAt,
            heartbeatAt = heartbeatAt,
            arrivingSlot = arrivingSlot,
            arrivingRecoveryTriggerEpochMillis = arrivingRecoveryTriggerEpochMillis,
            slotA = slotA,
            slotB = slotB,
            nowEpochMillis = nowEpochMillis,
            maxHeartbeatAgeMillis = maxHeartbeatAgeMillis,
        )
}
