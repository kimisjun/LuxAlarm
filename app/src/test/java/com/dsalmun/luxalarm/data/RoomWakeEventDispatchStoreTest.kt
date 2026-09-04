/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorization
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSource
import com.dsalmun.luxalarm.wake.WakeDispatchSourceKind
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomWakeEventDispatchStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var store: RoomWakeEventDispatchStore
    private val event = WakeEventIdentity("dispatch-snapshot", WakeEventKind.START, 1_000L)

    @Test
    fun primaryCommitsExactLeaseAndReturnsAuthorizationFromPostimage() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 2L, attemptCount = 4L)
        val identity = WakePendingIntentData.primary(event)
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                identity,
                1_100L,
            )

        val result = store.reduce(event, source, 1_100L, maxHeartbeatAgeMillis = 500L)

        assertEquals(WakeEventStoreOutcome.AUTHORIZED_NEW, result.outcome)
        val authorization = requireNotNull(result.authorization)
        val committed =
            requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", committed.state)
        assertEquals(3L, committed.dispatchAttemptId)
        assertEquals(5L, committed.attemptCount)
        assertEquals(1_100L, committed.lastAttemptAt)
        assertEquals(61_100L, committed.leaseExpiresAt)
        assertEquals(committed.leaseOwner, authorization.leaseOwner)
        assertEquals(committed.dispatchAttemptId, authorization.dispatchAttemptId)
        assertEquals(1L, authorization.scheduleGeneration)
        assertEquals(0L, authorization.expectedExecutionEpoch)
        assertEquals(source, authorization.source)
        assertEquals(0, committed.armedPrimary)
    }

    @Test
    fun bothDynamicSlotsInEveryLiveStateReturnExactCommittedAuthorization() {
        WakeRecoverySlotId.entries.forEach { slot ->
            listOf("ARMED", "FIRED", "IN_FLIGHT").forEach { slotState ->
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
                insertDispatch(
                    state = "RECEIVED",
                    slotAAt = if (slot == WakeRecoverySlotId.A) 1_000L else null,
                    slotAState = if (slot == WakeRecoverySlotId.A) slotState else "CONSUMED",
                    slotAToken = if (slot == WakeRecoverySlotId.A) 7L else 0L,
                    slotBAt = if (slot == WakeRecoverySlotId.B) 1_000L else null,
                    slotBState = if (slot == WakeRecoverySlotId.B) slotState else "CONSUMED",
                    slotBToken = if (slot == WakeRecoverySlotId.B) 7L else 0L,
                )
                val identity = WakePendingIntentData.dynamic(event, slot, 7L, 1_000L)
                val source =
                    WakeDispatchAuthorizationFactory.canonicalSource(
                        event,
                        if (slot == WakeRecoverySlotId.A) {
                            WakeDispatchSourceKind.START_DYNAMIC_A
                        } else {
                            WakeDispatchSourceKind.START_DYNAMIC_B
                        },
                        identity,
                        1_100L,
                    )
                val expected =
                    WakeDispatchAuthorizationFactory.create(event, 1L, 1L, 0L, 61_100L, source)

                val result = store.reduce(event, source, 1_100L, 500L)

                assertEquals(
                    WakeEventStoreOutcome.AUTHORIZED_NEW,
                    result.outcome,
                    "$slot/$slotState",
                )
                assertAuthorizationEquals(
                    expected,
                    requireNotNull(result.authorization),
                    "$slot/$slotState",
                )
                val committed = requireNotNull(result.dispatch)
                assertEquals(expected.leaseOwner, committed.leaseOwner, "$slot/$slotState")
                assertEquals(expected.leaseExpiresAt, committed.leaseExpiresAt, "$slot/$slotState")
                assertEquals(
                    "CONSUMED",
                    if (slot == WakeRecoverySlotId.A) committed.recoverySlotAState
                    else committed.recoverySlotBState,
                )
            }
        }
    }

    @Test
    fun earlyExactRecoveryFailsClosedWithZeroWritesForAllLiveStatesAndBothSlots() {
        listOf("ARMED", "FIRED", "IN_FLIGHT").forEach { slotState ->
            WakeRecoverySlotId.entries.forEach { slot ->
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
                insertDispatch(
                    state = "RECEIVED",
                    slotAAt = if (slot == WakeRecoverySlotId.A) 1_101L else null,
                    slotAState = if (slot == WakeRecoverySlotId.A) slotState else "CONSUMED",
                    slotAToken = 7L,
                    slotBAt = if (slot == WakeRecoverySlotId.B) 1_101L else null,
                    slotBState = if (slot == WakeRecoverySlotId.B) slotState else "CONSUMED",
                    slotBToken = 7L,
                )
                val before = allTablesFingerprint()

                val result = store.reduce(event, recoveryArrival(slot, 7L, 1_101L), 1_100L, 500L)

                assertEquals(
                    WakeEventStoreOutcome.FAIL_CLOSED,
                    result.outcome,
                    "$slot/$slotState",
                )
                assertEquals(before, allTablesFingerprint(), "$slot/$slotState")
            }
        }
    }

    @Test
    fun exactRecoveryAtItsTriggerIsAcceptedForBothSlotsAndOwners() {
        listOf("PREPARING_WAKE", "WAKE").forEach { owner ->
            WakeRecoverySlotId.entries.forEach { slot ->
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE migration_state SET schedule_owner = ? WHERE id = 1",
                    arrayOf(owner),
                )
                insertDispatch(
                    state = "RECEIVED",
                    slotAAt = if (slot == WakeRecoverySlotId.A) 1_100L else null,
                    slotAState = if (slot == WakeRecoverySlotId.A) "ARMED" else "CONSUMED",
                    slotAToken = 7L,
                    slotBAt = if (slot == WakeRecoverySlotId.B) 1_100L else null,
                    slotBState = if (slot == WakeRecoverySlotId.B) "ARMED" else "CONSUMED",
                    slotBToken = 7L,
                )

                val result = store.reduce(event, recoveryArrival(slot, 7L, 1_100L), 1_100L, 500L)

                assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome, "$owner/$slot")
                assertEquals(
                    if (owner == "WAKE") "DISPATCH_REQUESTED" else "DEFERRED",
                    result.dispatch?.state,
                    "$owner/$slot",
                )
                assertEquals(
                    "CONSUMED",
                    if (slot == WakeRecoverySlotId.A) result.dispatch?.recoverySlotAState
                    else result.dispatch?.recoverySlotBState,
                )
            }
        }
    }

    @Test
    fun recoveryAuthenticatesDeliveredTriggerAgainstSlotNotPrimaryAndPreservesPrimaryIdentity() {
        listOf(WakeRecoverySlotId.A to 1_015L, WakeRecoverySlotId.B to 1_030L).forEachIndexed {
            index,
            (slot, trigger) ->
            if (index > 0) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            }
            insertDispatch(
                state = "RECEIVED",
                slotAAt = if (slot == WakeRecoverySlotId.A) trigger else null,
                slotAState = if (slot == WakeRecoverySlotId.A) "FIRED" else "CONSUMED",
                slotAToken = 7L,
                slotBAt = if (slot == WakeRecoverySlotId.B) trigger else null,
                slotBState = if (slot == WakeRecoverySlotId.B) "FIRED" else "CONSUMED",
                slotBToken = 7L,
            )

            val result =
                store.reduce(
                    event,
                    recoveryArrival(
                        slot,
                        deliveredToken = 7L,
                        deliveredTriggerEpochMillis = trigger,
                    ),
                    1_100L,
                    500L,
                )

            assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome, slot.name)
            assertEquals(event.expectedTriggerEpochMillis, result.dispatch?.expectedTriggerEpochMs)
        }
    }

    @Test
    fun armedFiredAndInFlightRecoveryStatesUseTheSameExactTokenAndTriggerFence() {
        listOf("ARMED", "FIRED", "IN_FLIGHT").forEachIndexed { index, slotState ->
            if (index > 0) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            }
            insertDispatch(
                state = "RECEIVED",
                slotAAt = 1_015L,
                slotAState = slotState,
                slotAToken = 7L,
            )
            val before = dispatchFingerprint()
            val wrongToken = recoveryArrival(WakeRecoverySlotId.A, 6L, 1_015L)
            val wrongTrigger = recoveryArrival(WakeRecoverySlotId.A, 7L, 1_016L)

            assertEquals(
                WakeEventStoreOutcome.STALE_DELIVERY,
                store.reduce(event, wrongToken, 1_100L, 500L).outcome,
            )
            assertEquals(before, dispatchFingerprint(), slotState)
            assertEquals(
                WakeEventStoreOutcome.FAIL_CLOSED,
                store.reduce(event, wrongTrigger, 1_100L, 500L).outcome,
            )
            assertEquals(before, dispatchFingerprint(), slotState)

            val applied =
                store.reduce(
                    event,
                    recoveryArrival(WakeRecoverySlotId.A, 7L, 1_015L),
                    1_100L,
                    500L,
                )
            assertEquals(WakeEventStoreOutcome.APPLIED, applied.outcome, slotState)
            assertEquals("DISPATCH_REQUESTED", applied.dispatch?.state, slotState)
            assertEquals("CONSUMED", applied.dispatch?.recoverySlotAState, slotState)
            assertNull(applied.dispatch?.recoverySlotAAt, slotState)
            assertEquals(8L, applied.dispatch?.recoverySlotAToken, slotState)
        }
    }

    @Test
    fun wrongDeliveredRecoveryTriggerFailsClosedWithoutWrites() {
        insertDispatch(
            state = "RECEIVED",
            slotAAt = 1_015L,
            slotAState = "FIRED",
            slotAToken = 7L,
        )
        val before = dispatchFingerprint()

        val result =
            store.reduce(
                event,
                recoveryArrival(
                    WakeRecoverySlotId.A,
                    deliveredToken = 7L,
                    deliveredTriggerEpochMillis = 1_000L,
                ),
                1_100L,
                500L,
            )

        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, dispatchFingerprint())
    }

    @Test
    fun authenticatedRecoveryEventIdentityMustMatchStoreEvent() {
        insertDispatch(
            state = "RECEIVED",
            slotAAt = 1_015L,
            slotAState = "FIRED",
            slotAToken = 7L,
        )
        val before = dispatchFingerprint()
        val otherEvent = WakeEventIdentity("other-snapshot", WakeEventKind.START, 1_000L)

        val result =
            store.reduce(
                event,
                recoveryArrival(
                    WakeRecoverySlotId.A,
                    deliveredToken = 7L,
                    deliveredTriggerEpochMillis = 1_015L,
                    eventIdentity = otherEvent,
                ),
                1_100L,
                500L,
            )

        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, dispatchFingerprint())
    }

    @Test
    fun recoveryArrivalRejectsNegativeDeliveredTriggerAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            recoveryArrival(WakeRecoverySlotId.A, 7L, -1L)
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-event-dispatch-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeEventDispatchStore(database)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'WAKE', active_generation = 1 WHERE id = 1"
        )
        database.wakeRunStorageDao().createSnapshot(snapshot(event.snapshotId), 900L)
    }

    @Test
    fun noncanonicalTypedSourcesFailClosedBeforeMutationForPreparingAndWakeOwners() {
        val goal = WakeEventIdentity(event.snapshotId, WakeEventKind.GOAL, 2_000L)
        val malformedSources =
            listOf(
                WakeDispatchSource(
                    WakeDispatchSourceKind.START_DYNAMIC_B,
                    WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_000L),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_PRIMARY,
                    WakePendingIntentData.primary(event),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.START_PRIMARY,
                    WakePendingIntentData.primary(goal),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.START_DYNAMIC_A,
                    WakePendingIntentData.primary(event),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.START_DYNAMIC_A,
                    WakePendingIntentData.dynamic(event, WakeRecoverySlotId.B, 7L, 1_000L),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_DYNAMIC_B,
                    WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.A, 7L, 1_000L),
                    1_100L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_DYNAMIC_A,
                    WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.B, 7L, 1_000L),
                    1_100L,
                ),
            )
        listOf("PREPARING_WAKE", "WAKE").forEach { owner ->
            malformedSources.forEachIndexed { index, source ->
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE migration_state SET schedule_owner = ? WHERE id = 1",
                    arrayOf(owner),
                )
                insertDispatch(
                    state = "RECEIVED",
                    slotAAt = 1_000L,
                    slotAState = "FIRED",
                    slotAToken = 7L,
                )
                val before = allTablesFingerprint()

                val result = store.reduce(event, source, 1_100L, 500L)

                assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome, "$owner/$index")
                assertNull(result.authorization, "$owner/$index")
                assertEquals(before, allTablesFingerprint(), "$owner/$index")
            }
        }
    }

    @Test
    fun canonicalStartAndGoalSourcesWithMismatchedReceiptFailClosedWithoutWrites() {
        val goal = WakeEventIdentity(event.snapshotId, WakeEventKind.GOAL, 2_000L)
        val cases =
            listOf(
                Triple(
                    event,
                    WakeDispatchSourceKind.START_PRIMARY,
                    WakePendingIntentData.primary(event),
                ),
                Triple(
                    event,
                    WakeDispatchSourceKind.START_DYNAMIC_A,
                    WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_000L),
                ),
                Triple(
                    event,
                    WakeDispatchSourceKind.START_DYNAMIC_B,
                    WakePendingIntentData.dynamic(event, WakeRecoverySlotId.B, 7L, 1_000L),
                ),
                Triple(
                    goal,
                    WakeDispatchSourceKind.GOAL_PRIMARY,
                    WakePendingIntentData.primary(goal),
                ),
                Triple(
                    goal,
                    WakeDispatchSourceKind.GOAL_DYNAMIC_A,
                    WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.A, 7L, 1_000L),
                ),
                Triple(
                    goal,
                    WakeDispatchSourceKind.GOAL_DYNAMIC_B,
                    WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.B, 7L, 1_000L),
                ),
            )
        cases.forEach { (candidateEvent, kind, identity) ->
            val source =
                WakeDispatchAuthorizationFactory.canonicalSource(
                    candidateEvent,
                    kind,
                    identity,
                    1_100L,
                )
            val before = allTablesFingerprint()
            val result = store.reduce(candidateEvent, source, 1_101L, 500L)
            assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome, kind.name)
            assertNull(result.authorization, kind.name)
            assertEquals(before, allTablesFingerprint(), kind.name)
        }
    }

    @Test
    fun eventTriggerMustExactlyMatchItsSnapshotTriggerBeforeMutation() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_snapshot SET wake_start_epoch_ms = 1001 WHERE id = ?",
            arrayOf(event.snapshotId),
        )
        insertDispatch(state = "RECEIVED")
        val startBefore = allTablesFingerprint()

        val startResult = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, startResult.outcome)
        assertNull(startResult.authorization)
        assertEquals(startBefore, allTablesFingerprint())

        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_snapshot SET wake_start_epoch_ms = 1000, goal_epoch_ms = 2001 WHERE id = ?",
            arrayOf(event.snapshotId),
        )
        val goal = WakeEventIdentity(event.snapshotId, WakeEventKind.GOAL, 2_000L)
        insertDispatch(
            state = "RECEIVED",
            slotAAt = 2_000L,
            slotAState = "FIRED",
            slotAToken = 7L,
            eventIdentity = goal,
        )
        val goalSource =
            WakeDispatchAuthorizationFactory.canonicalSource(
                goal,
                WakeDispatchSourceKind.GOAL_DYNAMIC_A,
                WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.A, 7L, 2_000L),
                2_100L,
            )
        val goalBefore = allTablesFingerprint()

        val goalResult = store.reduce(goal, goalSource, 2_100L, 500L)

        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, goalResult.outcome)
        assertNull(goalResult.authorization)
        assertEquals(goalBefore, allTablesFingerprint())
    }

    @Test
    fun startEventToleratesAChangedButStillValidGoalSnapshotTrigger() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_snapshot SET goal_epoch_ms = 2001 WHERE id = ?",
            arrayOf(event.snapshotId),
        )
        insertDispatch(state = "RECEIVED")

        val result = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

        assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun receivedPrimaryUnderWakeRequestsDispatchAndClearsStaleLease() {
        insertDispatch(
            state = "RECEIVED",
            dispatchAttemptId = 7L,
            attemptCount = 11L,
            leaseOwner = "stale-owner",
            leaseExpiresAt = 999L,
            failureReason = "old failure",
            slotAAt = 2_000L,
            slotAState = "ARMED",
            slotAToken = 3L,
            slotBAt = null,
            slotBState = "CONSUMED",
            slotBToken = 5L,
        )

        val before = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                WakePendingIntentData.primary(event),
                1_100L,
            )
        val result = store.reduce(event, source, 1_100L, 500L)

        assertEquals(WakeEventStoreOutcome.AUTHORIZED_NEW, result.outcome)
        val authorization = requireNotNull(result.authorization)
        val row = requireNotNull(result.dispatch)
        assertEquals(
            before.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 8L,
                attemptCount = 12L,
                lastAttemptAt = 1_100L,
                leaseOwner = authorization.leaseOwner,
                leaseExpiresAt = 61_100L,
                failureReason = null,
                armedPrimary = 0,
            ),
            row,
        )
        assertEquals(
            WakeDispatchAuthorizationFactory.create(event, 1L, 8L, 0L, 61_100L, source).leaseOwner,
            authorization.leaseOwner,
        )
    }

    @Test
    fun preparingRecoveryDefersAndConsumesOnlyArrivingSlot() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'PREPARING_WAKE' WHERE id = 1"
        )
        insertDispatch(
            state = "RECEIVED",
            dispatchAttemptId = 4L,
            attemptCount = 9L,
            leaseOwner = "existing-lease",
            leaseExpiresAt = 5_000L,
            failureReason = "preserved",
            slotAAt = 1_015L,
            slotAState = "FIRED",
            slotAToken = 12L,
            slotBAt = 2_000L,
            slotBState = "ARMED",
            slotBToken = 20L,
        )

        val result =
            store.reduce(
                event,
                recoveryArrival(
                    WakeRecoverySlotId.A,
                    deliveredToken = 12L,
                    deliveredTriggerEpochMillis = 1_015L,
                ),
                nowEpochMillis = 1_100L,
                maxHeartbeatAgeMillis = 500L,
            )

        assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome)
        val row = requireNotNull(result.dispatch)
        assertEquals("DEFERRED", row.state)
        assertEquals(4L, row.dispatchAttemptId)
        assertEquals(9L, row.attemptCount)
        assertNull(row.lastAttemptAt)
        assertEquals("existing-lease", row.leaseOwner)
        assertEquals(5_000L, row.leaseExpiresAt)
        assertEquals("preserved", row.failureReason)
        assertEquals("CONSUMED", row.recoverySlotAState)
        assertNull(row.recoverySlotAAt)
        assertEquals(13L, row.recoverySlotAToken)
        assertEquals("ARMED", row.recoverySlotBState)
        assertEquals(2_000L, row.recoverySlotBAt)
        assertEquals(20L, row.recoverySlotBToken)
    }

    @Test
    fun staleServiceAckRecoveryRequestsAndConsumesAtomically() {
        insertDispatch(
            state = "SERVICE_ACKED",
            dispatchAttemptId = 2L,
            attemptCount = 3L,
            leaseOwner = "service-1",
            leaseExpiresAt = 900L,
            failureReason = "retry me",
            slotAAt = 3_000L,
            slotAState = "ARMED",
            slotAToken = 8L,
            slotBAt = 1_000L,
            slotBState = "IN_FLIGHT",
            slotBToken = 5L,
        )

        val before = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_DYNAMIC_B,
                WakePendingIntentData.dynamic(event, WakeRecoverySlotId.B, 5L, 1_000L),
                1_100L,
            )
        val result = store.reduce(event, source, 1_100L, 500L)

        assertEquals(WakeEventStoreOutcome.AUTHORIZED_NEW, result.outcome)
        val authorization = requireNotNull(result.authorization)
        val row = requireNotNull(result.dispatch)
        assertEquals(
            before.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 3L,
                attemptCount = 4L,
                lastAttemptAt = 1_100L,
                leaseOwner = authorization.leaseOwner,
                leaseExpiresAt = 61_100L,
                failureReason = null,
                recoverySlotBState = "CONSUMED",
                recoverySlotBAt = null,
                recoverySlotBToken = 6L,
            ),
            row,
        )
        assertEquals(
            WakeDispatchAuthorizationFactory.create(event, 1L, 3L, 0L, 61_100L, source).leaseOwner,
            authorization.leaseOwner,
        )
    }

    @Test
    fun terminalActiveDispatchAndHealthyAckAreTypedNoOpsWithoutCas() {
        val scenarios =
            listOf(
                Triple("TERMINAL", null, WakeEventStoreOutcome.NO_OP_TERMINAL),
                Triple("DISPATCH_REQUESTED", "active", WakeEventStoreOutcome.NO_OP_ACTIVE_DISPATCH),
                Triple("SERVICE_ACKED", "healthy", WakeEventStoreOutcome.NO_OP_HEALTHY_ACK),
            )
        scenarios.forEachIndexed { index, (state, mode, expected) ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(
                state = state,
                dispatchAttemptId = 3L,
                attemptCount = 4L,
                leaseOwner = if (mode == null) null else "owner",
                leaseExpiresAt = if (mode == null) null else 2_000L,
            )
            if (mode == "healthy") {
                database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE wake_run_status SET state = 'ACTIVE', active_service_owner_token = 'owner',
                      execution_epoch = 1, service_lease_owner = 'owner',
                      service_lease_expires_at = 2000, heartbeat_at = 1050
                    WHERE snapshot_id = ?
                    """
                        .trimIndent(),
                    arrayOf(event.snapshotId),
                )
            } else {
                database.openHelper.writableDatabase.execSQL(
                    """
                    UPDATE wake_run_status SET state = 'PREPARED', active_service_owner_token = NULL,
                      execution_epoch = 0, service_lease_owner = NULL,
                      service_lease_expires_at = NULL, heartbeat_at = NULL
                    WHERE snapshot_id = ?
                    """
                        .trimIndent(),
                    arrayOf(event.snapshotId),
                )
            }
            val before = dispatchFingerprint()

            val result = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

            assertEquals(expected, result.outcome)
            assertEquals(before, dispatchFingerprint())
        }
    }

    @Test
    fun differentDynamicArrivalDuringActiveLeaseReturnsNoAuthorizationAndLeavesMarkerUnconsumed() {
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            dispatchAttemptId = 3L,
            attemptCount = 4L,
            leaseOwner = "active-owner",
            leaseExpiresAt = 2_000L,
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 7L,
        )
        val identity = WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_000L)
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_DYNAMIC_A,
                identity,
                1_100L,
            )
        val before = allTablesFingerprint()

        val result = store.reduce(event, source, 1_100L, 500L)

        assertEquals(WakeEventStoreOutcome.NO_OP_ACTIVE_DISPATCH, result.outcome)
        assertNull(result.authorization)
        assertNull(result.convergence)
        assertEquals(before, allTablesFingerprint())
        val dispatch = requireNotNull(result.dispatch)
        assertEquals("FIRED", dispatch.recoverySlotAState)
        assertEquals(7L, dispatch.recoverySlotAToken)
    }

    @Test
    fun activeGenerationMismatchAndTerminalRunStatusAreZeroWriteTypedResults() {
        insertDispatch(state = "RECEIVED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET active_generation = 2 WHERE id = 1"
        )
        val mismatchBefore = allTablesFingerprint()
        val mismatch = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, mismatch.outcome)
        assertNull(mismatch.authorization)
        assertEquals(mismatchBefore, allTablesFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET active_generation = 1 WHERE id = 1"
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state = 'COMPLETED', completed_at = 1050 WHERE snapshot_id = ?",
            arrayOf(event.snapshotId),
        )
        val terminalBefore = allTablesFingerprint()
        val terminal = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
        assertEquals(WakeEventStoreOutcome.NO_OP_TERMINAL, terminal.outcome)
        assertNull(terminal.authorization)
        assertEquals(terminalBefore, allTablesFingerprint())
    }

    @Test
    fun dispatchLeaseExpiryOverflowFailsClosedWithoutAnyWrite() {
        insertDispatch(state = "RECEIVED")
        val receivedAt = Long.MAX_VALUE - 59_999L
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                WakePendingIntentData.primary(event),
                receivedAt,
            )
        val before = allTablesFingerprint()

        val result = store.reduce(event, source, receivedAt, 500L)

        assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome)
        assertNull(result.authorization)
        assertEquals(before, allTablesFingerprint())
    }

    @Test
    fun legacyAndRestoringFailClosedWithoutMutation() {
        listOf("LEGACY", "RESTORING").forEachIndexed { index, owner ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            database.openHelper.writableDatabase.execSQL(
                "UPDATE migration_state SET schedule_owner = ? WHERE id = 1",
                arrayOf(owner),
            )
            insertDispatch(state = "RECEIVED", failureReason = "keep")
            val before = dispatchFingerprint()

            val result = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

            assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome)
            assertEquals(before, dispatchFingerprint())
        }
    }

    @Test
    fun exactDuplicatePrimaryAndRecoveryConvergeWithoutDoubleIncrement() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 7L, attemptCount = 11L)
        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        val primaryApplied = dispatchFingerprint()

        assertEquals(
            WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(primaryApplied, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        insertDispatch(
            state = "RECEIVED",
            dispatchAttemptId = 2L,
            attemptCount = 3L,
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 9L,
        )
        val slotA =
            recoveryArrival(
                WakeRecoverySlotId.A,
                deliveredToken = 9L,
                deliveredTriggerEpochMillis = 1_000L,
            )
        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, slotA, 1_100L, 500L).outcome,
        )
        val recoveryApplied = dispatchFingerprint()

        assertEquals(
            WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
            store.reduce(event, slotA, 1_100L, 500L).outcome,
        )
        assertEquals(recoveryApplied, dispatchFingerprint())
    }

    @Test
    fun missingOrWrongIdentityAndMissingStatusFailClosedWithoutWrites() {
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )

        insertDispatch(state = "RECEIVED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET event_kind = 'GOAL' WHERE event_key = ?",
            arrayOf(event.canonicalKey()),
        )
        val wrongBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(wrongBefore, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET event_kind = 'START'"
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM wake_run_status WHERE snapshot_id = ?",
            arrayOf(event.snapshotId),
        )
        val missingStatusBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(missingStatusBefore, dispatchFingerprint())
    }

    @Test
    fun corruptRowsAndAttemptCountOverflowFailClosedBeforeCas() {
        val corruptions =
            listOf(
                "state = 'BOGUS'",
                "dispatch_attempt_id = -1",
                "attempt_count = -1",
                "armed_primary = 2",
                "lease_expires_at = -1, lease_owner = 'x'",
                "recovery_slot_a_state = 'FIRED', recovery_slot_a_at = NULL",
                "recovery_slot_a_token = -1",
            )
        corruptions.forEachIndexed { index, assignment ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(state = "RECEIVED")
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
            database.openHelper.writableDatabase.execSQL(
                "UPDATE wake_event_dispatch SET $assignment"
            )
            val before = dispatchFingerprint()

            assertEquals(
                WakeEventStoreOutcome.FAIL_CLOSED,
                store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
            )
            assertEquals(before, dispatchFingerprint())
        }

        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        insertDispatch(state = "RECEIVED", attemptCount = Long.MAX_VALUE)
        val overflowBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(overflowBefore, dispatchFingerprint())
    }

    @Test
    fun injectedFaultsBeforeAfterCasAndBeforeReturnRollBackAllParticipatingTables() {
        listOf("BEFORE_CAS", "AFTER_CAS", "BEFORE_RETURN").forEachIndexed { index, point ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(
                state = "RECEIVED",
                dispatchAttemptId = 4L,
                attemptCount = 8L,
                leaseOwner = "stale",
                leaseExpiresAt = 900L,
                failureReason = "keep-on-rollback",
                slotAAt = 2_000L,
                slotAState = "ARMED",
                slotAToken = 6L,
            )
            val before = allTablesFingerprint()
            val faulting =
                WakeEventDispatchStoreFaultFixture.create(database) {
                    if (it == point) error("injected-$point")
                }

            assertFailsWith<IllegalStateException> {
                faulting.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
            }
            assertEquals(before, allTablesFingerprint())
        }
    }

    @Test
    fun requestDoesNotMutateStatusMigrationSnapshotAnchorOrOutbox() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 1L, attemptCount = 2L)
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_recovery_anchor(event_key, anchor_kind, trigger_epoch_ms, state, pending_intent_identity)
            VALUES (?, 'GOAL_PRIMARY', 1000, 'ARMED', 'anchor-pi')
            """
                .trimIndent(),
            arrayOf(event.canonicalKey()),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO schedule_outbox(id, generation, command, event_key, state, attempt_count,
              not_before_epoch_ms, created_at, last_error)
            VALUES ('existing-outbox', 1, 'RECONCILE', ?, 'PENDING', 2, 900, 800, 'keep')
            """
                .trimIndent(),
            arrayOf(event.canonicalKey()),
        )
        val protectedBefore = protectedTablesFingerprint()

        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )

        assertEquals(protectedBefore, protectedTablesFingerprint())
    }

    @Test
    fun missingMigrationAndCorruptStatusFailClosedWithoutDispatchWrites() {
        insertDispatch(state = "RECEIVED", failureReason = "preserve")
        database.openHelper.writableDatabase.execSQL("DELETE FROM migration_state WHERE id = 1")
        val missingMigration = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(missingMigration, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO migration_state(
              id, schedule_owner, active_generation, bootstrap_version,
              rollback_allowed_until_version, handoff_fence_occurrence_id, install_epoch,
              source_fingerprint, target_storage_key, bootstrap_phase, attempt_token
            ) VALUES (1, 'WAKE', NULL, 0, 16, NULL, 'install-1', NULL, NULL, NULL, NULL)
            """
                .trimIndent()
        )
        database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET execution_epoch = -1 WHERE snapshot_id = ?",
            arrayOf(event.snapshotId),
        )
        val corruptStatus = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(corruptStatus, dispatchFingerprint())
    }

    @Test
    fun twoConcurrentPrimaryCallersReturnExactlyOneAuthorizationAndTypedConvergence() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 4L, attemptCount = 6L)
        val identity = WakePendingIntentData.primary(event)
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                identity,
                1_100L,
            )
        val expected = WakeDispatchAuthorizationFactory.create(event, 1L, 5L, 0L, 61_100L, source)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls =
                (0 until 2).map {
                    executor.submit(
                        Callable {
                            start.await()
                            RoomWakeEventDispatchStore(database).reduce(event, source, 1_100L, 500L)
                        }
                    )
                }
            start.countDown()
            val results = calls.map { it.get(10, TimeUnit.SECONDS) }

            val winner = results.single { it.outcome == WakeEventStoreOutcome.AUTHORIZED_NEW }
            val loser = results.single {
                it.outcome == WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE
            }
            assertAuthorizationEquals(expected, requireNotNull(winner.authorization), "winner")
            assertNull(loser.authorization)
            assertEquals(WakeEventConvergence.ALREADY_CONSUMED, loser.convergence)
            assertEquals(1, results.count { it.authorization != null })
            val row = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
            assertEquals(5L, row.dispatchAttemptId)
            assertEquals(7L, row.attemptCount)
            assertEquals(1_100L, row.lastAttemptAt)
            assertEquals(expected.leaseOwner, row.leaseOwner)
            assertEquals(expected.leaseExpiresAt, row.leaseExpiresAt)
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun preparingPrimaryDuplicateConvergesWithoutReapplyingDefer() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'PREPARING_WAKE' WHERE id = 1"
        )
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 4L, attemptCount = 9L)
        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        val applied = dispatchFingerprint()

        assertEquals(
            WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        assertEquals(applied, dispatchFingerprint())
    }

    @Test
    fun duplicatePrimaryAtDifferentNowUsesDurableConsumedMarkerWithoutMutation() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 7L, attemptCount = 11L)
        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L).outcome,
        )
        val applied = dispatchFingerprint()

        val duplicate = store.reduce(event, WakeEventArrival.Primary, 9_999L, 500L)

        assertEquals(WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE, duplicate.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, duplicate.convergence)
        assertEquals(applied, dispatchFingerprint())
    }

    @Test
    fun primaryRequiresArmedMarkerAndConsumesItWhileRecoveryPreservesIt() {
        insertDispatch(state = "RECEIVED", armedPrimary = 0)
        val consumed = dispatchFingerprint()
        val duplicate = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
        assertEquals(WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE, duplicate.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, duplicate.convergence)
        assertEquals(consumed, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        insertDispatch(
            state = "RECEIVED",
            armedPrimary = 1,
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 4L,
        )
        val recovery =
            store.reduce(
                event,
                recoveryArrival(WakeRecoverySlotId.A, 4L, 1_000L),
                1_100L,
                500L,
            )
        assertEquals(WakeEventStoreOutcome.APPLIED, recovery.outcome)
        assertEquals(1, requireNotNull(recovery.dispatch).armedPrimary)
    }

    @Test
    fun recoveryTokenFenceDistinguishesExactDuplicateStaleAndFutureDeliveries() {
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            armedPrimary = 0,
            dispatchAttemptId = 2L,
            attemptCount = 2L,
            slotAState = "CONSUMED",
            slotAToken = 10L,
        )
        val exactBefore = dispatchFingerprint()
        val exact =
            store.reduce(
                event,
                recoveryArrival(WakeRecoverySlotId.A, 9L, 1_000L),
                1_100L,
                500L,
            )
        assertEquals(WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE, exact.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, exact.convergence)
        assertEquals(exactBefore, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET recovery_slot_a_token = 11"
        )
        val staleBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.STALE_DELIVERY,
            store
                .reduce(
                    event,
                    recoveryArrival(WakeRecoverySlotId.A, 9L, 1_000L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(staleBefore, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET recovery_slot_a_token = 10, recovery_slot_a_state = 'ARMED', recovery_slot_a_at = 1000"
        )
        val rearmedBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.STALE_DELIVERY,
            store
                .reduce(
                    event,
                    recoveryArrival(WakeRecoverySlotId.A, 9L, 1_000L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(rearmedBefore, dispatchFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET recovery_slot_a_token = 8, recovery_slot_a_state = 'FIRED', recovery_slot_a_at = 1000"
        )
        val futureBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.FAIL_CLOSED,
            store
                .reduce(
                    event,
                    recoveryArrival(WakeRecoverySlotId.A, 9L, 1_000L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(futureBefore, dispatchFingerprint())
    }

    @Test
    fun consumedRecoveryDuplicateIsNecessarilyTokenOnlyInV6() {
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            armedPrimary = 0,
            dispatchAttemptId = 2L,
            attemptCount = 2L,
            slotAState = "CONSUMED",
            slotAToken = 10L,
        )
        val before = dispatchFingerprint()

        val result =
            store.reduce(
                event,
                recoveryArrival(WakeRecoverySlotId.A, 9L, 9_999L),
                1_100L,
                500L,
            )

        assertEquals(WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE, result.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, result.convergence)
        assertEquals(before, dispatchFingerprint())
    }

    @Test
    fun invalidRecoveryTokensAreRejectedBeforeStoreWithoutWrites() {
        insertDispatch(
            state = "RECEIVED",
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 10L,
        )
        listOf(-1L, Long.MAX_VALUE).forEach { delivered ->
            val before = dispatchFingerprint()
            assertFailsWith<IllegalArgumentException> {
                recoveryArrival(WakeRecoverySlotId.A, delivered, 1_000L)
            }
            assertEquals(before, dispatchFingerprint())
        }
        val mismatchBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.STALE_DELIVERY,
            store
                .reduce(
                    event,
                    recoveryArrival(WakeRecoverySlotId.A, 9L, 1_000L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(mismatchBefore, dispatchFingerprint())
    }

    @Test
    fun casLoserPerformsBoundedConvergenceRereadAndReturnsNoAuthorization() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 4L, attemptCount = 6L)
        val identity = WakePendingIntentData.primary(event)
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                identity,
                1_100L,
            )
        val committedAuthorization =
            WakeDispatchAuthorizationFactory.create(event, 1L, 5L, 0L, 61_100L, source)
        var beforeCasHits = 0
        val losingStore =
            WakeEventDispatchStoreFaultFixture.create(database) { point ->
                if (point == "BEFORE_CAS") {
                    beforeCasHits += 1
                    database.openHelper.writableDatabase.execSQL(
                        """
                        UPDATE wake_event_dispatch
                        SET state='DISPATCH_REQUESTED', dispatch_attempt_id=5, attempt_count=7,
                          last_attempt_at=1100, failure_reason=NULL, armed_primary=0,
                          lease_owner=?, lease_expires_at=61100
                        WHERE event_key=?
                        """
                            .trimIndent(),
                        arrayOf(committedAuthorization.leaseOwner, event.canonicalKey()),
                    )
                }
            }

        val result = losingStore.reduce(event, source, 1_100L, 500L)

        assertEquals(1, beforeCasHits)
        assertEquals(WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE, result.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, result.convergence)
        assertNull(result.authorization)
        assertEquals(committedAuthorization.leaseOwner, result.dispatch?.leaseOwner)
        assertEquals(committedAuthorization.leaseExpiresAt, result.dispatch?.leaseExpiresAt)
    }

    @Test
    fun storeCasLossFromEveryMutatedPreimageColumnNeverWritesAgainOrReturnsAuthorization() {
        data class ConcurrentMutation(
            val name: String,
            val assignment: String,
            val arguments: Array<Any?> = emptyArray(),
            val outcome: WakeEventStoreOutcome,
        )
        database.wakeRunStorageDao().createSnapshot(snapshot("alternate-snapshot"), 900L)
        database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
        val cases =
            listOf(
                ConcurrentMutation(
                    "event_key",
                    "event_key = event_key || '-changed'",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "snapshot_id",
                    "snapshot_id = ?",
                    arrayOf("alternate-snapshot"),
                    WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "event_kind",
                    "event_kind = 'GOAL'",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "expected_trigger",
                    "expected_trigger_epoch_ms = 1001",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "state",
                    "state = 'DEFERRED'",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "dispatch_attempt_id",
                    "dispatch_attempt_id = 6",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "lease_owner_nonnull_to_null",
                    "lease_owner = NULL",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "lease_expiry_nonnull_to_null",
                    "lease_expires_at = NULL",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "attempt_count",
                    "attempt_count = 6",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "last_attempt_at_null_to_nonnull",
                    "last_attempt_at = 999",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "failure_reason_nonnull_to_null",
                    "failure_reason = NULL",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "armed_primary",
                    "armed_primary = 0",
                    outcome = WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE,
                ),
                ConcurrentMutation(
                    "slot_a_trigger_nonnull_to_null",
                    "recovery_slot_a_at = NULL",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "slot_a_state",
                    "recovery_slot_a_state = 'IN_FLIGHT'",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "slot_a_token",
                    "recovery_slot_a_token = 9",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "slot_b_trigger_null_to_nonnull",
                    "recovery_slot_b_at = 3000",
                    outcome = WakeEventStoreOutcome.FAIL_CLOSED,
                ),
                ConcurrentMutation(
                    "slot_b_state",
                    "recovery_slot_b_state = 'CANCELLED'",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
                ConcurrentMutation(
                    "slot_b_token",
                    "recovery_slot_b_token = 12",
                    outcome = WakeEventStoreOutcome.STALE_RETRY_REQUIRED,
                ),
            )

        cases.forEachIndexed { index, case ->
            if (index > 0) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            }
            insertDispatch(
                state = "RECEIVED",
                dispatchAttemptId = 5L,
                attemptCount = 5L,
                leaseOwner = "old-owner",
                leaseExpiresAt = 900L,
                failureReason = "old-failure",
                slotAAt = 1_000L,
                slotAState = "FIRED",
                slotAToken = 8L,
                slotBState = "CONSUMED",
                slotBToken = 11L,
            )
            var beforeCasHits = 0
            var afterConcurrentMutation: List<Any?>? = null
            val faulting =
                WakeEventDispatchStoreFaultFixture.create(database) { point ->
                    if (point == "BEFORE_CAS") {
                        beforeCasHits += 1
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE wake_event_dispatch SET ${case.assignment}",
                            case.arguments,
                        )
                        afterConcurrentMutation = allTablesFingerprint()
                    }
                }

            val result = faulting.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

            assertEquals(1, beforeCasHits, case.name)
            assertEquals(case.outcome, result.outcome, case.name)
            assertNull(result.authorization, case.name)
            assertEquals(afterConcurrentMutation, allTablesFingerprint(), case.name)
        }
    }

    @Test
    fun actualDaoCasFencesEveryFullPreimageColumnWithNullSafeEquality() {
        val stalePreimages =
            listOf<Pair<String, (WakeEventDispatchEntity) -> WakeEventDispatchEntity>>(
                "event_key" to { it.copy(eventKey = "${it.eventKey}-stale") },
                "snapshot_id" to { it.copy(snapshotId = "stale-snapshot") },
                "event_kind" to { it.copy(eventKind = "GOAL") },
                "expected_trigger" to { it.copy(expectedTriggerEpochMs = 1_001L) },
                "state" to { it.copy(state = "DEFERRED") },
                "dispatch_attempt_id" to { it.copy(dispatchAttemptId = 4L) },
                "lease_owner_nonnull_to_null" to { it.copy(leaseOwner = null) },
                "lease_expiry_nonnull_to_null" to { it.copy(leaseExpiresAt = null) },
                "attempt_count" to { it.copy(attemptCount = 6L) },
                "last_attempt_at_null_to_nonnull" to { it.copy(lastAttemptAt = 999L) },
                "failure_reason_nonnull_to_null" to { it.copy(failureReason = null) },
                "armed_primary" to { it.copy(armedPrimary = 0) },
                "slot_a_trigger_nonnull_to_null" to { it.copy(recoverySlotAAt = null) },
                "slot_a_state" to { it.copy(recoverySlotAState = "IN_FLIGHT") },
                "slot_a_token" to { it.copy(recoverySlotAToken = 9L) },
                "slot_b_trigger_null_to_nonnull" to { it.copy(recoverySlotBAt = 3_000L) },
                "slot_b_state" to { it.copy(recoverySlotBState = "ARMED") },
                "slot_b_token" to { it.copy(recoverySlotBToken = 12L) },
            )

        stalePreimages.forEachIndexed { index, (name, mutate) ->
            if (index > 0) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            }
            insertDispatch(
                state = "RECEIVED",
                dispatchAttemptId = 5L,
                attemptCount = 5L,
                leaseOwner = "old-owner",
                leaseExpiresAt = 900L,
                failureReason = "old-failure",
                slotAAt = 1_000L,
                slotAState = "FIRED",
                slotAToken = 8L,
                slotBState = "CONSUMED",
                slotBToken = 11L,
            )
            val actual =
                requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
            val staleExpected = mutate(actual)
            val proposed =
                actual.copy(
                    state = "DISPATCH_REQUESTED",
                    dispatchAttemptId = 6L,
                    leaseOwner = "proposed-owner",
                    leaseExpiresAt = 61_100L,
                    attemptCount = 6L,
                    lastAttemptAt = 1_100L,
                    failureReason = null,
                    armedPrimary = 0,
                )
            val before = allTablesFingerprint()

            val changed = daoCompareAndSet(staleExpected, proposed)

            assertEquals(0, changed, name)
            assertEquals(before, allTablesFingerprint(), name)
        }
    }

    @Test
    fun authenticatedRecoveryFactoryRejectsNegativeTokenBeforeStore() {
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                eventIdentity = event,
                slot = WakeRecoverySlotId.A,
                token = -1L,
                triggerEpochMillis = 1_015L,
            )
        }
    }

    @Test
    fun authenticatedRecoveryFactoryRejectsMaximumTokenBeforeStore() {
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                eventIdentity = event,
                slot = WakeRecoverySlotId.A,
                token = Long.MAX_VALUE,
                triggerEpochMillis = 1_015L,
            )
        }
    }

    @Test
    fun authenticatedRecoveryFactoryAcceptsTokenBoundaries() {
        listOf(0L, Long.MAX_VALUE - 1L).forEach { token ->
            val arrival =
                AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                    eventIdentity = event,
                    slot = WakeRecoverySlotId.A,
                    token = token,
                    triggerEpochMillis = 1_015L,
                )

            arrival.match(
                onPrimary = { throw AssertionError("Expected recovery arrival") },
                onRecovery = { recoveredEvent, recoveredSlot, recoveredToken, recoveredTrigger ->
                    assertEquals(event, recoveredEvent)
                    assertEquals(WakeRecoverySlotId.A, recoveredSlot)
                    assertEquals(token, recoveredToken)
                    assertEquals(1_015L, recoveredTrigger)
                },
            )
        }
    }

    @Test
    fun recoveryArrivalHasOnlyNamedAuthenticatedFactoryConstructionPath() {
        assertTrue(
            WakeEventArrival::class.java.declaredClasses.none { it.simpleName == "Recovery" },
            "WakeEventArrival must not expose a directly constructible Recovery type",
        )

        val factory =
            Class.forName("com.dsalmun.luxalarm.data.AuthenticatedWakeEventArrivalFactory")
        assertEquals(
            setOf(
                "$" +
                    "jacocoInit(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/Class;)[Z|private static|synthetic=true|bridge=false",
                "fromVerifiedPendingIntentData" +
                    "(Lcom/dsalmun/luxalarm/wake/WakeEventIdentity;" +
                    "Lcom/dsalmun/luxalarm/wake/WakeRecoverySlotId;JJ)" +
                    "Lcom/dsalmun/luxalarm/data/WakeEventArrival;" +
                    "|public final|synthetic=false|bridge=false",
            ),
            factory.declaredMethods.map(::methodSurface).toSet(),
            "Lock the factory's complete declared-method surface",
        )

        val recoveryImplementation =
            AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                    event,
                    WakeRecoverySlotId.A,
                    1L,
                    1_015L,
                )
                .javaClass

        val constructorSurfaces =
            recoveryImplementation.declaredConstructors.map(::constructorSurface).sorted()
        assertEquals(1, constructorSurfaces.size)
        recoveryImplementation.declaredConstructors.forEach { constructor ->
            assertFalse(Modifier.isPublic(constructor.modifiers), constructor.toString())
            assertFalse(Modifier.isProtected(constructor.modifiers), constructor.toString())
            assertFalse(
                constructor.parameterTypes.any {
                    it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                },
                constructor.toString(),
            )
        }

        assertEquals(
            setOf(
                "$" +
                    "jacocoInit(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;" +
                    "Ljava/lang/Class;)[Z|private static|synthetic=true|bridge=false",
                "match(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function4;)" +
                    "Ljava/lang/Object;|public|synthetic=false|bridge=false",
            ),
            recoveryImplementation.declaredMethods.map(::methodSurface).toSet(),
            "Lock the recovery implementation's complete declared-method surface",
        )

        assertTrue(recoveryImplementation.declaredClasses.isEmpty())
        assertTrue(recoveryImplementation.declaredMethods.none { it.name == "create" })

        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.dsalmun.luxalarm.data.WakeEventArrival\$Recovery")
        }
    }

    @Test
    fun productionVisibleConstructorsExposeOnlyTheDatabase() {
        val accessible =
            RoomWakeEventDispatchStore::class.java.declaredConstructors.filterNot {
                Modifier.isPrivate(it.modifiers)
            }

        assertEquals(1, accessible.size)
        assertEquals(listOf(AlarmDatabase::class.java), accessible.single().parameterTypes.toList())

        val reduceMethods =
            RoomWakeEventDispatchStore::class.java.declaredMethods.filter {
                it.name == "reduce" && Modifier.isPublic(it.modifiers)
            }
        assertEquals(1, reduceMethods.size)
        assertEquals(
            listOf(
                WakeEventIdentity::class.java,
                WakeDispatchSource::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            ),
            reduceMethods.single().parameterTypes.toList(),
        )
    }

    @Test
    fun eventStoreResultHasOpaqueJvmShapeAndFactoryRejectsImpossiblePayloads() {
        assertTrue(WakeEventStoreResult::class.java.isInterface)
        assertTrue(WakeEventStoreResult::class.java.declaredConstructors.isEmpty())
        assertTrue(
            WakeEventStoreResult::class.java.declaredMethods.none {
                it.name == "copy" || it.name == "copy\$default" || it.isSynthetic || it.isBridge
            }
        )

        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                event,
                WakeDispatchSourceKind.START_PRIMARY,
                WakePendingIntentData.primary(event),
                1_100L,
            )
        val authorization =
            WakeDispatchAuthorizationFactory.create(event, 1L, 1L, 0L, 61_100L, source)
        WakeEventStoreOutcome.entries.forEach { outcome ->
            val convergence =
                if (outcome == WakeEventStoreOutcome.CONVERGED_EXACT_DUPLICATE) {
                    WakeEventConvergence.ALREADY_CONSUMED
                } else null
            val exactAuthorization =
                if (outcome == WakeEventStoreOutcome.AUTHORIZED_NEW) authorization else null
            val result =
                WakeEventStoreResultFactory.create(outcome, null, convergence, exactAuthorization)
            assertEquals(exactAuthorization, result.authorization, outcome.name)
            assertEquals(convergence, result.convergence, outcome.name)

            assertFailsWith<IllegalArgumentException>(outcome.name) {
                WakeEventStoreResultFactory.create(
                    outcome,
                    null,
                    convergence,
                    if (exactAuthorization == null) authorization else null,
                )
            }
            assertFailsWith<IllegalArgumentException>(outcome.name) {
                WakeEventStoreResultFactory.create(
                    outcome,
                    null,
                    if (convergence == null) WakeEventConvergence.ALREADY_CONSUMED else null,
                    exactAuthorization,
                )
            }
        }
        WakeEventStoreResultFactory::class.java.declaredMethods.forEach { method ->
            assertFalse(method.name.contains("default"), method.toString())
            assertFalse(method.isBridge, method.toString())
        }
    }

    @Test
    fun malformedCanonicalSnapshotAndRunStatusFailClosedWithoutAnyWriteOrAuthorization() {
        val snapshotCorruptions =
            listOf(
                "UPDATE wake_run_snapshot SET occurrence_id=''",
                "UPDATE wake_run_snapshot SET schedule_generation=-1",
                "UPDATE wake_run_snapshot SET routine_revision=-1",
                "UPDATE wake_run_snapshot SET calculation_rule_version=-1",
                "UPDATE wake_run_snapshot SET zone_id=''",
                "UPDATE wake_run_snapshot SET zone_id='BOGUS/ZONE'",
                "UPDATE wake_run_snapshot SET occurrence_local_date=''",
                "UPDATE wake_run_snapshot SET occurrence_local_date='not-a-date'",
                "UPDATE wake_run_snapshot SET wake_start_epoch_ms=-1",
                "UPDATE wake_run_snapshot SET goal_epoch_ms=-1",
                "UPDATE wake_run_snapshot SET wake_start_epoch_ms=2001,goal_epoch_ms=2000",
                "UPDATE wake_run_snapshot SET light_payload=''",
                "UPDATE wake_run_snapshot SET music_payload=''",
                "UPDATE wake_run_snapshot SET vibration_payload=''",
                "UPDATE wake_run_snapshot SET dismissal='BOGUS'",
                "UPDATE wake_run_snapshot SET created_at=-1",
                "UPDATE wake_run_snapshot SET install_epoch=''",
                "UPDATE wake_run_snapshot SET selected_track_id=NULL,selected_track_storage_key='orphan'",
            )
        val statusCorruptions =
            listOf(
                "UPDATE wake_run_status SET state='BOGUS'",
                "UPDATE wake_run_status SET execution_epoch=-1",
                "UPDATE wake_run_status SET armed_start=2",
                "UPDATE wake_run_status SET armed_goal=-1",
                "UPDATE wake_run_status SET state='ACTIVE',completed_at=1100",
                "UPDATE wake_run_status SET state='ACTIVE',failure_reason='NO_CONFIRMATION_DEADLINE'",
                "UPDATE wake_run_status SET heartbeat_at=1000",
                "UPDATE wake_run_status SET service_lease_owner='owner',service_lease_expires_at=NULL",
                "UPDATE wake_run_status SET state='COMPLETED',completed_at=1100,active_service_owner_token='owner'",
                "UPDATE wake_run_status SET state='COMPLETED',completed_at=1100,service_lease_owner='owner',service_lease_expires_at=2000",
                "UPDATE wake_run_status SET state='COMPLETED',completed_at=1100,armed_start=1",
                "UPDATE wake_run_status SET state='FAILED',completed_at=1100",
                "UPDATE wake_run_status SET state='CANCELLED',cancelled_at=NULL",
            )
        (snapshotCorruptions + statusCorruptions).forEachIndexed { index, sql ->
            if (index > 0) resetCanonicalContext()
            insertDispatch(state = "RECEIVED")
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
            database.openHelper.writableDatabase.execSQL(sql)
            val before = allTablesFingerprint()

            val result = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)

            assertEquals(WakeEventStoreOutcome.FAIL_CLOSED, result.outcome, sql)
            assertNull(result.authorization, sql)
            assertEquals(before, allTablesFingerprint(), sql)
        }
    }

    private fun daoCompareAndSet(
        expected: WakeEventDispatchEntity,
        next: WakeEventDispatchEntity,
    ): Int =
        database
            .wakeEventDispatchDao()
            .compareAndSet(
                expectedEventKey = expected.eventKey,
                expectedSnapshotId = expected.snapshotId,
                expectedEventKind = expected.eventKind,
                expectedTriggerEpochMs = expected.expectedTriggerEpochMs,
                expectedState = expected.state,
                expectedDispatchAttemptId = expected.dispatchAttemptId,
                expectedLeaseOwner = expected.leaseOwner,
                expectedLeaseExpiresAt = expected.leaseExpiresAt,
                expectedAttemptCount = expected.attemptCount,
                expectedLastAttemptAt = expected.lastAttemptAt,
                expectedFailureReason = expected.failureReason,
                expectedArmedPrimary = expected.armedPrimary,
                expectedRecoverySlotAAt = expected.recoverySlotAAt,
                expectedRecoverySlotAState = expected.recoverySlotAState,
                expectedRecoverySlotAToken = expected.recoverySlotAToken,
                expectedRecoverySlotBAt = expected.recoverySlotBAt,
                expectedRecoverySlotBState = expected.recoverySlotBState,
                expectedRecoverySlotBToken = expected.recoverySlotBToken,
                nextState = next.state,
                nextDispatchAttemptId = next.dispatchAttemptId,
                nextLeaseOwner = next.leaseOwner,
                nextLeaseExpiresAt = next.leaseExpiresAt,
                nextAttemptCount = next.attemptCount,
                nextLastAttemptAt = next.lastAttemptAt,
                nextFailureReason = next.failureReason,
                nextArmedPrimary = next.armedPrimary,
                nextRecoverySlotAAt = next.recoverySlotAAt,
                nextRecoverySlotAState = next.recoverySlotAState,
                nextRecoverySlotAToken = next.recoverySlotAToken,
                nextRecoverySlotBAt = next.recoverySlotBAt,
                nextRecoverySlotBState = next.recoverySlotBState,
                nextRecoverySlotBToken = next.recoverySlotBToken,
            )

    /** Test fixture deliberately exercises the same authenticated factory Task 5 must use. */
    private fun assertAuthorizationEquals(
        expected: WakeDispatchAuthorization,
        actual: WakeDispatchAuthorization,
        message: String,
    ) {
        assertEquals(expected.event, actual.event, message)
        assertEquals(expected.eventKey, actual.eventKey, message)
        assertEquals(expected.scheduleGeneration, actual.scheduleGeneration, message)
        assertEquals(expected.dispatchAttemptId, actual.dispatchAttemptId, message)
        assertEquals(expected.expectedExecutionEpoch, actual.expectedExecutionEpoch, message)
        assertEquals(expected.leaseOwner, actual.leaseOwner, message)
        assertEquals(expected.leaseExpiresAt, actual.leaseExpiresAt, message)
        assertEquals(expected.requestedAt, actual.requestedAt, message)
        assertEquals(expected.source, actual.source, message)
    }

    private fun recoveryArrival(
        slot: WakeRecoverySlotId,
        deliveredToken: Long,
        deliveredTriggerEpochMillis: Long,
        eventIdentity: WakeEventIdentity = event,
    ): WakeEventArrival =
        AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
            eventIdentity = eventIdentity,
            slot = slot,
            token = deliveredToken,
            triggerEpochMillis = deliveredTriggerEpochMillis,
        )

    /** Test-only bridge: raw arrivals are canonicalized before crossing the production boundary. */
    private fun RoomWakeEventDispatchStore.reduce(
        event: WakeEventIdentity,
        arrival: WakeEventArrival,
        nowEpochMillis: Long,
        maxHeartbeatAgeMillis: Long,
    ): WakeEventStoreResult {
        val source =
            arrival.match(
                onPrimary = {
                    WakeDispatchAuthorizationFactory.canonicalSource(
                        event,
                        if (event.kind == WakeEventKind.START) {
                            WakeDispatchSourceKind.START_PRIMARY
                        } else {
                            WakeDispatchSourceKind.GOAL_PRIMARY
                        },
                        WakePendingIntentData.primary(event),
                        nowEpochMillis,
                    )
                },
                onRecovery = { deliveredEvent, slot, token, trigger ->
                    val kind =
                        when (deliveredEvent.kind to slot) {
                            WakeEventKind.START to WakeRecoverySlotId.A ->
                                WakeDispatchSourceKind.START_DYNAMIC_A
                            WakeEventKind.START to WakeRecoverySlotId.B ->
                                WakeDispatchSourceKind.START_DYNAMIC_B
                            WakeEventKind.GOAL to WakeRecoverySlotId.A ->
                                WakeDispatchSourceKind.GOAL_DYNAMIC_A
                            WakeEventKind.GOAL to WakeRecoverySlotId.B ->
                                WakeDispatchSourceKind.GOAL_DYNAMIC_B
                            else -> error("Unknown test dynamic source")
                        }
                    WakeDispatchAuthorizationFactory.canonicalSource(
                        deliveredEvent,
                        kind,
                        WakePendingIntentData.dynamic(deliveredEvent, slot, token, trigger),
                        nowEpochMillis,
                    )
                },
            )
        val exact = reduce(event, source, nowEpochMillis, maxHeartbeatAgeMillis)
        return when (exact.outcome) {
            WakeEventStoreOutcome.AUTHORIZED_NEW ->
                WakeEventStoreResultFactory.create(
                    WakeEventStoreOutcome.APPLIED,
                    exact.dispatch,
                    null,
                    null,
                )
            else -> exact
        }
    }

    private fun resetCanonicalContext() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_run_snapshot")
        database.wakeRunStorageDao().createSnapshot(snapshot(event.snapshotId), 900L)
    }

    private fun dispatchFingerprint(): List<String?> =
        database.openHelper.readableDatabase
            .query(
                """
                SELECT event_key, snapshot_id, event_kind, expected_trigger_epoch_ms, state,
                  dispatch_attempt_id, lease_owner, lease_expires_at, attempt_count, last_attempt_at,
                  failure_reason, armed_primary, recovery_slot_a_at, recovery_slot_a_state,
                  recovery_slot_a_token, recovery_slot_b_at, recovery_slot_b_state, recovery_slot_b_token
                FROM wake_event_dispatch ORDER BY event_key
                """
                    .trimIndent()
            )
            .use { cursor ->
                if (!cursor.moveToFirst()) emptyList()
                else
                    (0 until cursor.columnCount).map { column ->
                        if (cursor.isNull(column)) null else cursor.getString(column)
                    }
            }

    private fun allTablesFingerprint(): List<Any?> =
        listOf(dispatchFingerprint()) + protectedTablesFingerprint()

    private fun protectedTablesFingerprint(): List<String> =
        listOf(
                "migration_state",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_recovery_anchor",
                "schedule_outbox",
            )
            .map { table ->
                database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    buildString {
                        append(table).append(':').append(cursor.columnCount).append(':')
                        while (cursor.moveToNext()) {
                            for (column in 0 until cursor.columnCount) {
                                append(
                                        if (cursor.isNull(column)) "<null>"
                                        else cursor.getString(column)
                                    )
                                    .append('|')
                            }
                        }
                    }
                }
            }

    private fun insertDispatch(
        state: String,
        dispatchAttemptId: Long = 0L,
        attemptCount: Long = 0L,
        leaseOwner: String? = null,
        leaseExpiresAt: Long? = null,
        failureReason: String? = null,
        armedPrimary: Int = 1,
        slotAAt: Long? = null,
        slotAState: String = "CONSUMED",
        slotAToken: Long = 0L,
        slotBAt: Long? = null,
        slotBState: String = "CONSUMED",
        slotBToken: Long = 0L,
        eventIdentity: WakeEventIdentity = event,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_event_dispatch(
              event_key, snapshot_id, event_kind, expected_trigger_epoch_ms, state,
              dispatch_attempt_id, lease_owner, lease_expires_at, attempt_count,
              last_attempt_at, failure_reason, armed_primary,
              recovery_slot_a_at, recovery_slot_a_state, recovery_slot_a_token,
              recovery_slot_b_at, recovery_slot_b_state, recovery_slot_b_token
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                eventIdentity.canonicalKey(),
                eventIdentity.snapshotId,
                eventIdentity.kind.name,
                eventIdentity.expectedTriggerEpochMillis,
                state,
                dispatchAttemptId,
                leaseOwner,
                leaseExpiresAt,
                attemptCount,
                failureReason,
                armedPrimary,
                slotAAt,
                slotAState,
                slotAToken,
                slotBAt,
                slotBState,
                slotBToken,
            ),
        )
    }

    private fun constructorSurface(constructor: java.lang.reflect.Constructor<*>): String =
        "constructor" +
            constructor.parameterTypes.joinToString(
                separator = "",
                prefix = "(",
                postfix = ")",
            ) {
                jvmDescriptor(it)
            } +
            "V|${Modifier.toString(constructor.modifiers)}|synthetic=${constructor.isSynthetic}"

    private fun methodSurface(method: java.lang.reflect.Method): String =
        method.name +
            method.parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") {
                jvmDescriptor(it)
            } +
            jvmDescriptor(method.returnType) +
            "|${Modifier.toString(method.modifiers)}" +
            "|synthetic=${method.isSynthetic}|bridge=${method.isBridge}"

    private fun jvmDescriptor(type: Class<*>): String =
        when (type) {
            java.lang.Void.TYPE -> "V"
            java.lang.Boolean.TYPE -> "Z"
            java.lang.Byte.TYPE -> "B"
            java.lang.Character.TYPE -> "C"
            java.lang.Short.TYPE -> "S"
            java.lang.Integer.TYPE -> "I"
            java.lang.Long.TYPE -> "J"
            java.lang.Float.TYPE -> "F"
            java.lang.Double.TYPE -> "D"
            else ->
                if (type.isArray) type.name.replace('.', '/')
                else "L${type.name.replace('.', '/')};"
        }

    private fun snapshot(id: String) =
        WakeRunSnapshotEntity(
            id = id,
            occurrenceId = "occurrence-$id",
            scheduleGeneration = 1L,
            routineRevision = 1L,
            calculationRuleVersion = 1L,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-04",
            wakeStartEpochMs = 1_000L,
            goalEpochMs = 2_000L,
            lightPayload = "{}",
            musicPayload = "{}",
            vibrationPayload = "{}",
            selectedTrackId = null,
            selectedTrackStorageKey = null,
            dismissal = "CONFIRM",
            createdAt = 900L,
            installEpoch = "install-1",
        )
}
