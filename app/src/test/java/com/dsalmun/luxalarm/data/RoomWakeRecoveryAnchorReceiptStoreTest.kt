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
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorState
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomWakeRecoveryAnchorReceiptStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var store: RoomWakeRecoveryAnchorReceiptStore
    private val goal = WakeEventIdentity("anchor-snapshot", WakeEventKind.GOAL, 2_000L)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-anchor-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeRecoveryAnchorReceiptStore(database)
        database.wakeRunStorageDao().createSnapshot(snapshot(), 900L)
        insertDispatch()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun everyAnchorKindClaimsArmedToFiredAndMutatesOnlyAnchorState() {
        WakeRecoveryAnchorKind.entries.forEachIndexed { index, kind ->
            database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
            val trigger = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))
            val pi =
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) "goal-primary-pi"
                else "anchor-$index-pi"
            insertAnchor(kind, trigger, "ARMED", pi)
            val protectedBefore = protectedFingerprint()
            val anchorBefore = anchorFingerprint()

            val result = store.claim(delivery(kind, trigger, pi, trigger))

            assertEquals(WakeRecoveryAnchorReceiptStoreOutcome.APPLIED, result.outcome, kind.name)
            assertEquals(WakeRecoveryAnchorState.FIRED, result.anchor?.state, kind.name)
            assertEquals(
                anchorBefore.mapIndexed { column, value -> if (column == 3) "FIRED" else value },
                anchorFingerprint(),
            )
            assertEquals(protectedBefore, protectedFingerprint(), kind.name)
        }
    }

    @Test
    fun durableStatesConvergeWithoutWrites() {
        val cases =
            listOf(
                "FIRED" to WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING,
                "CONSUMED" to WakeRecoveryAnchorReceiptStoreOutcome.DUPLICATE,
                "CANCELLED" to WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
            )
        cases.forEachIndexed { index, (state, expected) ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
            insertAnchor(state = state)
            val before = allFingerprint()
            val result = store.claim(delivery())
            assertEquals(expected, result.outcome, state)
            assertEquals(before, allFingerprint(), state)
        }
    }

    @Test
    fun nonterminalStatusesMayClaimWithoutWakeOwner() {
        listOf("PREPARED", "ACTIVE", "GOAL_REACHED").forEachIndexed { index, state ->
            if (index > 0) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
            }
            setStatus(state)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE migration_state SET schedule_owner = 'PREPARING_WAKE' WHERE id = 1"
            )
            insertAnchor()
            assertEquals(
                WakeRecoveryAnchorReceiptStoreOutcome.APPLIED,
                store.claim(delivery()).outcome,
                state,
            )
        }
    }

    @Test
    fun allTerminalStatusesAreStaleWithoutWrites() {
        listOf("COMPLETED", "NO_CONFIRMATION", "FAILED", "CANCELLED", "SUPERSEDED", "EXPIRED")
            .forEachIndexed { index, state ->
                if (index > 0)
                    database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
                setStatus(state)
                insertAnchor()
                val before = allFingerprint()
                assertEquals(
                    WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
                    store.claim(delivery()).outcome,
                    state,
                )
                assertEquals(before, allFingerprint(), state)
            }
    }

    @Test
    fun wrongImmutableIdentityAndEarlyDeliveryAreRejectedWithoutWrites() {
        insertAnchor()
        val wrongGoal = WakeEventIdentity(goal.snapshotId, WakeEventKind.GOAL, 2_001L)
        val cases =
            listOf(
                delivery(pi = "wrong-pi") to WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
                delivery(trigger = 2_001L) to WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
                delivery(receivedAt = 1_999L) to
                    WakeRecoveryAnchorReceiptStoreOutcome.STALE_DELIVERY,
                WakeRecoveryAnchorDelivery(
                    wrongGoal,
                    WakeRecoveryAnchorKind.GOAL_PRIMARY,
                    2_001L,
                    "goal-primary-pi",
                    2_001L,
                ) to WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                delivery(
                    kind = WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                    trigger = 62_000L,
                    receivedAt = 62_000L,
                ) to WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
            )
        cases.forEach { (candidate, expected) ->
            val before = allFingerprint()
            assertEquals(expected, store.claim(candidate).outcome)
            assertEquals(before, allFingerprint())
        }
    }

    @Test
    fun missingAndMalformedRequiredRowsFailClosedWithoutWrites() {
        insertAnchor()
        val corruptions =
            listOf(
                "DELETE FROM wake_recovery_anchor" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_recovery_anchor SET anchor_kind = 'BOGUS'" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_recovery_anchor SET state = 'BOGUS'" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_recovery_anchor SET trigger_epoch_ms = -1" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_recovery_anchor SET pending_intent_identity = ''" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_event_dispatch SET event_kind = 'START'" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_event_dispatch SET expected_trigger_epoch_ms = 2001" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_event_dispatch SET snapshot_id = 'other'" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_run_snapshot SET schedule_generation = -1" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_run_snapshot SET goal_epoch_ms = 2001" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_run_status SET state = 'BOGUS'" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
                "UPDATE wake_run_status SET execution_epoch = -1" to
                    WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
            )
        corruptions.forEachIndexed { index, (sql, expected) ->
            if (index > 0) resetRows()
            database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
            database.openHelper.writableDatabase.execSQL(sql)
            val before = allFingerprint()
            assertEquals(expected, store.claim(delivery()).outcome, sql)
            assertEquals(before, allFingerprint(), sql)
        }

        resetRows()
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_run_status")
        val beforeStatus = allFingerprint()
        assertEquals(
            WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
            store.claim(delivery()).outcome,
        )
        assertEquals(beforeStatus, allFingerprint())

        resetRows()
        insertAnchor()
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_run_snapshot")
        val beforeSnapshot = allFingerprint()
        assertEquals(
            WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
            store.claim(delivery()).outcome,
        )
        assertEquals(beforeSnapshot, allFingerprint())

        resetRows()
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        val beforeDispatch = allFingerprint()
        assertEquals(
            WakeRecoveryAnchorReceiptStoreOutcome.FAIL_CLOSED,
            store.claim(delivery()).outcome,
        )
        assertEquals(beforeDispatch, allFingerprint())
    }

    @Test
    fun maximumNonnegativeScheduleGenerationDoesNotOverflowReceiptClaim() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_snapshot SET schedule_generation = ? WHERE id = ?",
            arrayOf<Any>(Long.MAX_VALUE, goal.snapshotId),
        )
        insertAnchor()

        assertEquals(WakeRecoveryAnchorReceiptStoreOutcome.APPLIED, store.claim(delivery()).outcome)
        assertEquals(Long.MAX_VALUE.toString(), tableFingerprint("wake_run_snapshot")[2])
    }

    @Test
    fun actualDaoCasHasExactStalePredicateAndNeverChangesFailureReason() {
        insertAnchor(state = "ARMED", pi = "goal-primary-pi")
        val before = anchorFingerprint()
        val dao = database.wakeRecoveryAnchorDao()
        data class CasArgs(val key: String, val kind: String, val trigger: Long, val pi: String)
        listOf(
                CasArgs(goal.canonicalKey(), "GOAL_PRIMARY", 2_001L, "goal-primary-pi"),
                CasArgs(goal.canonicalKey(), "GOAL_PRIMARY", 2_000L, "wrong-pi"),
                CasArgs(goal.canonicalKey(), "GOAL_PLUS_1M", 2_000L, "goal-primary-pi"),
                CasArgs("wrong-key", "GOAL_PRIMARY", 2_000L, "goal-primary-pi"),
            )
            .forEach { args ->
                assertEquals(
                    0,
                    dao.compareAndSetArmedToFired(args.key, args.kind, args.trigger, args.pi),
                )
                assertEquals(before, anchorFingerprint())
            }
    }

    @Test
    fun faultsBeforeAndAfterCasRollBackEverything() {
        listOf("BEFORE_CAS", "AFTER_CAS").forEachIndexed { index, point ->
            if (index > 0) resetRows()
            insertAnchor()
            val before = allFingerprint()
            val faulting =
                WakeRecoveryAnchorReceiptStoreFaultFixture.create(database) {
                    if (it == point) error("injected-$point")
                }
            assertFailsWith<IllegalStateException> { faulting.claim(delivery()) }
            assertEquals(before, allFingerprint(), point)
            assertEquals(
                "ARMED",
                database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), "GOAL_PRIMARY")?.state,
            )
        }
    }

    @Test
    fun concurrentIdenticalCallersApplyExactlyOnceThenResume() {
        insertAnchor()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls =
                (0 until 2).map {
                    executor.submit(
                        Callable {
                            start.await()
                            RoomWakeRecoveryAnchorReceiptStore(database).claim(delivery()).outcome
                        }
                    )
                }
            start.countDown()
            val outcomes = calls.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == WakeRecoveryAnchorReceiptStoreOutcome.APPLIED })
            assertEquals(
                1,
                outcomes.count { it == WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING },
            )
            assertEquals(
                "FIRED",
                database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), "GOAL_PRIMARY")?.state,
            )
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun productionConstructorExposesOnlyDatabaseAndEntryOnlyAcceptsDelivery() {
        val accessible =
            RoomWakeRecoveryAnchorReceiptStore::class.java.declaredConstructors.filterNot {
                Modifier.isPrivate(it.modifiers)
            }
        assertEquals(1, accessible.size)
        assertEquals(listOf(AlarmDatabase::class.java), accessible.single().parameterTypes.toList())
        val claim =
            RoomWakeRecoveryAnchorReceiptStore::class.java.declaredMethods.single {
                it.name == "claim"
            }
        assertEquals(listOf(WakeRecoveryAnchorDelivery::class.java), claim.parameterTypes.toList())
    }

    private fun delivery(
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PRIMARY,
        trigger: Long = 2_000L,
        pi: String = "goal-primary-pi",
        receivedAt: Long = trigger,
    ) = WakeRecoveryAnchorDelivery(goal, kind, trigger, pi, receivedAt)

    private fun resetRows() {
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_run_status")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_run_snapshot")
        database.wakeRunStorageDao().createSnapshot(snapshot(), 900L)
        insertDispatch()
    }

    private fun insertDispatch() {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_event_dispatch(event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token) VALUES (?,?,'GOAL',2000,'RECEIVED',7,NULL,NULL,11,NULL,'preserve',1,NULL,'CONSUMED',0,NULL,'CONSUMED',0)""",
            arrayOf(goal.canonicalKey(), goal.snapshotId),
        )
    }

    private fun insertAnchor(
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PRIMARY,
        trigger: Long = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)),
        state: String = "ARMED",
        pi: String = "goal-primary-pi",
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,?,?,?,?)",
            arrayOf<Any>(goal.canonicalKey(), kind.name, trigger, state, pi),
        )
    }

    private fun setStatus(state: String) {
        val terminal = state !in setOf("PREPARED", "ACTIVE", "GOAL_REACHED")
        val completed =
            state in setOf("COMPLETED", "NO_CONFIRMATION", "FAILED", "SUPERSEDED", "EXPIRED")
        val cancelled = state == "CANCELLED"
        val failure = if (state == "NO_CONFIRMATION") "NO_CONFIRMATION_DEADLINE" else null
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state=?, active_service_owner_token=NULL, service_lease_owner=NULL, service_lease_expires_at=NULL, heartbeat_at=NULL, armed_start=?, armed_goal=?, completed_at=?, cancelled_at=?, failure_reason=? WHERE snapshot_id=?""",
            arrayOf<Any?>(
                state,
                if (terminal) 0 else 1,
                if (terminal) 0 else 1,
                if (completed) 3_000L else null,
                if (cancelled) 3_000L else null,
                failure,
                goal.snapshotId,
            ),
        )
    }

    private fun snapshot() =
        WakeRunSnapshotEntity(
            id = goal.snapshotId,
            occurrenceId = "occurrence-anchor",
            scheduleGeneration = 1L,
            routineRevision = 1L,
            calculationRuleVersion = 1L,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-04",
            wakeStartEpochMs = 1_000L,
            goalEpochMs = goal.expectedTriggerEpochMillis,
            lightPayload = "{}",
            musicPayload = "{}",
            vibrationPayload = "{}",
            selectedTrackId = null,
            selectedTrackStorageKey = null,
            dismissal = "CONFIRM",
            createdAt = 900L,
            installEpoch = "install-1",
        )

    private fun anchorFingerprint() = tableFingerprint("wake_recovery_anchor")

    private fun protectedFingerprint() =
        listOf(
                "wake_event_dispatch",
                "wake_run_snapshot",
                "wake_run_status",
                "migration_state",
                "schedule_outbox",
            )
            .map(::tableFingerprint)

    private fun allFingerprint() = protectedFingerprint() + anchorFingerprint()

    private fun tableFingerprint(table: String): List<String?> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor
            ->
            buildList {
                while (cursor.moveToNext()) for (column in 0 until cursor.columnCount) add(
                    if (cursor.isNull(column)) null else cursor.getString(column)
                )
            }
        }
}
