/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-event-dispatch-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeEventDispatchStore(database)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'WAKE' WHERE id = 1"
        )
        database.wakeRunStorageDao().createSnapshot(snapshot(event.snapshotId), 900L)
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

        val result =
            store.reduce(
                event,
                arrival = WakeEventArrival.Primary,
                nowEpochMillis = 1_100L,
                maxHeartbeatAgeMillis = 500L,
            )

        assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome)
        val row = requireNotNull(result.dispatch)
        assertEquals("DISPATCH_REQUESTED", row.state)
        assertEquals(8L, row.dispatchAttemptId)
        assertEquals(12L, row.attemptCount)
        assertEquals(1_100L, row.lastAttemptAt)
        assertNull(row.leaseOwner)
        assertNull(row.leaseExpiresAt)
        assertNull(row.failureReason)
        assertEquals(0, row.armedPrimary)
        assertEquals(2_000L, row.recoverySlotAAt)
        assertEquals("ARMED", row.recoverySlotAState)
        assertEquals(3L, row.recoverySlotAToken)
        assertNull(row.recoverySlotBAt)
        assertEquals("CONSUMED", row.recoverySlotBState)
        assertEquals(5L, row.recoverySlotBToken)
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
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 12L,
            slotBAt = 2_000L,
            slotBState = "ARMED",
            slotBToken = 20L,
        )

        val result =
            store.reduce(
                event,
                WakeEventArrival.Recovery(WakeRecoverySlotId.A, deliveredToken = 12L),
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

        val result =
            store.reduce(
                event,
                WakeEventArrival.Recovery(WakeRecoverySlotId.B, deliveredToken = 5L),
                nowEpochMillis = 1_100L,
                maxHeartbeatAgeMillis = 500L,
            )

        assertEquals(WakeEventStoreOutcome.APPLIED, result.outcome)
        val row = requireNotNull(result.dispatch)
        assertEquals("DISPATCH_REQUESTED", row.state)
        assertEquals(3L, row.dispatchAttemptId)
        assertEquals(4L, row.attemptCount)
        assertEquals(1_100L, row.lastAttemptAt)
        assertNull(row.leaseOwner)
        assertNull(row.leaseExpiresAt)
        assertNull(row.failureReason)
        assertEquals("ARMED", row.recoverySlotAState)
        assertEquals(8L, row.recoverySlotAToken)
        assertEquals("CONSUMED", row.recoverySlotBState)
        assertNull(row.recoverySlotBAt)
        assertEquals(6L, row.recoverySlotBToken)
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
            WakeEventStoreOutcome.CONVERGED,
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
        val slotA = WakeEventArrival.Recovery(WakeRecoverySlotId.A, deliveredToken = 9L)
        assertEquals(
            WakeEventStoreOutcome.APPLIED,
            store.reduce(event, slotA, 1_100L, 500L).outcome,
        )
        val recoveryApplied = dispatchFingerprint()

        assertEquals(
            WakeEventStoreOutcome.CONVERGED,
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
    fun injectedFaultsBeforeAndAfterCasRollBackAllDispatchColumns() {
        listOf("BEFORE_CAS", "AFTER_CAS").forEachIndexed { index, point ->
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
            val before = dispatchFingerprint()
            val faulting =
                WakeEventDispatchStoreFaultFixture.create(database) {
                    if (it == point) error("injected-$point")
                }

            assertFailsWith<IllegalStateException> {
                faulting.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
            }
            assertEquals(before, dispatchFingerprint())
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
    fun twoConcurrentPrimaryCallersApplyExactlyOnceAndConverge() {
        insertDispatch(state = "RECEIVED", dispatchAttemptId = 4L, attemptCount = 6L)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls =
                (0 until 2).map {
                    executor.submit(
                        Callable {
                            start.await()
                            RoomWakeEventDispatchStore(database)
                                .reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
                                .outcome
                        }
                    )
                }
            start.countDown()
            val outcomes = calls.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it == WakeEventStoreOutcome.APPLIED })
            assertEquals(1, outcomes.count { it == WakeEventStoreOutcome.CONVERGED })
            val row = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
            assertEquals(5L, row.dispatchAttemptId)
            assertEquals(7L, row.attemptCount)
            assertEquals(1_100L, row.lastAttemptAt)
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
            WakeEventStoreOutcome.CONVERGED,
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

        assertEquals(WakeEventStoreOutcome.CONVERGED, duplicate.outcome)
        assertEquals(WakeEventConvergence.ALREADY_CONSUMED, duplicate.convergence)
        assertEquals(applied, dispatchFingerprint())
    }

    @Test
    fun primaryRequiresArmedMarkerAndConsumesItWhileRecoveryPreservesIt() {
        insertDispatch(state = "RECEIVED", armedPrimary = 0)
        val consumed = dispatchFingerprint()
        val duplicate = store.reduce(event, WakeEventArrival.Primary, 1_100L, 500L)
        assertEquals(WakeEventStoreOutcome.CONVERGED, duplicate.outcome)
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
                WakeEventArrival.Recovery(WakeRecoverySlotId.A, 4L),
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
                WakeEventArrival.Recovery(WakeRecoverySlotId.A, 9L),
                1_100L,
                500L,
            )
        assertEquals(WakeEventStoreOutcome.CONVERGED, exact.outcome)
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
                    WakeEventArrival.Recovery(WakeRecoverySlotId.A, 9L),
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
                    WakeEventArrival.Recovery(WakeRecoverySlotId.A, 9L),
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
                    WakeEventArrival.Recovery(WakeRecoverySlotId.A, 9L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(futureBefore, dispatchFingerprint())
    }

    @Test
    fun invalidRecoveryTokensFailClosedWithoutAdoptingDatabaseToken() {
        insertDispatch(
            state = "RECEIVED",
            slotAAt = 1_000L,
            slotAState = "FIRED",
            slotAToken = 10L,
        )
        listOf(-1L, Long.MAX_VALUE).forEach { delivered ->
            val before = dispatchFingerprint()
            assertEquals(
                WakeEventStoreOutcome.FAIL_CLOSED,
                store
                    .reduce(
                        event,
                        WakeEventArrival.Recovery(WakeRecoverySlotId.A, delivered),
                        1_100L,
                        500L,
                    )
                    .outcome,
            )
            assertEquals(before, dispatchFingerprint())
        }
        val mismatchBefore = dispatchFingerprint()
        assertEquals(
            WakeEventStoreOutcome.STALE_DELIVERY,
            store
                .reduce(
                    event,
                    WakeEventArrival.Recovery(WakeRecoverySlotId.A, 9L),
                    1_100L,
                    500L,
                )
                .outcome,
        )
        assertEquals(mismatchBefore, dispatchFingerprint())
    }

    @Test
    fun actualDaoCasReturnsZeroAndLeavesEntireRowUnchangedForEveryStaleFence() {
        data class StaleFence(
            val name: String,
            val armedPrimary: Int = 1,
            val primaryArrival: Boolean = true,
            val arrivingSlot: String? = null,
            val expectedState: String = "RECEIVED",
            val expectedAttempt: Long = 5L,
            val expectedSlotState: String? = null,
            val expectedSlotTrigger: Long? = null,
            val actualSlotToken: Long = 10L,
            val expectedSlotToken: Long? = null,
        )
        val cases =
            listOf(
                StaleFence("state", expectedState = "DEFERRED"),
                StaleFence("attempt", expectedAttempt = 4L),
                StaleFence("primary marker", armedPrimary = 0),
                StaleFence(
                    "recovery token",
                    primaryArrival = false,
                    arrivingSlot = "A",
                    expectedSlotState = "FIRED",
                    expectedSlotTrigger = 1_000L,
                    expectedSlotToken = 9L,
                ),
            )

        cases.forEachIndexed { index, case ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(
                state = "RECEIVED",
                dispatchAttemptId = 5L,
                attemptCount = 7L,
                armedPrimary = case.armedPrimary,
                slotAAt = 1_000L,
                slotAState = "FIRED",
                slotAToken = case.actualSlotToken,
            )
            val before = dispatchFingerprint()

            val changed =
                database
                    .wakeEventDispatchDao()
                    .compareAndSet(
                        eventKey = event.canonicalKey(),
                        expectedState = case.expectedState,
                        expectedDispatchAttemptId = case.expectedAttempt,
                        primaryArrival = case.primaryArrival,
                        arrivingSlot = case.arrivingSlot,
                        expectedSlotState = case.expectedSlotState,
                        expectedSlotTrigger = case.expectedSlotTrigger,
                        expectedSlotToken = case.expectedSlotToken,
                        nextState = "DISPATCH_REQUESTED",
                        nextDispatchAttemptId = 6L,
                        nextSlotState = "CONSUMED",
                        nextSlotTrigger = null,
                        nextSlotToken = 11L,
                        requestDispatch = true,
                        nowEpochMillis = 1_100L,
                    )

            assertEquals(0, changed, case.name)
            assertEquals(before, dispatchFingerprint(), case.name)
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
                event.canonicalKey(),
                event.snapshotId,
                event.kind.name,
                event.expectedTriggerEpochMillis,
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
