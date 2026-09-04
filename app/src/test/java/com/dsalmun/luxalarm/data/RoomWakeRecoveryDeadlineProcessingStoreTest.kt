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
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
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
class RoomWakeRecoveryDeadlineProcessingStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var store: RoomWakeRecoveryAnchorProcessingStore
    private val goal = WakeEventIdentity("deadline-snapshot", WakeEventKind.GOAL, 2_000L)
    private val start = WakeEventIdentity(goal.snapshotId, WakeEventKind.START, 1_000L)
    private val deadline = 1_802_000L

    @Test
    fun canonicalOutboxIdentityRejectsMalformedUtf16InsteadOfColliding() {
        assertFailsWith<IllegalArgumentException> {
            ScheduleOutboxCanonicalizer.hexUtf8("\uD800")
        }
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-deadline-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeRecoveryAnchorProcessingStore(database)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
        )
        database.wakeRunStorageDao().createSnapshot(snapshot(), 900L)
        insertDispatch(start, armedPrimary = 0)
        insertDispatch(goal, armedPrimary = 0)
        WakeRecoveryAnchorKind.entries.forEach { kind ->
            insertAnchor(
                kind,
                if (kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M) "FIRED" else "CONSUMED",
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun deadlineOwnerAuthorizationMatrixAllowsOnlyWakeToApply() {
        val cases =
            listOf(
                "WAKE" to WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
                "PREPARING_WAKE" to WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                "LEGACY" to WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                "RESTORING" to WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
            )

        cases.forEachIndexed { index, (owner, expectedOutcome) ->
            if (index > 0) resetDeadlineScenario()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE migration_state SET schedule_owner=? WHERE id=1",
                arrayOf(owner),
            )
            val before = wholeDatabaseFingerprint()

            val result = store.processDeadline(delivery())

            assertEquals(expectedOutcome, result.outcome, owner)
            val after = wholeDatabaseFingerprint()
            if (owner == "WAKE") {
                assertTrue(before != after, owner)
                assertEquals(
                    "NO_CONFIRMATION",
                    requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
                    owner,
                )
                assertTrue(outboxRows().any { it.command == "CREATE_NEXT" }, owner)
            } else {
                assertEquals(before, after, owner)
            }
        }
    }

    @Test
    fun wrongDeliveryRetainsStalePriorityForEveryUnauthorizedOwner() {
        listOf("PREPARING_WAKE", "LEGACY", "RESTORING").forEachIndexed { index, owner ->
            if (index > 0) resetDeadlineScenario()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE migration_state SET schedule_owner=? WHERE id=1",
                arrayOf(owner),
            )
            val before = wholeDatabaseFingerprint()
            val wrongDelivery = delivery().copy(pendingIntentIdentity = "wrong-delivery-pi")

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY,
                store.processDeadline(wrongDelivery).outcome,
                owner,
            )
            assertEquals(before, wholeDatabaseFingerprint(), owner)
        }
    }

    @Test
    fun preparedDeadlineWritesExactNoConfirmationStatusPostimage() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='PREPARED',processed_start_at=101,processed_goal_at=202,active_service_owner_token='active-owner',execution_epoch=7,service_lease_owner='lease-owner',service_lease_expires_at=1900000,heartbeat_at=1800000,armed_start=1,armed_goal=1,started_at=303,completed_at=NULL,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )

        val result = store.processDeadline(delivery())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        val row = requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId))
        assertEquals("NO_CONFIRMATION", row.state)
        assertEquals(101L, row.processedStartAt)
        assertEquals(202L, row.processedGoalAt)
        assertEquals(303L, row.startedAt)
        assertNull(row.activeServiceOwnerToken)
        assertNull(row.serviceLeaseOwner)
        assertNull(row.serviceLeaseExpiresAt)
        assertNull(row.heartbeatAt)
        assertEquals(8L, row.executionEpoch)
        assertEquals(0, row.armedStart)
        assertEquals(0, row.armedGoal)
        assertEquals(deadline, row.completedAt)
        assertNull(row.cancelledAt)
        assertEquals("NO_CONFIRMATION_DEADLINE", row.failureReason)
    }

    @Test
    fun activeDeadlineConsumesCurrentAndCancelsOnlyLiveSiblingAnchors() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state='ACTIVE',execution_epoch=4,completed_at=NULL,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?",
            arrayOf(goal.snapshotId),
        )
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "FIRED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_5M, "CONSUMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_15M, "CANCELLED")

        val result = store.processDeadline(delivery())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        assertEquals(
            "NO_CONFIRMATION",
            requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
        )
        assertEquals("CANCELLED", anchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY))
        assertEquals("CANCELLED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M))
        assertEquals("CONSUMED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_5M))
        assertEquals("CANCELLED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_15M))
        assertEquals("CONSUMED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_30M))
    }

    @Test
    fun terminalizesExactStartAndGoalDispatchPostimagesPreservingCountersAndTokens() {
        setDispatchLiveMatrix(
            start,
            "DISPATCH_REQUESTED",
            "owner-start",
            1,
            10_000L,
            "ARMED",
            31L,
            11_000L,
            "FIRED",
            41L,
        )
        setDispatchLiveMatrix(
            goal,
            "SERVICE_ACKED",
            "owner-goal",
            1,
            12_000L,
            "IN_FLIGHT",
            51L,
            null,
            "CANCELLED",
            61L,
        )

        val startBefore = dispatch(start)
        val goalBefore = dispatch(goal)
        val result = store.processDeadline(delivery())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        assertTerminalDispatch(startBefore, dispatch(start))
        assertTerminalDispatch(goalBefore, dispatch(goal))
        assertEquals("CANCELLED", dispatch(start).recoverySlotAState)
        assertEquals("CANCELLED", dispatch(start).recoverySlotBState)
        assertEquals("CANCELLED", dispatch(goal).recoverySlotAState)
        assertEquals("CANCELLED", dispatch(goal).recoverySlotBState)
        assertNull(dispatch(start).recoverySlotAAt)
        assertNull(dispatch(start).recoverySlotBAt)
        assertNull(dispatch(goal).recoverySlotAAt)
        assertNull(dispatch(goal).recoverySlotBAt)
    }

    private fun setDispatchLiveMatrix(
        event: WakeEventIdentity,
        state: String,
        owner: String,
        primary: Int,
        aAt: Long?,
        aState: String,
        aToken: Long,
        bAt: Long?,
        bState: String,
        bToken: Long,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state=?,lease_owner=?,lease_expires_at=1900000,armed_primary=?,recovery_slot_a_at=?,recovery_slot_a_state=?,recovery_slot_a_token=?,recovery_slot_b_at=?,recovery_slot_b_state=?,recovery_slot_b_token=? WHERE event_key=?""",
            arrayOf<Any?>(
                state,
                owner,
                primary,
                aAt,
                aState,
                aToken,
                bAt,
                bState,
                bToken,
                event.canonicalKey(),
            ),
        )
    }

    private fun dispatch(event: WakeEventIdentity): WakeEventDispatchEntity =
        requireNotNull(database.wakeRecoveryAnchorDao().dispatch(event.canonicalKey()))

    private fun assertTerminalDispatch(
        before: WakeEventDispatchEntity,
        after: WakeEventDispatchEntity,
    ) {
        assertEquals(
            before.copy(
                state = "TERMINAL",
                leaseOwner = null,
                leaseExpiresAt = null,
                failureReason = "NO_CONFIRMATION_DEADLINE",
                armedPrimary = 0,
                recoverySlotAAt = null,
                recoverySlotAState =
                    if (before.recoverySlotAState == "CONSUMED") "CONSUMED" else "CANCELLED",
                recoverySlotBAt = null,
                recoverySlotBState =
                    if (before.recoverySlotBState == "CONSUMED") "CONSUMED" else "CANCELLED",
            ),
            after,
        )
    }

    @Test
    fun insertsOnlyPreimageArmedCancellationTargetsAndOneNullKeyCreateNext() {
        setDispatchLiveMatrix(
            start,
            "DISPATCH_REQUESTED",
            "start-owner",
            1,
            10_000L,
            "ARMED",
            31L,
            11_000L,
            "FIRED",
            41L,
        )
        setDispatchLiveMatrix(
            goal,
            "DISPATCH_REQUESTED",
            "goal-owner",
            1,
            12_000L,
            "IN_FLIGHT",
            51L,
            null,
            "CANCELLED",
            61L,
        )
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_5M, "FIRED")

        store.processDeadline(delivery())

        val rows = outboxRows()
        assertEquals(5, rows.size)
        assertEquals(2, rows.count { it.command == "CANCEL_PRIMARY" })
        assertEquals(2, rows.count { it.command == "CANCEL_RECOVERY" })
        assertEquals(1, rows.count { it.command == "CREATE_NEXT" })
        assertEquals(1, rows.count { it.command == "CREATE_NEXT" && it.eventKey == null })
        assertTrue(rows.all { it.generation == 9L && it.state == "PENDING" })
        assertTrue(
            rows.all {
                it.attemptCount == 0L &&
                    it.notBeforeEpochMs == deadline &&
                    it.createdAt == deadline &&
                    it.lastError == null
            }
        )
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        assertTrue(rows.all { row -> row.id.all { it.code in 0x21..0x7e } })
        val ids = rows.map { it.id }
        assertTrue(ids.any { it.contains(start.canonicalKey()) && it.contains("START_PRIMARY") })
        assertTrue(ids.any { it.contains(goal.canonicalKey()) && it.contains("GOAL_PRIMARY") })
        assertTrue(
            ids.any {
                it.contains(start.canonicalKey()) && it.contains("DYNAMIC") && it.contains("31")
            }
        )
        assertTrue(ids.any { it.contains(goal.canonicalKey()) && it.contains("GOAL_PLUS_1M") })
        assertTrue(ids.none { it.contains("GOAL_PLUS_5M") })
    }

    @Test
    fun armedGoalPrimaryAliasWithoutArmedBitDoesNotEmitPrimaryCancellation() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "ARMED")

        val result = store.processDeadline(delivery())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        assertTrue(
            outboxRows().none {
                it.command == "CANCEL_PRIMARY" && it.eventKey == goal.canonicalKey()
            }
        )
    }

    @Test
    fun goalPrimaryAnchorAliasEmitsOnePrimaryCancellationWhenBitIsArmed() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "ARMED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET armed_primary=1 WHERE event_key=?",
            arrayOf(goal.canonicalKey()),
        )

        store.processDeadline(delivery())

        val goalPrimaryRows =
            outboxRows().filter {
                it.command == "CANCEL_PRIMARY" && it.eventKey == goal.canonicalKey()
            }
        assertEquals(1, goalPrimaryRows.size)
        assertTrue(goalPrimaryRows.single().id.contains("GOAL_PRIMARY"))
        assertTrue(
            goalPrimaryRows
                .single()
                .id
                .contains("anchor-GOAL_PRIMARY-pi".encodeToByteArray().toHex())
        )
    }

    @Test
    fun firedGoalPrimarySiblingSuppressesPrimaryCancellationEvenWhenBitIsArmed() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "FIRED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET armed_primary=1 WHERE event_key=?",
            arrayOf(goal.canonicalKey()),
        )

        val result = store.processDeadline(delivery())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        assertTrue(
            outboxRows().none {
                it.command == "CANCEL_PRIMARY" && it.eventKey == goal.canonicalKey()
            }
        )
    }

    @Test
    fun firedGoalPrimaryCurrentSuppressesPrimaryCancellationEvenWhenBitIsArmed() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "FIRED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET armed_primary=1 WHERE event_key=?",
            arrayOf(goal.canonicalKey()),
        )
        val primaryTrigger = goal.expectedTriggerEpochMillis

        val result =
            store.processDeadline(
                WakeRecoveryAnchorDelivery(
                    goal,
                    WakeRecoveryAnchorKind.GOAL_PRIMARY,
                    primaryTrigger,
                    "anchor-GOAL_PRIMARY-pi",
                    deadline,
                )
            )

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            result.outcome,
        )
        assertTrue(
            outboxRows().none {
                it.command == "CANCEL_PRIMARY" && it.eventKey == goal.canonicalKey()
            }
        )
    }

    @Test
    fun createNextSurvivesEventDispatchDeletionCascade() {
        store.processDeadline(delivery())
        val createNextId = outboxRows().single { it.command == "CREATE_NEXT" }.id

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM wake_event_dispatch WHERE snapshot_id=?",
            arrayOf(goal.snapshotId),
        )

        val survivor = requireNotNull(database.wakeRecoveryAnchorDao().outbox(createNextId))
        assertEquals("CREATE_NEXT", survivor.command)
        assertNull(survivor.eventKey)
    }

    @Test
    fun terminalStatusWithMissingStartDispatchFailsClosedInsteadOfClassifyingStale() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='COMPLETED',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=1700,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM wake_event_dispatch WHERE event_key=?",
            arrayOf(start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun terminalStatusWithMalformedStartDispatchFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='COMPLETED',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=1700,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET expected_trigger_epoch_ms=expected_trigger_epoch_ms+1 WHERE event_key=?",
            arrayOf(start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun deliveryMismatchWinsOverMalformedSiblingDispatch() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET expected_trigger_epoch_ms=expected_trigger_epoch_ms+1 WHERE event_key=?",
            arrayOf(start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery(deadline - 1L))

        assertEquals(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun deliveryIdentityMismatchWinsOverIndependentMalformedCurrentRowsWithoutWrites() {
        database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
        val mutations =
            listOf(
                "status" to
                    "UPDATE wake_run_status SET state='MALFORMED' WHERE snapshot_id='${goal.snapshotId}'",
                "snapshot" to
                    "UPDATE wake_run_snapshot SET goal_epoch_ms=goal_epoch_ms+1 WHERE id='${goal.snapshotId}'",
                "current dispatch" to
                    "UPDATE wake_event_dispatch SET state='MALFORMED' WHERE event_key='${goal.canonicalKey()}'",
                "current anchor non-identity" to
                    "UPDATE wake_recovery_anchor SET state='MALFORMED' WHERE event_key='${goal.canonicalKey()}' AND anchor_kind='GOAL_PLUS_30M'",
            )
        mutations.forEachIndexed { index, (label, sql) ->
            if (index > 0) resetDeadlineScenario()
            database.openHelper.writableDatabase.execSQL(sql)
            val before = wholeDatabaseFingerprint()
            val wrongDelivery = delivery().copy(pendingIntentIdentity = "wrong-delivery-pi")

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY,
                store.processDeadline(wrongDelivery).outcome,
                label,
            )
            assertEquals(before, wholeDatabaseFingerprint(), label)
        }
    }

    @Test
    fun exactNonterminalDeliveryRequiresFiredCurrentAnchorWithoutWrites() {
        val cases =
            listOf("PREPARED", "ACTIVE", "GOAL_REACHED").flatMap { status ->
                listOf("ARMED", "CONSUMED", "CANCELLED").map { anchorState ->
                    status to anchorState
                }
            }
        cases.forEachIndexed { index, (status, currentAnchorState) ->
            if (index > 0) resetDeadlineScenario()
            database.openHelper.writableDatabase.execSQL(
                "UPDATE wake_run_status SET state=? WHERE snapshot_id=?",
                arrayOf(status, goal.snapshotId),
            )
            setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_30M, currentAnchorState)
            val before = wholeDatabaseFingerprint()

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                store.processDeadline(delivery()).outcome,
                "$status/$currentAnchorState",
            )
            assertEquals(before, wholeDatabaseFingerprint(), "$status/$currentAnchorState")
        }
    }

    @Test
    fun malformedNoConfirmationStatusFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='NO_CONFIRMATION',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=NULL,cancelled_at=NULL,failure_reason='NO_CONFIRMATION_DEADLINE' WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun malformedCancelledStatusFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='CANCELLED',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=1700,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun terminalStatusWithOwnedTerminalDispatchFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='COMPLETED',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=1700,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='TERMINAL',lease_owner=NULL,lease_expires_at=NULL,armed_primary=0,recovery_slot_a_at=NULL,recovery_slot_a_state='CANCELLED',recovery_slot_b_at=NULL,recovery_slot_b_state='CANCELLED' WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET lease_owner='stale-owner',lease_expires_at=1900000 WHERE event_key=?",
            arrayOf(start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun canonicalTerminalStatusRejectsLiveOrTriggeredTerminalSlotsAcrossBothDispatches() {
        val malformedSlots =
            listOf(
                "ARMED" to 10_000L,
                "FIRED" to 10_000L,
                "IN_FLIGHT" to 10_000L,
                "CONSUMED" to 10_000L,
                "CANCELLED" to 10_000L,
            )
        val targets = listOf(start, goal).flatMap { event -> listOf(event to "a", event to "b") }
        val cases = targets.flatMap { target -> malformedSlots.map { target to it } }
        cases.forEachIndexed { index, case ->
            if (index > 0) resetDeadlineScenario()
            database.openHelper.writableDatabase.execSQL(
                """UPDATE wake_run_status SET state='COMPLETED',active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=1700,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
                arrayOf(goal.snapshotId),
            )
            database.openHelper.writableDatabase.execSQL(
                """UPDATE wake_event_dispatch SET state='TERMINAL',lease_owner=NULL,lease_expires_at=NULL,armed_primary=0,recovery_slot_a_at=NULL,recovery_slot_a_state='CONSUMED',recovery_slot_b_at=NULL,recovery_slot_b_state='CANCELLED' WHERE snapshot_id=?""",
                arrayOf(goal.snapshotId),
            )
            val (target, malformed) = case
            val (event, slot) = target
            val (slotState, trigger) = malformed
            database.openHelper.writableDatabase.execSQL(
                "UPDATE wake_event_dispatch SET recovery_slot_${slot}_state=?,recovery_slot_${slot}_at=? WHERE event_key=?",
                arrayOf<Any>(slotState, trigger, event.canonicalKey()),
            )
            val label = "${event.kind}/$slot/$slotState/non-null-trigger"
            val before = wholeDatabaseFingerprint()

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                store.processDeadline(delivery()).outcome,
                label,
            )
            assertEquals(before, wholeDatabaseFingerprint(), label)
        }
    }

    @Test
    fun allSixTerminalStatusesRejectStateSpecificMalformedRowsWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='TERMINAL',lease_owner=NULL,lease_expires_at=NULL,armed_primary=0,recovery_slot_a_at=NULL,recovery_slot_a_state='CANCELLED',recovery_slot_b_at=NULL,recovery_slot_b_state='CANCELLED' WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        listOf(
                TerminalStatusFixture("COMPLETED"),
                TerminalStatusFixture(
                    "NO_CONFIRMATION",
                    failureReason = "NO_CONFIRMATION_DEADLINE",
                ),
                TerminalStatusFixture("FAILED", completedAt = 1_700L),
                TerminalStatusFixture("CANCELLED"),
                TerminalStatusFixture("SUPERSEDED", cancelledAt = 1_700L),
                TerminalStatusFixture(
                    "EXPIRED",
                    failureReason = "NO_CONFIRMATION_DEADLINE",
                ),
            )
            .forEach { fixture ->
                database.openHelper.writableDatabase.execSQL(
                    """UPDATE wake_run_status SET state=?,active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=?,cancelled_at=?,failure_reason=? WHERE snapshot_id=?""",
                    arrayOf<Any?>(
                        fixture.state,
                        fixture.completedAt,
                        fixture.cancelledAt,
                        fixture.failureReason,
                        goal.snapshotId,
                    ),
                )
                val before = wholeDatabaseFingerprint()

                assertEquals(
                    WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                    store.processDeadline(delivery()).outcome,
                    fixture.state,
                )
                assertEquals(before, wholeDatabaseFingerprint(), fixture.state)
            }
    }

    @Test
    fun allSixTerminalStatusesWinWithoutOutboxRepair() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='TERMINAL',lease_owner=NULL,lease_expires_at=NULL,armed_primary=0,recovery_slot_a_at=NULL,recovery_slot_a_state='CANCELLED',recovery_slot_b_at=NULL,recovery_slot_b_state='CANCELLED' WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        listOf(
                TerminalStatusFixture("COMPLETED", completedAt = 1_700L),
                TerminalStatusFixture(
                    "NO_CONFIRMATION",
                    completedAt = 1_700L,
                    failureReason = "NO_CONFIRMATION_DEADLINE",
                ),
                TerminalStatusFixture("FAILED"),
                TerminalStatusFixture("CANCELLED", cancelledAt = 1_700L),
                TerminalStatusFixture("SUPERSEDED"),
                TerminalStatusFixture("EXPIRED"),
            )
            .forEach { fixture ->
                database.openHelper.writableDatabase.execSQL(
                    """UPDATE wake_run_status SET state=?,active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=?,cancelled_at=?,failure_reason=? WHERE snapshot_id=?""",
                    arrayOf<Any?>(
                        fixture.state,
                        fixture.completedAt,
                        fixture.cancelledAt,
                        fixture.failureReason,
                        goal.snapshotId,
                    ),
                )

                assertEquals(
                    WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL,
                    store.processDeadline(delivery()).outcome,
                )
                assertEquals(
                    fixture.state,
                    requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
                )
                assertTrue(outboxRows().isEmpty())
            }
    }

    @Test
    fun predeadlineDeliveryMismatchWinsAndDeadlinePlusOneUsesExactReceiptTime() {
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY,
            store.processDeadline(delivery(deadline - 1L)).outcome,
        )
        assertEquals(
            "PREPARED",
            requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
        )
        assertTrue(outboxRows().isEmpty())

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            store.processDeadline(delivery(deadline + 1L)).outcome,
        )
        assertEquals(
            deadline + 1L,
            requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).completedAt,
        )
        assertTrue(
            outboxRows().all {
                it.createdAt == deadline + 1L && it.notBeforeEpochMs == deadline + 1L
            }
        )
    }

    @Test
    fun armedSiblingControlCharacterIdentityFailsClosedWithoutWrites() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "ARMED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET pending_intent_identity=? WHERE event_key=? AND anchor_kind=?",
            arrayOf(
                "bad\u0001identity",
                goal.canonicalKey(),
                WakeRecoveryAnchorKind.GOAL_PLUS_1M.name,
            ),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun armedSiblingNonAsciiIdentityFailsClosedWithoutWrites() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "ARMED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET pending_intent_identity=? WHERE event_key=? AND anchor_kind=?",
            arrayOf("non-ascii-é", goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PLUS_1M.name),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun armedSiblingOversizeIdentityFailsClosedWithoutWrites() {
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "ARMED")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET pending_intent_identity=? WHERE event_key=? AND anchor_kind=?",
            arrayOf("x".repeat(513), goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PLUS_1M.name),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun malformedDispatchLeaseOwnerFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner=?,lease_expires_at=1900000 WHERE event_key=?",
            arrayOf("bad\u0001owner", start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = store.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun executionEpochMaxFailsClosedWithoutPartialWrites() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET execution_epoch=? WHERE snapshot_id=?",
            arrayOf<Any>(Long.MAX_VALUE, goal.snapshotId),
        )

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
            store.processDeadline(delivery()).outcome,
        )
        assertEquals(
            "PREPARED",
            requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
        )
        assertEquals("FIRED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_30M))
        assertTrue(outboxRows().isEmpty())
    }

    @Test
    fun overflowingDeadlineFailsClosedWithExactWholeDatabaseZeroWrites() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM wake_run_snapshot WHERE id=?",
            arrayOf(goal.snapshotId),
        )
        val overflowingGoal =
            WakeEventIdentity(
                "overflow-deadline-snapshot",
                WakeEventKind.GOAL,
                Long.MAX_VALUE - 1_799_999L,
            )
        val overflowingStart =
            WakeEventIdentity(overflowingGoal.snapshotId, WakeEventKind.START, 1_000L)
        database
            .wakeRunStorageDao()
            .createSnapshot(
                snapshot()
                    .copy(
                        id = overflowingGoal.snapshotId,
                        occurrenceId = "overflow-occurrence",
                        wakeStartEpochMs = overflowingStart.expectedTriggerEpochMillis,
                        goalEpochMs = overflowingGoal.expectedTriggerEpochMillis,
                    ),
                900L,
            )
        insertDispatch(overflowingStart, armedPrimary = 0)
        insertDispatch(overflowingGoal, armedPrimary = 0)
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,'GOAL_PLUS_30M',?,'FIRED','overflow-deadline-pi')""",
            arrayOf<Any>(overflowingGoal.canonicalKey(), Long.MAX_VALUE),
        )
        val before = wholeDatabaseFingerprint()

        val result =
            store.processDeadline(
                WakeRecoveryAnchorDelivery(
                    overflowingGoal,
                    WakeRecoveryAnchorKind.GOAL_PLUS_30M,
                    Long.MAX_VALUE,
                    "overflow-deadline-pi",
                    Long.MAX_VALUE,
                )
            )

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun deadlinePreservesFullProtectedRowFingerprints() {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO imported_track(id,storage_key,title,artist,duration_ms,mime_type,content_hash,lifecycle_state,availability,deletion_token,ref_count_cache,added_at) VALUES ('protected-track','protected/storage.mp3','Protected title','Protected artist',123456,'audio/mpeg','protected-content-hash','AVAILABLE','AVAILABLE',NULL,0,777)"""
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO track_lease(snapshot_id,track_id,acquired_at) VALUES (?,'protected-track',888)""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO schedule_occurrence_claim(canonical_occurrence_key,legacy_alarm_id,goal_epoch_ms,owner,state,fence_token,claimed_by,claimed_at) VALUES ('protected-occurrence',42,2000,'WAKE','CLAIMED',9,'claim-owner',666)"""
        )
        val protectedTables =
            listOf(
                "wake_run_snapshot",
                "migration_state",
                "imported_track",
                "track_lease",
                "schedule_occurrence_claim",
            )
        val protectedBefore = protectedTables.associateWith(::tableFingerprint)
        val statusBefore = tableFingerprint("wake_run_status")
        val dispatchBefore = tableFingerprint("wake_event_dispatch")
        val anchorsBefore = tableFingerprint("wake_recovery_anchor")
        val outboxBefore = tableFingerprint("schedule_outbox")

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            store.processDeadline(delivery()).outcome,
        )

        assertEquals(protectedBefore, protectedTables.associateWith(::tableFingerprint))
        assertTrue(statusBefore != tableFingerprint("wake_run_status"))
        assertTrue(dispatchBefore != tableFingerprint("wake_event_dispatch"))
        assertTrue(anchorsBefore != tableFingerprint("wake_recovery_anchor"))
        assertTrue(outboxBefore != tableFingerprint("schedule_outbox"))
    }

    @Test
    fun statusNoConfirmationCasChecksEveryExactAndNullSafePreimageColumnDirectly() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='ACTIVE',processed_start_at=101,processed_goal_at=NULL,active_service_owner_token='active-owner',execution_epoch=7,service_lease_owner='lease-owner',service_lease_expires_at=1900000,heartbeat_at=NULL,armed_start=1,armed_goal=0,started_at=303,completed_at=NULL,cancelled_at=404,failure_reason='SERVICE_START_FAILED' WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        val dao = database.wakeRecoveryAnchorDao()
        val actual = requireNotNull(dao.status(goal.snapshotId))
        val mismatches =
            listOf(
                "snapshotId" to actual.copy(snapshotId = "other-snapshot"),
                "state" to actual.copy(state = "PREPARED"),
                "processedStartAt" to actual.copy(processedStartAt = null),
                "processedGoalAt" to actual.copy(processedGoalAt = 202L),
                "activeServiceOwnerToken" to actual.copy(activeServiceOwnerToken = null),
                "executionEpoch" to actual.copy(executionEpoch = 8L),
                "serviceLeaseOwner" to actual.copy(serviceLeaseOwner = null),
                "serviceLeaseExpiresAt" to actual.copy(serviceLeaseExpiresAt = null),
                "heartbeatAt" to actual.copy(heartbeatAt = 505L),
                "armedStart" to actual.copy(armedStart = 0),
                "armedGoal" to actual.copy(armedGoal = 1),
                "startedAt" to actual.copy(startedAt = null),
                "completedAt" to actual.copy(completedAt = 606L),
                "cancelledAt" to actual.copy(cancelledAt = null),
                "failureReason" to actual.copy(failureReason = null),
            )
        mismatches.forEach { (label, expected) ->
            val before = tableFingerprint("wake_run_status")
            assertEquals(0, dao.compareAndSetStatusNoConfirmation(expected, deadline), label)
            assertEquals(before, tableFingerprint("wake_run_status"), label)
        }

        assertEquals(1, dao.compareAndSetStatusNoConfirmation(actual, deadline))
    }

    @Test
    fun terminalDispatchCasChecksEveryExactAndNullSafePreimageColumnDirectly() {
        setDispatchLiveMatrix(
            start,
            "DISPATCH_REQUESTED",
            "dispatch-owner",
            1,
            10_000L,
            "ARMED",
            31L,
            null,
            "CANCELLED",
            41L,
        )
        val dao = database.wakeRecoveryAnchorDao()
        val actual = dispatch(start)
        val next =
            actual.copy(
                state = "TERMINAL",
                leaseOwner = null,
                leaseExpiresAt = null,
                failureReason = "NO_CONFIRMATION_DEADLINE",
                armedPrimary = 0,
                recoverySlotAAt = null,
                recoverySlotAState = "CANCELLED",
            )
        val mismatches =
            listOf(
                "eventKey" to actual.copy(eventKey = "other-event"),
                "snapshotId" to actual.copy(snapshotId = "other-snapshot"),
                "eventKind" to actual.copy(eventKind = "GOAL"),
                "expectedTriggerEpochMs" to actual.copy(expectedTriggerEpochMs = 1_001L),
                "state" to actual.copy(state = "RECEIVED"),
                "dispatchAttemptId" to
                    actual.copy(dispatchAttemptId = actual.dispatchAttemptId + 1L),
                "leaseOwner" to actual.copy(leaseOwner = null),
                "leaseExpiresAt" to actual.copy(leaseExpiresAt = null),
                "attemptCount" to actual.copy(attemptCount = actual.attemptCount + 1L),
                "lastAttemptAt" to actual.copy(lastAttemptAt = null),
                "failureReason" to actual.copy(failureReason = null),
                "armedPrimary" to actual.copy(armedPrimary = 0),
                "slotAAt" to actual.copy(recoverySlotAAt = null),
                "slotAState" to actual.copy(recoverySlotAState = "FIRED"),
                "slotAToken" to actual.copy(recoverySlotAToken = 32L),
                "slotBAt" to actual.copy(recoverySlotBAt = 11_000L),
                "slotBState" to actual.copy(recoverySlotBState = "CONSUMED"),
                "slotBToken" to actual.copy(recoverySlotBToken = 42L),
            )
        mismatches.forEach { (label, expected) ->
            val before = tableFingerprint("wake_event_dispatch")
            assertEquals(0, dao.compareAndSetDispatchPostimage(expected, next), label)
            assertEquals(before, tableFingerprint("wake_event_dispatch"), label)
        }

        assertEquals(1, dao.compareAndSetDispatchPostimage(actual, next))
    }

    @Test
    fun anchorStateCasChecksEveryExactPreimageColumnDirectly() {
        val dao = database.wakeRecoveryAnchorDao()
        val actual =
            requireNotNull(
                dao.anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PLUS_30M.name)
            )
        val mismatches =
            listOf(
                "eventKey" to actual.copy(eventKey = "other-event"),
                "anchorKind" to actual.copy(anchorKind = WakeRecoveryAnchorKind.GOAL_PLUS_15M.name),
                "triggerEpochMs" to actual.copy(triggerEpochMs = actual.triggerEpochMs - 1L),
                "state" to actual.copy(state = "ARMED"),
                "pendingIntentIdentity" to actual.copy(pendingIntentIdentity = "other-pi"),
            )
        mismatches.forEach { (label, expected) ->
            val before = tableFingerprint("wake_recovery_anchor")
            assertEquals(0, dao.compareAndSetAnchorState(expected, "CONSUMED"), label)
            assertEquals(before, tableFingerprint("wake_recovery_anchor"), label)
        }

        assertEquals(1, dao.compareAndSetAnchorState(actual, "CONSUMED"))
    }

    @Test
    fun injectedCasMissWithCanonicalButChangedNonterminalStatusFailsClosedWithoutWrites() {
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET processed_start_at=999 WHERE snapshot_id=?",
            arrayOf(goal.snapshotId),
        )
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithValidUnchangedNonterminalPreimageRequiresRetryWithoutWrites() {
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithChangedCanonicalDispatchFailsClosedWithoutWrites() {
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET attempt_count=attempt_count+1 WHERE event_key=?",
            arrayOf(start.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithChangedCanonicalAnchorFailsClosedWithoutWrites() {
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET pending_intent_identity='changed-pi' WHERE event_key=? AND anchor_kind='GOAL_PLUS_15M'",
            arrayOf(goal.canonicalKey()),
        )
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithMissingOrMalformedDispatchFailsClosedWithoutWrites() {
        listOf(
                "DELETE FROM wake_event_dispatch WHERE event_key='${start.canonicalKey()}'",
                "UPDATE wake_event_dispatch SET expected_trigger_epoch_ms=expected_trigger_epoch_ms+1 WHERE event_key='${start.canonicalKey()}'",
            )
            .forEachIndexed { index, mutation ->
                if (index > 0) resetDeadlineScenario()
                val expectedPoststate = reflectedDeadlineExpectedPoststate()
                database.openHelper.writableDatabase.execSQL(mutation)
                val before = wholeDatabaseFingerprint()

                assertEquals(
                    WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                    classifyReflectedDeadlineCasMiss(expectedPoststate).outcome,
                )
                assertEquals(before, wholeDatabaseFingerprint())
            }
    }

    @Test
    fun injectedCasMissWithMissingOrMalformedAnchorFailsClosedWithoutWrites() {
        listOf(
                "DELETE FROM wake_recovery_anchor WHERE event_key='${goal.canonicalKey()}' AND anchor_kind='GOAL_PLUS_15M'",
                "UPDATE wake_recovery_anchor SET trigger_epoch_ms=trigger_epoch_ms+1 WHERE event_key='${goal.canonicalKey()}' AND anchor_kind='GOAL_PLUS_15M'",
            )
            .forEachIndexed { index, mutation ->
                if (index > 0) resetDeadlineScenario()
                val expectedPoststate = reflectedDeadlineExpectedPoststate()
                database.openHelper.writableDatabase.execSQL(mutation)
                val before = wholeDatabaseFingerprint()

                assertEquals(
                    WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                    classifyReflectedDeadlineCasMiss(expectedPoststate).outcome,
                )
                assertEquals(before, wholeDatabaseFingerprint())
            }
    }

    @Test
    fun injectedCasMissWithExactTerminalWinnerConvergesWithoutWrites() {
        prepareRichDeadlineOutbox()
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            store.processDeadline(delivery()).outcome,
        )
        val winner = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL, result.outcome)
        assertEquals(winner, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithAbsentExpectedOutboxFailsClosedWithoutWrites() {
        prepareRichDeadlineOutbox()
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        store.processDeadline(delivery())
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM schedule_outbox WHERE id=?",
            arrayOf(outboxRows().first().id),
        )
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun injectedCasMissWithMismatchedExistingOutboxFailsClosedWithoutWrites() {
        prepareRichDeadlineOutbox()
        val expectedPoststate = reflectedDeadlineExpectedPoststate()
        store.processDeadline(delivery())
        database.openHelper.writableDatabase.execSQL(
            "UPDATE schedule_outbox SET created_at=created_at+1 WHERE id=?",
            arrayOf(outboxRows().first().id),
        )
        val before = wholeDatabaseFingerprint()

        val result = classifyReflectedDeadlineCasMiss(expectedPoststate)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    private fun reflectedDeadlineExpectedPoststate(): Any {
        val dao = database.wakeRecoveryAnchorDao()
        val status = requireNotNull(dao.status(goal.snapshotId))
        val dispatches = dao.dispatches(goal.snapshotId)
        val anchors = dao.anchors(goal.canonicalKey())
        val snapshot = requireNotNull(dao.snapshot(goal.snapshotId))
        val terminalDispatches = dispatches.map { row ->
            row.copy(
                state = "TERMINAL",
                leaseOwner = null,
                leaseExpiresAt = null,
                failureReason = "NO_CONFIRMATION_DEADLINE",
                armedPrimary = 0,
                recoverySlotAAt = null,
                recoverySlotAState =
                    if (row.recoverySlotAState == "CONSUMED") "CONSUMED" else "CANCELLED",
                recoverySlotBAt = null,
                recoverySlotBState =
                    if (row.recoverySlotBState == "CONSUMED") "CONSUMED" else "CANCELLED",
            )
        }
        val nested = RoomWakeRecoveryAnchorProcessingStore::class.java.declaredClasses
        val dispatchClass = nested.single { it.simpleName == "DeadlineDispatches" }
        val dispatchConstructor = dispatchClass.declaredConstructors.single()
        dispatchConstructor.isAccessible = true
        val prestateDispatches =
            dispatchConstructor.newInstance(
                dispatches.single { it.eventKind == "START" },
                dispatches.single { it.eventKind == "GOAL" },
            )
        val expectedDispatches =
            dispatchConstructor.newInstance(
                terminalDispatches.single { it.eventKind == "START" },
                terminalDispatches.single { it.eventKind == "GOAL" },
            )
        val outboxMethod =
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredMethods.single {
                it.name == "deadlineOutboxRows"
            }
        outboxMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val outboxRows =
            outboxMethod.invoke(store, snapshot, dispatches, anchors, deadline)
                as List<ScheduleOutboxEntity>
        val prestateClass = nested.single { it.simpleName == "DeadlinePrestate" }
        val prestateConstructor = prestateClass.declaredConstructors.single()
        prestateConstructor.isAccessible = true
        val prestate =
            prestateConstructor.newInstance(
                snapshot,
                status,
                anchors.single { it.anchorKind == WakeRecoveryAnchorKind.GOAL_PLUS_30M.name },
                prestateDispatches,
                anchors,
            )
        val expectedClass = nested.single { it.simpleName == "DeadlineExpectedPoststate" }
        val expectedConstructor = expectedClass.declaredConstructors.single()
        expectedConstructor.isAccessible = true
        return expectedConstructor.newInstance(
            prestate,
            status.copy(
                state = "NO_CONFIRMATION",
                activeServiceOwnerToken = null,
                executionEpoch = status.executionEpoch + 1L,
                serviceLeaseOwner = null,
                serviceLeaseExpiresAt = null,
                heartbeatAt = null,
                armedStart = 0,
                armedGoal = 0,
                completedAt = deadline,
                cancelledAt = null,
                failureReason = "NO_CONFIRMATION_DEADLINE",
            ),
            expectedDispatches,
            anchors.map { row ->
                when {
                    row.anchorKind == WakeRecoveryAnchorKind.GOAL_PLUS_30M.name ->
                        row.copy(state = "CONSUMED")
                    row.state == "ARMED" || row.state == "FIRED" -> row.copy(state = "CANCELLED")
                    else -> row
                }
            },
            outboxRows,
        )
    }

    private fun classifyReflectedDeadlineCasMiss(
        expectedPoststate: Any
    ): WakeRecoveryAnchorProcessingResult {
        val nested = RoomWakeRecoveryAnchorProcessingStore::class.java.declaredClasses
        val missClass = nested.single { it.simpleName == "DeadlineCasMiss" }
        val missConstructor = missClass.declaredConstructors.single()
        missConstructor.isAccessible = true
        val miss = missConstructor.newInstance(delivery(), expectedPoststate)
        val method =
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredMethods.single {
                it.name == "classifyDeadlineCasMiss"
            }
        method.isAccessible = true
        return method.invoke(store, miss) as WakeRecoveryAnchorProcessingResult
    }

    @Test
    fun dispatchCasZeroRollsBackAndReturnsRetryWithoutSecondMutation() {
        val before = wholeDatabaseFingerprint()
        val faultStore =
            WakeRecoveryAnchorProcessingStoreFaultFixture.create(database) { point ->
                if (point == "AFTER_STATUS_CAS") {
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE wake_event_dispatch SET attempt_count=attempt_count+1 WHERE event_key=?",
                        arrayOf(start.canonicalKey()),
                    )
                }
            }

        val result = faultStore.processDeadline(delivery())

        assertEquals(WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED, result.outcome)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun everyDeadlineCasZeroRollsBackWithoutRepairMutation() {
        val cases =
            listOf(
                "BEFORE_STATUS_CAS" to
                    "UPDATE wake_run_status SET processed_start_at=999 WHERE snapshot_id='${goal.snapshotId}'",
                "AFTER_STATUS_CAS" to
                    "UPDATE wake_event_dispatch SET attempt_count=attempt_count+1 WHERE event_key='${start.canonicalKey()}'",
                "AFTER_START_DISPATCH" to
                    "UPDATE wake_event_dispatch SET attempt_count=attempt_count+1 WHERE event_key='${goal.canonicalKey()}'",
                "AFTER_GOAL_DISPATCH" to
                    "UPDATE wake_recovery_anchor SET pending_intent_identity='stale-pi' WHERE event_key='${goal.canonicalKey()}' AND anchor_kind='GOAL_PLUS_30M'",
            )
        cases.forEachIndexed { index, (point, sql) ->
            if (index > 0) resetDeadlineScenario()
            val before = wholeDatabaseFingerprint()
            val faultStore =
                WakeRecoveryAnchorProcessingStoreFaultFixture.create(database) { reached ->
                    if (reached == point) database.openHelper.writableDatabase.execSQL(sql)
                }

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.RETRY_REQUIRED,
                faultStore.processDeadline(delivery()).outcome,
                point,
            )
            assertEquals(before, wholeDatabaseFingerprint(), point)
        }
    }

    @Test
    fun twoDeadlineProcessorsProduceOneTerminalApplicationAndOneTypedStale() {
        setDispatchLiveMatrix(
            start,
            "DISPATCH_REQUESTED",
            "start-owner",
            1,
            10_000L,
            "ARMED",
            31L,
            null,
            "CANCELLED",
            41L,
        )
        setDispatchLiveMatrix(
            goal,
            "DISPATCH_REQUESTED",
            "goal-owner",
            1,
            12_000L,
            "ARMED",
            51L,
            null,
            "CANCELLED",
            61L,
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val startGate = CountDownLatch(1)
            val calls =
                (0 until 2).map {
                    executor.submit(
                        Callable {
                            startGate.await()
                            RoomWakeRecoveryAnchorProcessingStore(database)
                                .processDeadline(delivery())
                                .outcome
                        }
                    )
                }
            startGate.countDown()
            val outcomes = calls.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(
                1,
                outcomes.count {
                    it == WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION
                },
            )
            assertEquals(
                1,
                outcomes.count { it == WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL },
            )
            val rows = outboxRows()
            assertEquals(1, rows.count { it.command == "CREATE_NEXT" })
            assertEquals(rows.size, rows.map { it.id }.toSet().size)
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun predeadlineProcessorAfterDeadlineCannotCreateAttemptOrChangeTerminalPoststate() {
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            store.processDeadline(delivery()).outcome,
        )
        val terminal = wholeDatabaseFingerprint()

        val result =
            store.processFired(
                WakeRecoveryAnchorDelivery(
                    goal,
                    WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                    902_000L,
                    "anchor-GOAL_PLUS_15M-pi",
                    902_000L,
                ),
                proposedDispatchLeaseOwner = "late-predeadline-worker",
                proposedDispatchLeaseExpiresAtEpochMillis = deadline + 10_000L,
                maxHeartbeatAgeMillis = 500L,
            )

        assertEquals(WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL, result.outcome)
        assertEquals(terminal, wholeDatabaseFingerprint())
        assertTrue(
            database.wakeRecoveryAnchorDao().dispatches(goal.snapshotId).none {
                it.state == "DISPATCH_REQUESTED"
            }
        )
    }

    @Test
    fun richDeadlinePreimageProducesMaximumTenDeterministicOutboxRows() {
        prepareRichDeadlineOutbox()

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.TERMINALIZED_NO_CONFIRMATION,
            store.processDeadline(delivery()).outcome,
        )

        val rows = outboxRows()
        assertEquals(10, rows.size)
        assertEquals(2, rows.count { it.command == "CANCEL_PRIMARY" })
        assertEquals(4, rows.count { it.id.contains("DYNAMIC") })
        assertEquals(3, rows.count { it.id.contains("IMMUTABLE") })
        assertEquals(1, rows.count { it.command == "CREATE_NEXT" })
    }

    @Test
    fun deadlineFaultsRollbackEveryMutationAndEveryOutboxInsertion() {
        val points =
            listOf(
                "BEFORE_STATUS_CAS",
                "AFTER_STATUS_CAS",
                "BEFORE_START_DISPATCH",
                "AFTER_START_DISPATCH",
                "BEFORE_GOAL_DISPATCH",
                "AFTER_GOAL_DISPATCH",
                "BEFORE_ANCHORS",
                "AFTER_ANCHORS",
            ) +
                (0 until 10).flatMap { index ->
                    listOf("BEFORE_OUTBOX_INSERT_$index", "AFTER_OUTBOX_INSERT_$index")
                } +
                "BEFORE_RETURN"
        insertProtectedRows()
        points.forEachIndexed { index, point ->
            if (index > 0) resetDeadlineScenario()
            prepareRichDeadlineOutbox()
            val before = wholeDatabaseFingerprint()
            val reachedPoints = mutableSetOf<String>()
            val faultStore =
                WakeRecoveryAnchorProcessingStoreFaultFixture.create(database) { reached ->
                    reachedPoints += reached
                    if (reached == point) throw InjectedDeadlineFault(point)
                }

            val failure =
                assertFailsWith<InjectedDeadlineFault> {
                    faultStore.processDeadline(delivery())
                }

            assertTrue(point in reachedPoints, "fault hook was not reached: $point")
            assertEquals(point, failure.message)
            assertEquals(before, wholeDatabaseFingerprint(), point)
        }
    }

    private fun insertProtectedRows() {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO imported_track(id,storage_key,title,artist,duration_ms,mime_type,content_hash,lifecycle_state,availability,deletion_token,ref_count_cache,added_at) VALUES ('fault-protected-track','fault/protected.mp3','Fault protected title','Fault protected artist',123456,'audio/mpeg','fault-protected-hash','AVAILABLE','AVAILABLE',NULL,0,777)"""
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO track_lease(snapshot_id,track_id,acquired_at) VALUES (?,'fault-protected-track',888)""",
            arrayOf(goal.snapshotId),
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO schedule_occurrence_claim(canonical_occurrence_key,legacy_alarm_id,goal_epoch_ms,owner,state,fence_token,claimed_by,claimed_at) VALUES ('fault-protected-occurrence',42,2000,'WAKE','CLAIMED',9,'fault-claim-owner',666)"""
        )
    }

    @Test
    fun conflictingExistingOutboxPostimageRollsBackWholeDeadline() {
        val id =
            ScheduleOutboxCanonicalizer.id(
                "command" to "CREATE_NEXT",
                "generation" to "9",
                "snapshot_utf8_hex" to ScheduleOutboxCanonicalizer.hexUtf8(goal.snapshotId),
                "occurrence_utf8_hex" to ScheduleOutboxCanonicalizer.hexUtf8("occurrence-deadline"),
            )
        database
            .wakeRecoveryAnchorDao()
            .insertOutbox(
                ScheduleOutboxEntity(
                    id,
                    9L,
                    "CREATE_NEXT",
                    null,
                    "PENDING",
                    0L,
                    deadline,
                    deadline - 1L,
                    null,
                )
            )

        assertFailsWith<IllegalStateException> { store.processDeadline(delivery()) }

        assertEquals(
            "PREPARED",
            requireNotNull(database.wakeRecoveryAnchorDao().status(goal.snapshotId)).state,
        )
        assertEquals("RECEIVED", dispatch(goal).state)
        assertEquals("FIRED", anchorState(WakeRecoveryAnchorKind.GOAL_PLUS_30M))
        assertEquals(1, outboxRows().size)
    }

    private fun outboxRows(): List<ScheduleOutboxEntity> =
        database.openHelper.readableDatabase
            .query(
                "SELECT id,generation,command,event_key,state,attempt_count,not_before_epoch_ms,created_at,last_error FROM schedule_outbox ORDER BY id"
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ScheduleOutboxEntity(
                                id = cursor.getString(0),
                                generation = cursor.getLong(1),
                                command = cursor.getString(2),
                                eventKey = if (cursor.isNull(3)) null else cursor.getString(3),
                                state = cursor.getString(4),
                                attemptCount = cursor.getLong(5),
                                notBeforeEpochMs = cursor.getLong(6),
                                createdAt = cursor.getLong(7),
                                lastError = if (cursor.isNull(8)) null else cursor.getString(8),
                            )
                        )
                    }
                }
            }

    private fun wholeDatabaseFingerprint(): List<Pair<String, List<String?>>> =
        database.openHelper.readableDatabase
            .query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val table = cursor.getString(0)
                        add(table to tableFingerprint(table))
                    }
                }
            }

    private fun tableFingerprint(table: String): List<String?> =
        database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY 1").use { cursor
            ->
            buildList {
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        add(if (cursor.isNull(column)) null else cursor.getString(column))
                    }
                }
            }
        }

    private fun resetDeadlineScenario() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM schedule_outbox")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state='PREPARED',processed_start_at=NULL,processed_goal_at=NULL,active_service_owner_token=NULL,execution_epoch=0,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,started_at=NULL,completed_at=NULL,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf(goal.snapshotId),
        )
        insertDispatch(start, armedPrimary = 0)
        insertDispatch(goal, armedPrimary = 0)
        WakeRecoveryAnchorKind.entries.forEach { kind ->
            insertAnchor(
                kind,
                if (kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M) "FIRED" else "CONSUMED",
            )
        }
    }

    private fun prepareRichDeadlineOutbox() {
        setDispatchLiveMatrix(
            start,
            "DISPATCH_REQUESTED",
            "start-owner",
            1,
            10_000L,
            "ARMED",
            31L,
            11_000L,
            "ARMED",
            41L,
        )
        setDispatchLiveMatrix(
            goal,
            "DISPATCH_REQUESTED",
            "goal-owner",
            1,
            12_000L,
            "ARMED",
            51L,
            13_000L,
            "ARMED",
            61L,
        )
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PRIMARY, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_1M, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_5M, "ARMED")
        setAnchorState(WakeRecoveryAnchorKind.GOAL_PLUS_15M, "ARMED")
    }

    private fun setAnchorState(kind: WakeRecoveryAnchorKind, state: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state=? WHERE event_key=? AND anchor_kind=?",
            arrayOf(state, goal.canonicalKey(), kind.name),
        )
    }

    private fun anchorState(kind: WakeRecoveryAnchorKind): String? =
        database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name)?.state

    private fun delivery(receivedAt: Long = deadline) =
        WakeRecoveryAnchorDelivery(
            goal,
            WakeRecoveryAnchorKind.GOAL_PLUS_30M,
            deadline,
            "anchor-GOAL_PLUS_30M-pi",
            receivedAt,
        )

    private fun snapshot() =
        WakeRunSnapshotEntity(
            id = goal.snapshotId,
            occurrenceId = "occurrence-deadline",
            scheduleGeneration = 9L,
            routineRevision = 3L,
            calculationRuleVersion = 4L,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-04",
            wakeStartEpochMs = start.expectedTriggerEpochMillis,
            goalEpochMs = goal.expectedTriggerEpochMillis,
            lightPayload = "{}",
            musicPayload = "{}",
            vibrationPayload = "{}",
            selectedTrackId = null,
            selectedTrackStorageKey = null,
            dismissal = "CONFIRM",
            createdAt = 900L,
            installEpoch = "install-deadline",
        )

    private fun insertDispatch(event: WakeEventIdentity, armedPrimary: Int) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_event_dispatch(event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token) VALUES (?,?,?,?, 'RECEIVED',7,NULL,NULL,11,1500,'old failure',?,NULL,'CONSUMED',3,NULL,'CANCELLED',5)""",
            arrayOf<Any>(
                event.canonicalKey(),
                event.snapshotId,
                event.kind.name,
                event.expectedTriggerEpochMillis,
                armedPrimary,
            ),
        )
    }

    private fun insertAnchor(kind: WakeRecoveryAnchorKind, state: String) {
        val trigger = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,?,?,?,?)""",
            arrayOf<Any>(goal.canonicalKey(), kind.name, trigger, state, "anchor-${kind.name}-pi"),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class TerminalStatusFixture(
        val state: String,
        val completedAt: Long? = null,
        val cancelledAt: Long? = null,
        val failureReason: String? = null,
    )

    private class InjectedDeadlineFault(point: String) : RuntimeException(point)
}
