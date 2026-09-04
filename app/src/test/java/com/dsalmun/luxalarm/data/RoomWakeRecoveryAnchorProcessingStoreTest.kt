/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSource
import com.dsalmun.luxalarm.wake.WakeDispatchSourceKind
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
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
class RoomWakeRecoveryAnchorProcessingStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var store: RoomWakeRecoveryAnchorProcessingStore
    private val goal = WakeEventIdentity("processing-snapshot", WakeEventKind.GOAL, 2_000L)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-anchor-processing-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeRecoveryAnchorProcessingStore(database)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'WAKE', active_generation = 1 WHERE id = 1"
        )
        database.wakeRunStorageDao().createSnapshot(snapshot(), 900L)
        insertDispatch()
        insertFiredAnchor()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun firedPrimaryUnderWakeClaimsNewLeasedDispatchAndConsumesAnchor() {
        val result = process()

        assertEquals(WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST, result.outcome)
        assertEquals(goal.canonicalKey(), result.dispatchRequest?.eventKey)
        assertEquals(8L, result.dispatchRequest?.dispatchAttemptId)
        val authorization = requireNotNull(result.authorization)
        assertEquals(authorization.leaseOwner, result.dispatchRequest?.leaseOwner)
        assertEquals(62_100L, result.dispatchRequest?.leaseExpiresAtEpochMillis)
        val dispatch =
            requireNotNull(database.wakeRecoveryAnchorDao().dispatch(goal.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", dispatch.state)
        assertEquals(8L, dispatch.dispatchAttemptId)
        assertEquals(12L, dispatch.attemptCount)
        assertEquals(2_100L, dispatch.lastAttemptAt)
        assertEquals(authorization.leaseOwner, dispatch.leaseOwner)
        assertEquals(62_100L, dispatch.leaseExpiresAt)
        assertEquals(null, dispatch.failureReason)
        assertEquals(0, dispatch.armedPrimary)
        assertEquals(
            "CONSUMED",
            database
                .wakeRecoveryAnchorDao()
                .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name)
                ?.state,
        )
    }

    @Test
    fun allDispatchingAnchorKindsAuthorizeAtTheirExactBoundary() {
        resetDispatchAndAnchor()
        WakeRecoveryAnchorKind.entries.dropLast(1).forEachIndexed { index, kind ->
            if (index > 0) resetDispatchAndAnchor()
            val trigger = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))
            val pi =
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) {
                    WakePendingIntentData.primary(goal)
                } else {
                    WakePendingIntentData.anchor(goal, kind)
                }
            insertDispatch(armedPrimary = 1)
            insertFiredAnchor(kind, trigger, pi)
            val dispatchBefore = dispatchFingerprint()

            val result = process(delivery(kind, trigger, pi, trigger))

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                result.outcome,
                kind.name,
            )
            val authorization = requireNotNull(result.authorization)
            val source = sourceFor(delivery(kind, trigger, pi, trigger))
            val expectedAuthorization =
                WakeDispatchAuthorizationFactory.create(
                    goal,
                    scheduleGeneration = 1L,
                    dispatchAttemptId = 8L,
                    expectedExecutionEpoch = 3L,
                    leaseExpiresAt = trigger + 60_000L,
                    source = source,
                )
            val dispatch =
                requireNotNull(database.wakeRecoveryAnchorDao().dispatch(goal.canonicalKey()))
            assertEquals(expectedAuthorization.event, authorization.event, kind.name)
            assertEquals(expectedAuthorization.eventKey, authorization.eventKey, kind.name)
            assertEquals(
                expectedAuthorization.scheduleGeneration,
                authorization.scheduleGeneration,
                kind.name,
            )
            assertEquals(
                expectedAuthorization.dispatchAttemptId,
                authorization.dispatchAttemptId,
                kind.name,
            )
            assertEquals(
                expectedAuthorization.expectedExecutionEpoch,
                authorization.expectedExecutionEpoch,
                kind.name,
            )
            assertEquals(expectedAuthorization.leaseOwner, authorization.leaseOwner, kind.name)
            assertEquals(
                expectedAuthorization.leaseExpiresAt,
                authorization.leaseExpiresAt,
                kind.name,
            )
            assertEquals(expectedAuthorization.requestedAt, authorization.requestedAt, kind.name)
            assertEquals(expectedAuthorization.source, authorization.source, kind.name)
            assertEquals(dispatch.leaseOwner, authorization.leaseOwner, kind.name)
            assertEquals(trigger + 60_000L, authorization.leaseExpiresAt, kind.name)
            assertEquals(
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) 0 else 1,
                dispatch.armedPrimary,
                kind.name,
            )
            assertEquals("CONSUMED", anchorState(kind), kind.name)
        }
    }

    @Test
    fun wakeReceivedAndDeferredClaimNewAttemptWhileValidRequestedIsPreserved() {
        resetDispatchAndAnchor()
        listOf("RECEIVED", "DEFERRED").forEachIndexed { index, state ->
            if (index > 0) resetDispatchAndAnchor()
            insertDispatch(state = state)
            insertFiredAnchor()
            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                process().outcome,
                state,
            )
            assertEquals("CONSUMED", anchorState())
        }

        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 4_000L,
            armedPrimary = 0,
        )
        insertFiredAnchor()
        val dispatchBefore = dispatchFingerprint()

        val existing = process()

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            existing.outcome,
        )
        assertNull(existing.dispatchRequest)
        assertEquals(dispatchBefore, dispatchFingerprint())
        assertEquals("CONSUMED", anchorState())
    }

    @Test
    fun missingMalformedOrExpiredRequestedLeaseClaimsNewAttempt() {
        resetDispatchAndAnchor()
        val leases =
            listOf(
                null to null,
                "bad\nowner" to 4_000L,
                "expired-owner" to 2_100L,
            )
        leases.forEachIndexed { index, (owner, expiry) ->
            if (index > 0) resetDispatchAndAnchor()
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
            insertDispatch(
                state = "DISPATCH_REQUESTED",
                leaseOwner = owner,
                leaseExpiresAt = expiry,
                armedPrimary = 1,
            )
            insertFiredAnchor()

            val result = process()
            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                result.outcome,
                "owner=$owner expiry=$expiry",
            )
            val dispatch =
                requireNotNull(database.wakeRecoveryAnchorDao().dispatch(goal.canonicalKey()))
            assertEquals(8L, dispatch.dispatchAttemptId)
            assertEquals(12L, dispatch.attemptCount)
            assertEquals(requireNotNull(result.authorization).leaseOwner, dispatch.leaseOwner)
            assertEquals("CONSUMED", anchorState())
        }
    }

    @Test
    fun serviceAckedRequiresExactHealthyDurableEvidence() {
        resetDispatchAndAnchor()
        insertDispatch(
            state = "SERVICE_ACKED",
            leaseOwner = "service-owner",
            leaseExpiresAt = 2_000L,
            armedPrimary = 1,
        )
        setStatusLease(
            state = "ACTIVE",
            activeOwner = "service-owner",
            serviceOwner = "service-owner",
            serviceExpiry = 4_500L,
            heartbeat = 2_000L,
        )
        insertFiredAnchor()
        val dispatchBefore = dispatchFingerprint()

        val result = process(maxHeartbeatAgeMillis = 100L)

        assertEquals(WakeRecoveryAnchorProcessingOutcome.HEALTHY_EXECUTION, result.outcome)
        assertEquals(
            dispatchBefore.mapIndexed { index, value -> if (index == 11) "0" else value },
            dispatchFingerprint(),
        )
        assertEquals(0, dispatchRow().armedPrimary)
        assertEquals("CONSUMED", anchorState())
    }

    @Test
    fun preparingWakeCreatesOrRecognizesOnlyDurableDeferredWork() {
        setOwner("PREPARING_WAKE")
        val receivedBefore = dispatchRow()

        val received = process()

        assertEquals(WakeRecoveryAnchorProcessingOutcome.DEFERRED_DURABLE, received.outcome)
        assertEquals(
            receivedBefore.copy(state = "DEFERRED", armedPrimary = 0),
            dispatchRow(),
        )
        assertEquals("CONSUMED", anchorState())

        resetDispatchAndAnchor()
        insertDispatch(state = "DEFERRED", armedPrimary = 1)
        insertFiredAnchor()
        val deferredBefore = dispatchFingerprint()
        val deferred = process()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.DEFERRED_DURABLE, deferred.outcome)
        assertEquals(
            deferredBefore.mapIndexed { index, value -> if (index == 11) "0" else value },
            dispatchFingerprint(),
        )
        assertEquals(0, dispatchRow().armedPrimary)
        assertEquals("CONSUMED", anchorState())
    }

    @Test
    fun preparingWakeUsesNormalDurableEvidenceButDoesNotInventIt() {
        setOwner("PREPARING_WAKE")
        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 4_000L,
            armedPrimary = 0,
        )
        insertFiredAnchor()
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            process().outcome,
        )
        assertEquals("CONSUMED", anchorState())

        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "expired-owner",
            leaseExpiresAt = 2_100L,
        )
        insertFiredAnchor()
        val before = allFingerprint()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
        assertEquals(before, allFingerprint())
    }

    @Test
    fun legacyAndRestoringOwnersFailClosedAndLeaveFired() {
        listOf("LEGACY", "RESTORING").forEach { owner ->
            resetDispatchAndAnchor()
            insertDispatch()
            insertFiredAnchor()
            setOwner(owner)
            val before = allFingerprint()
            assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome, owner)
            assertEquals(before, allFingerprint(), owner)
            assertEquals("FIRED", anchorState())
        }
    }

    @Test
    fun allNonterminalRunStatesMayRecoverAndAllTerminalStatesAreStale() {
        listOf("PREPARED", "ACTIVE", "GOAL_REACHED").forEach { state ->
            resetDispatchAndAnchor()
            insertDispatch()
            insertFiredAnchor()
            setStatusLease(state = state)
            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                process().outcome,
                state,
            )
        }

        listOf("COMPLETED", "NO_CONFIRMATION", "FAILED", "CANCELLED", "SUPERSEDED", "EXPIRED")
            .forEach { state ->
                resetDispatchAndAnchor()
                insertDispatch()
                insertFiredAnchor()
                setTerminalStatus(state)
                val before = allFingerprint()
                assertEquals(
                    WakeRecoveryAnchorProcessingOutcome.STALE_TERMINAL,
                    process().outcome,
                    state,
                )
                assertEquals(before, allFingerprint(), state)
            }
    }

    @Test
    fun terminalConsumedPrimaryWithArmedBitFailsClosedWithoutWrites() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=1"""
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state='CONSUMED'"
        )
        setTerminalStatus("COMPLETED")
        val before = allFingerprint()

        val result = process()

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertNull(result.dispatchRequest)
        assertEquals(WakeRecoveryAnchorProcessingRecommendation.NONE, result.recommendation)
        assertEquals(before, allFingerprint())
    }

    @Test
    fun terminalMalformedSourceDeliveryPairFailsClosedBeforePrimaryCorruptionClassification() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=1"""
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state='CONSUMED'"
        )
        setTerminalStatus("COMPLETED")
        val before = allFingerprint()

        val wrongDelivery = delivery(pi = "wrong-primary-pi")
        val result =
            store.processFired(
                wrongDelivery,
                sourceFor(delivery()),
                maxHeartbeatAgeMillis = 500L,
            )

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
        assertNull(result.dispatchRequest)
        assertEquals(WakeRecoveryAnchorProcessingRecommendation.NONE, result.recommendation)
        assertEquals(before, allFingerprint())
    }

    @Test
    fun malformedGoalPrimarySourcesFailClosedBeforeAnyDatabaseMutation() {
        val candidate = delivery()
        val malformedSources =
            listOf(
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_PLUS_1M,
                    candidate.pendingIntentIdentity,
                    candidate.receivedAtEpochMillis,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_PRIMARY,
                    candidate.pendingIntentIdentity,
                    candidate.receivedAtEpochMillis + 1L,
                ),
                WakeDispatchSource(
                    WakeDispatchSourceKind.GOAL_PRIMARY,
                    WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M),
                    candidate.receivedAtEpochMillis,
                ),
            )
        malformedSources.forEach { source ->
            val before = allFingerprint()
            val result = store.processFired(candidate, source, 500L)
            assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, result.outcome)
            assertNull(result.authorization)
            assertNull(result.dispatchRequest)
            assertEquals(before, allFingerprint())
        }
    }

    @Test
    fun primaryDurableEvidenceClearsArmedBitBeforeConsumingAnchor() {
        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 4_000L,
            armedPrimary = 1,
        )
        insertFiredAnchor()

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            process().outcome,
        )
        assertEquals(0, dispatchRow().armedPrimary)
        assertEquals("CONSUMED", anchorState())
    }

    @Test
    fun primaryArmingFencesNewClaimsButNotExistingDurableEvidence() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET armed_primary = 0"
        )
        val unarmedBefore = allFingerprint()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
        assertEquals(unarmedBefore, allFingerprint())

        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 4_000L,
            armedPrimary = 0,
        )
        insertFiredAnchor()
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            process().outcome,
        )
        assertEquals("CONSUMED", anchorState())

        resetDispatchAndAnchor()
        insertDispatch(armedPrimary = 0)
        val plusOneIdentity =
            WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        insertFiredAnchor(WakeRecoveryAnchorKind.GOAL_PLUS_1M, 62_000L, plusOneIdentity)
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
            process(
                    delivery(
                        WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                        62_000L,
                        plusOneIdentity,
                        62_000L,
                    )
                )
                .outcome,
        )
        assertEquals(0, dispatchRow().armedPrimary)
    }

    @Test
    fun eitherMaximumDispatchCounterFailsClosedWithFiredAnchor() {
        listOf(Long.MAX_VALUE to 11L, 7L to Long.MAX_VALUE).forEachIndexed {
            index,
            (attemptId, count) ->
            if (index > 0) resetDispatchAndAnchor()
            database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(dispatchAttemptId = attemptId, attemptCount = count)
            insertFiredAnchor()
            val before = allFingerprint()
            assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
            assertEquals(before, allFingerprint())
            assertEquals("FIRED", anchorState())
        }
    }

    @Test
    fun argumentsAreValidatedBeforeAnyDatabaseWrite() {
        val invalidCalls: List<() -> Unit> =
            listOf(
                { process(maxHeartbeatAgeMillis = 0L) },
                { process(maxHeartbeatAgeMillis = Long.MIN_VALUE) },
            )
        invalidCalls.forEach { call ->
            val before = allFingerprint()
            assertFailsWith<IllegalArgumentException> { call() }
            assertEquals(before, allFingerprint())
        }
    }

    @Test
    fun everyUnhealthyServiceAckClaimsNewAttempt() {
        data class Evidence(
            val dispatchOwner: String? = "service-owner",
            val dispatchExpiry: Long? = 2_000L,
            val activeOwner: String? = "service-owner",
            val executionEpoch: Long = 3L,
            val serviceOwner: String? = "service-owner",
            val serviceExpiry: Long? = 4_500L,
            val heartbeat: Long? = 2_000L,
        )
        val cases =
            listOf(
                Evidence(dispatchOwner = null, dispatchExpiry = null),
                Evidence(dispatchOwner = "other-owner"),
                Evidence(activeOwner = "other-owner"),
                Evidence(executionEpoch = 0L),
                Evidence(serviceOwner = "other-owner"),
                Evidence(serviceExpiry = 2_100L),
                Evidence(serviceOwner = null, serviceExpiry = null, heartbeat = null),
                Evidence(heartbeat = 2_101L),
                Evidence(heartbeat = 1_999L),
            )
        cases.forEach { evidence ->
            resetDispatchAndAnchor()
            insertDispatch(
                state = "SERVICE_ACKED",
                leaseOwner = evidence.dispatchOwner,
                leaseExpiresAt = evidence.dispatchExpiry,
            )
            setStatusLease(
                state = "ACTIVE",
                activeOwner = evidence.activeOwner,
                executionEpoch = evidence.executionEpoch,
                serviceOwner = evidence.serviceOwner,
                serviceExpiry = evidence.serviceExpiry,
                heartbeat = evidence.heartbeat,
            )
            insertFiredAnchor()

            val result = process(maxHeartbeatAgeMillis = 100L)

            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST,
                result.outcome,
                evidence.toString(),
            )
            assertEquals(requireNotNull(result.authorization).leaseOwner, dispatchRow().leaseOwner)
            assertEquals("CONSUMED", anchorState())
        }
    }

    @Test
    fun dispatchTerminalWithNonterminalRunFailsClosed() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET state = 'TERMINAL'"
        )
        val before = allFingerprint()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
        assertEquals(before, allFingerprint())
    }

    @Test
    fun exactConsumedDuplicateConvergesOnlyFromDurableEvidence() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=0"""
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state='CONSUMED'"
        )
        val before = allFingerprint()

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            process().outcome,
        )
        assertEquals(before, allFingerprint())

        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET state='RECEIVED',lease_owner=NULL,lease_expires_at=NULL"
        )
        val unsafe = allFingerprint()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY, process().outcome)
        assertEquals(unsafe, allFingerprint())
    }

    @Test
    fun consumedPrimaryWithArmedBitIsCorruptionEvenWithDurableEvidence() {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=1"""
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state='CONSUMED'"
        )
        val before = allFingerprint()

        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
        assertEquals(before, allFingerprint())
    }

    @Test
    fun consumedOrdinaryAnchorDuplicateDoesNotDependOnPrimaryBit() {
        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 63_000L,
            armedPrimary = 1,
        )
        val plusOneIdentity =
            WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        insertFiredAnchor(WakeRecoveryAnchorKind.GOAL_PLUS_1M, 62_000L, plusOneIdentity)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET state='CONSUMED'"
        )
        val before = allFingerprint()

        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST,
            process(
                    delivery(
                        WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                        62_000L,
                        plusOneIdentity,
                        62_000L,
                    )
                )
                .outcome,
        )
        assertEquals(before, allFingerprint())
    }

    @Test
    fun databaseFailurePropagatesInsteadOfBecomingFailClosed() {
        database.close()

        assertFailsWith<RuntimeException> { process() }
    }

    @Test
    fun injectedFaultsRollbackEveryParticipatingTable() {
        data class FaultCase(val point: String, val prepare: () -> Unit)
        val cases =
            listOf(
                FaultCase("BEFORE_DISPATCH_CAS") {},
                FaultCase("AFTER_DISPATCH_CAS") {},
                FaultCase("AFTER_PRIMARY_CLEAR_CAS") {
                    database.openHelper.writableDatabase.execSQL(
                        """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=1"""
                    )
                },
                FaultCase("AFTER_ANCHOR_CAS") {
                    database.openHelper.writableDatabase.execSQL(
                        """UPDATE wake_event_dispatch SET state='DISPATCH_REQUESTED',lease_owner='existing-owner',lease_expires_at=4000,armed_primary=1"""
                    )
                },
                FaultCase("BEFORE_RETURN") {},
            )
        cases.forEachIndexed { index, case ->
            if (index > 0) {
                resetDispatchAndAnchor()
                insertDispatch()
                insertFiredAnchor()
            }
            case.prepare()
            database.openHelper.writableDatabase.execSQL(
                """INSERT OR REPLACE INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,'GOAL_PLUS_1M',62000,'ARMED','fault-sibling-pi')""",
                arrayOf(goal.canonicalKey()),
            )
            database.openHelper.writableDatabase.execSQL(
                """INSERT OR REPLACE INTO schedule_outbox(id,generation,command,event_key,state,attempt_count,not_before_epoch_ms,created_at,last_error) VALUES ('fault-outbox',1,'RECONCILE',?,'PENDING',2,900,800,'keep')""",
                arrayOf(goal.canonicalKey()),
            )
            val before = allFingerprint()
            val faultStore =
                WakeRecoveryAnchorProcessingStoreFaultFixture.create(database) { point ->
                    if (point == case.point) throw InjectedProcessingFault(point)
                }

            val failure =
                assertFailsWith<InjectedProcessingFault> {
                    faultStore.processFired(delivery(), sourceFor(delivery()), 500L)
                }

            assertEquals(case.point, failure.message)
            assertEquals(before, allFingerprint(), case.point)
            assertEquals("FIRED", anchorState(), case.point)
        }
    }

    @Test
    fun resultShapeIsOpaqueAndFactoryRejectsImpossibleOutcomePayloads() {
        assertTrue(WakeRecoveryAnchorProcessingResult::class.java.isInterface)
        assertTrue(WakeRecoveryAnchorProcessingResult::class.java.declaredConstructors.isEmpty())
        assertTrue(
            WakeRecoveryAnchorProcessingResult::class.java.declaredMethods.none {
                it.name == "copy" || it.name == "copy\$default" || it.isSynthetic || it.isBridge
            }
        )
        val authorization =
            WakeDispatchAuthorizationFactory.create(goal, 1L, 1L, 3L, 3_000L, sourceFor(delivery()))
        WakeRecoveryAnchorProcessingOutcome.entries.forEach { outcome ->
            val request =
                WakeRecoveryAnchorDispatchRequest(
                    authorization.eventKey,
                    authorization.dispatchAttemptId,
                    authorization.leaseOwner,
                    authorization.leaseExpiresAt,
                )
            if (outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST) {
                WakeRecoveryAnchorProcessingResultFactory.create(outcome, request, authorization)
                assertFailsWith<IllegalArgumentException> {
                    WakeRecoveryAnchorProcessingResultFactory.create(outcome, null, authorization)
                }
                assertFailsWith<IllegalArgumentException> {
                    WakeRecoveryAnchorProcessingResultFactory.create(outcome, request, null)
                }
                assertFailsWith<IllegalArgumentException> {
                    WakeRecoveryAnchorProcessingResultFactory.create(
                        outcome,
                        request.copy(dispatchAttemptId = request.dispatchAttemptId + 1L),
                        authorization,
                    )
                }
            } else {
                WakeRecoveryAnchorProcessingResultFactory.create(outcome, null, null)
                assertFailsWith<IllegalArgumentException> {
                    WakeRecoveryAnchorProcessingResultFactory.create(outcome, request, null)
                }
                assertFailsWith<IllegalArgumentException> {
                    WakeRecoveryAnchorProcessingResultFactory.create(outcome, null, authorization)
                }
            }
            val expectedRecommendation =
                if (outcome == WakeRecoveryAnchorProcessingOutcome.OUT_OF_SCOPE_DEADLINE) {
                    WakeRecoveryAnchorProcessingRecommendation.DEFER_TO_TERMINAL
                } else {
                    WakeRecoveryAnchorProcessingRecommendation.NONE
                }
            assertEquals(
                expectedRecommendation,
                WakeRecoveryAnchorProcessingResultFactory.create(
                        outcome,
                        if (outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST) {
                            request
                        } else {
                            null
                        },
                        if (outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST) {
                            authorization
                        } else {
                            null
                        },
                    )
                    .recommendation,
            )
        }
        WakeRecoveryAnchorProcessingResultFactory::class.java.declaredMethods.forEach { method ->
            assertTrue(!method.name.contains("default"), method.toString())
            assertTrue(!method.isBridge, method.toString())
        }
    }

    @Test
    fun wrongDeliveryIdentityAndNonFiredStatesAreStaleWithoutWrites() {
        val malformedIdentity = delivery(pi = "wrong-pi")
        val malformedBefore = allFingerprint()
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
            process(malformedIdentity).outcome,
        )
        assertEquals(malformedBefore, allFingerprint())
        val candidates = listOf(delivery(trigger = 2_001L), delivery(receivedAt = 1_999L))
        candidates.forEach { candidate ->
            val before = allFingerprint()
            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY,
                process(candidate).outcome,
            )
            assertEquals(before, allFingerprint())
        }
        listOf("ARMED", "CANCELLED").forEach { state ->
            database.openHelper.writableDatabase.execSQL(
                "UPDATE wake_recovery_anchor SET state=?",
                arrayOf(state),
            )
            val before = allFingerprint()
            assertEquals(WakeRecoveryAnchorProcessingOutcome.STALE_DELIVERY, process().outcome)
            assertEquals(before, allFingerprint())
        }
    }

    @Test
    fun missingAndMalformedRequiredRowsFailClosedWithoutWrites() {
        val corruptions =
            listOf(
                "UPDATE wake_event_dispatch SET event_kind='START'",
                "UPDATE wake_event_dispatch SET expected_trigger_epoch_ms=2001",
                "UPDATE wake_event_dispatch SET state='BOGUS'",
                "UPDATE wake_event_dispatch SET dispatch_attempt_id=-1",
                "UPDATE wake_event_dispatch SET attempt_count=-1",
                "UPDATE wake_event_dispatch SET armed_primary=2",
                "UPDATE wake_event_dispatch SET last_attempt_at=-1",
                "UPDATE wake_event_dispatch SET recovery_slot_a_state='FIRED',recovery_slot_a_at=NULL",
                "UPDATE wake_event_dispatch SET recovery_slot_b_token=-1",
                "UPDATE wake_run_snapshot SET goal_epoch_ms=2001",
                "UPDATE wake_run_snapshot SET schedule_generation=-1",
                "UPDATE wake_run_snapshot SET zone_id='BOGUS/ZONE'",
                "UPDATE wake_run_snapshot SET occurrence_local_date='not-a-date'",
                "UPDATE wake_run_status SET state='BOGUS'",
                "UPDATE wake_run_status SET execution_epoch=-1",
                "UPDATE migration_state SET schedule_owner='BOGUS' WHERE id=1",
                "UPDATE wake_recovery_anchor SET state='BOGUS'",
                "UPDATE wake_recovery_anchor SET trigger_epoch_ms=-1",
                "UPDATE wake_recovery_anchor SET pending_intent_identity=''",
            )
        corruptions.forEach { sql ->
            resetDispatchAndAnchor()
            insertDispatch()
            insertFiredAnchor()
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
            database.openHelper.writableDatabase.execSQL(sql)
            val before = allFingerprint()
            val result = process()
            assertEquals(
                WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED,
                result.outcome,
                sql,
            )
            assertNull(result.authorization, sql)
            assertEquals(before, allFingerprint(), sql)
        }

        listOf("wake_recovery_anchor", "wake_run_status", "migration_state").forEach { table ->
            resetDispatchAndAnchor()
            insertDispatch()
            insertFiredAnchor()
            database.openHelper.writableDatabase.execSQL("DELETE FROM $table")
            val before = allFingerprint()
            assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome, table)
            assertEquals(before, allFingerprint(), table)
        }

        resetDispatchAndAnchor()
        insertDispatch()
        insertFiredAnchor()
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        val missingDispatch = allFingerprint()
        assertEquals(WakeRecoveryAnchorProcessingOutcome.FAIL_CLOSED, process().outcome)
        assertEquals(missingDispatch, allFingerprint())
    }

    @Test
    fun newClaimMutatesOnlyDispatchPostimageAndSelectedAnchorState() {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,'GOAL_PLUS_1M',62000,'ARMED','sibling-pi')""",
            arrayOf(goal.canonicalKey()),
        )
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO schedule_outbox(id,generation,command,event_key,state,attempt_count,not_before_epoch_ms,created_at,last_error) VALUES ('existing-outbox',1,'RECONCILE',?,'PENDING',2,900,800,'keep')""",
            arrayOf(goal.canonicalKey()),
        )
        val protectedBefore = protectedFingerprint()
        val siblingBefore =
            requireNotNull(
                database
                    .wakeRecoveryAnchorDao()
                    .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PLUS_1M.name)
            )

        assertEquals(WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST, process().outcome)

        assertEquals(protectedBefore, protectedFingerprint())
        assertEquals(
            siblingBefore,
            database
                .wakeRecoveryAnchorDao()
                .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PLUS_1M.name),
        )
        val dispatch = dispatchRow()
        assertEquals(62_000L, dispatch.recoverySlotAAt)
        assertEquals("ARMED", dispatch.recoverySlotAState)
        assertEquals(3L, dispatch.recoverySlotAToken)
        assertEquals(302_000L, dispatch.recoverySlotBAt)
        assertEquals("ARMED", dispatch.recoverySlotBState)
        assertEquals(5L, dispatch.recoverySlotBToken)
    }

    @Test
    fun dispatchRequestCasUsesTheCompleteNullablePreimage() {
        data class Mutation(val label: String, val sql: String)
        val mutations =
            listOf(
                Mutation("state", "UPDATE wake_event_dispatch SET state='DEFERRED'"),
                Mutation("attempt", "UPDATE wake_event_dispatch SET dispatch_attempt_id=8"),
                Mutation("owner", "UPDATE wake_event_dispatch SET lease_owner='stale-owner'"),
                Mutation("expiry", "UPDATE wake_event_dispatch SET lease_expires_at=4100"),
                Mutation("count", "UPDATE wake_event_dispatch SET attempt_count=12"),
                Mutation("lastAttempt", "UPDATE wake_event_dispatch SET last_attempt_at=2099"),
                Mutation("failure", "UPDATE wake_event_dispatch SET failure_reason=NULL"),
                Mutation("armed", "UPDATE wake_event_dispatch SET armed_primary=0"),
                Mutation("slotAAt", "UPDATE wake_event_dispatch SET recovery_slot_a_at=62001"),
                Mutation(
                    "slotAState",
                    "UPDATE wake_event_dispatch SET recovery_slot_a_state='FIRED'",
                ),
                Mutation("slotAToken", "UPDATE wake_event_dispatch SET recovery_slot_a_token=4"),
                Mutation("slotBAt", "UPDATE wake_event_dispatch SET recovery_slot_b_at=302001"),
                Mutation(
                    "slotBState",
                    "UPDATE wake_event_dispatch SET recovery_slot_b_state='FIRED'",
                ),
                Mutation("slotBToken", "UPDATE wake_event_dispatch SET recovery_slot_b_token=6"),
            )
        mutations.forEachIndexed { index, mutation ->
            if (index > 0) {
                resetDispatchAndAnchor()
                insertDispatch()
                insertFiredAnchor()
            }
            val expected = dispatchRow()
            database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints=ON")
            database.openHelper.writableDatabase.execSQL(mutation.sql)
            val stale = dispatchFingerprint()

            val changed =
                database
                    .wakeRecoveryAnchorDao()
                    .compareAndSetDispatchRequest(expected, "new-owner", 5_000L, 2_100L)

            assertEquals(0, changed, mutation.label)
            assertEquals(stale, dispatchFingerprint(), mutation.label)
        }
    }

    @Test
    fun eachOtherProcessingCasRejectsOneStaleFullPreimageField() {
        val dao = database.wakeRecoveryAnchorDao()

        val deferredExpected = dispatchRow()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET failure_reason=NULL"
        )
        val deferredStale = dispatchFingerprint()
        assertEquals(0, dao.compareAndSetReceivedToDeferred(deferredExpected))
        assertEquals(deferredStale, dispatchFingerprint())

        resetDispatchAndAnchor()
        insertDispatch(
            state = "DISPATCH_REQUESTED",
            leaseOwner = "existing-owner",
            leaseExpiresAt = 4_000L,
            armedPrimary = 1,
        )
        insertFiredAnchor()
        val primaryExpected = dispatchRow()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET recovery_slot_b_token=6"
        )
        val primaryStale = dispatchFingerprint()
        assertEquals(0, dao.compareAndSetPrimaryClear(primaryExpected))
        assertEquals(primaryStale, dispatchFingerprint())

        val anchorExpected =
            requireNotNull(
                dao.anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name)
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_recovery_anchor SET pending_intent_identity='changed-pi'"
        )
        val anchorStale = tableFingerprint("wake_recovery_anchor")
        assertEquals(0, dao.compareAndSetFiredToConsumed(anchorExpected))
        assertEquals(anchorStale, tableFingerprint("wake_recovery_anchor"))
    }

    @Test
    fun twoConcurrentProcessorsProduceOneNewAndOneExistingRequest() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val calls =
                (0 until 2).map { index ->
                    executor.submit(
                        Callable {
                            start.await()
                            RoomWakeRecoveryAnchorProcessingStore(database)
                                .processFired(delivery(), sourceFor(delivery()), 500L)
                        }
                    )
                }
            start.countDown()
            val results = calls.map { it.get(10, TimeUnit.SECONDS) }
            val winner = results.single {
                it.outcome == WakeRecoveryAnchorProcessingOutcome.NEW_DISPATCH_REQUEST
            }
            val loser = results.single {
                it.outcome == WakeRecoveryAnchorProcessingOutcome.EXISTING_DURABLE_REQUEST
            }
            val expected =
                WakeDispatchAuthorizationFactory.create(
                    goal,
                    1L,
                    8L,
                    0L,
                    62_100L,
                    sourceFor(delivery()),
                )
            val authorization = requireNotNull(winner.authorization)
            assertEquals(expected.event, authorization.event)
            assertEquals(expected.eventKey, authorization.eventKey)
            assertEquals(expected.scheduleGeneration, authorization.scheduleGeneration)
            assertEquals(expected.dispatchAttemptId, authorization.dispatchAttemptId)
            assertEquals(expected.expectedExecutionEpoch, authorization.expectedExecutionEpoch)
            assertEquals(expected.leaseOwner, authorization.leaseOwner)
            assertEquals(expected.leaseExpiresAt, authorization.leaseExpiresAt)
            assertEquals(expected.requestedAt, authorization.requestedAt)
            assertEquals(expected.source, authorization.source)
            assertNull(loser.authorization)
            assertNull(loser.dispatchRequest)
            assertEquals(1, results.count { it.authorization != null })
            assertEquals(8L, dispatchRow().dispatchAttemptId)
            assertEquals(12L, dispatchRow().attemptCount)
            assertEquals("CONSUMED", anchorState())
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun productionConstructorAndEntrySurfaceStayNarrow() {
        val accessible =
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredConstructors.filterNot {
                Modifier.isPrivate(it.modifiers)
            }
        assertEquals(1, accessible.size)
        assertEquals(listOf(AlarmDatabase::class.java), accessible.single().parameterTypes.toList())
        val entry =
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredMethods.single {
                it.name == "processFired"
            }
        assertEquals(
            listOf(
                WakeRecoveryAnchorDelivery::class.java,
                WakeDispatchSource::class.java,
                Long::class.javaPrimitiveType,
            ),
            entry.parameterTypes.toList(),
        )
        val deadlineEntry =
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredMethods.single {
                it.name == "processDeadline"
            }
        assertEquals(
            listOf(WakeRecoveryAnchorDelivery::class.java),
            deadlineEntry.parameterTypes.toList(),
        )
        assertTrue(
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredConstructors.any {
                Modifier.isPrivate(it.modifiers) &&
                    it.parameterTypes.toList() ==
                        listOf(AlarmDatabase::class.java, Function1::class.java)
            }
        )
        assertTrue(
            RoomWakeRecoveryAnchorProcessingStore::class.java.declaredFields.none {
                (Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers)) &&
                    it.name.contains("CAS")
            }
        )
        assertEquals(
            listOf("processDeadline", "processFired"),
            RoomWakeRecoveryAnchorProcessingStore::class
                .java
                .declaredMethods
                .filterNot { Modifier.isPrivate(it.modifiers) }
                .map { it.name }
                .sorted(),
        )
    }

    private fun delivery(
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PRIMARY,
        trigger: Long = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)),
        pi: String =
            if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) {
                WakePendingIntentData.primary(goal)
            } else {
                WakePendingIntentData.anchor(goal, kind)
            },
        receivedAt: Long = 2_100L,
    ) =
        WakeRecoveryAnchorDelivery(
            event = goal,
            kind = kind,
            triggerEpochMillis = trigger,
            pendingIntentIdentity = pi,
            receivedAtEpochMillis = receivedAt,
        )

    private fun process(
        candidate: WakeRecoveryAnchorDelivery = delivery(),
        maxHeartbeatAgeMillis: Long = 500L,
    ) =
        store.processFired(
            candidate,
            sourceFor(candidate),
            maxHeartbeatAgeMillis,
        )

    private fun sourceFor(candidate: WakeRecoveryAnchorDelivery): WakeDispatchSource {
        val kind =
            when (candidate.kind) {
                WakeRecoveryAnchorKind.GOAL_PRIMARY -> WakeDispatchSourceKind.GOAL_PRIMARY
                WakeRecoveryAnchorKind.GOAL_PLUS_1M -> WakeDispatchSourceKind.GOAL_PLUS_1M
                WakeRecoveryAnchorKind.GOAL_PLUS_5M -> WakeDispatchSourceKind.GOAL_PLUS_5M
                WakeRecoveryAnchorKind.GOAL_PLUS_15M -> WakeDispatchSourceKind.GOAL_PLUS_15M
                WakeRecoveryAnchorKind.GOAL_PLUS_30M -> error("Deadline anchors do not dispatch")
            }
        val canonicalIdentity =
            if (candidate.kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) {
                WakePendingIntentData.primary(candidate.event)
            } else {
                WakePendingIntentData.anchor(candidate.event, candidate.kind)
            }
        return WakeDispatchAuthorizationFactory.canonicalSource(
            candidate.event,
            kind,
            canonicalIdentity,
            candidate.receivedAtEpochMillis,
        )
    }

    private fun snapshot() =
        WakeRunSnapshotEntity(
            id = goal.snapshotId,
            occurrenceId = "occurrence-processing",
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

    private fun insertDispatch(
        state: String = "RECEIVED",
        dispatchAttemptId: Long = 7L,
        leaseOwner: String? = null,
        leaseExpiresAt: Long? = null,
        attemptCount: Long = 11L,
        lastAttemptAt: Long? = null,
        failureReason: String? = "old failure",
        armedPrimary: Int = 1,
        slotAAt: Long? = 62_000L,
        slotAState: String = "ARMED",
        slotAToken: Long = 3L,
        slotBAt: Long? = 302_000L,
        slotBState: String = "ARMED",
        slotBToken: Long = 5L,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_event_dispatch(event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token) VALUES (?,?, 'GOAL',2000,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(
                goal.canonicalKey(),
                goal.snapshotId,
                state,
                dispatchAttemptId,
                leaseOwner,
                leaseExpiresAt,
                attemptCount,
                lastAttemptAt,
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

    private fun insertFiredAnchor(
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PRIMARY,
        trigger: Long = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)),
        pi: String =
            if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) {
                WakePendingIntentData.primary(goal)
            } else {
                WakePendingIntentData.anchor(goal, kind)
            },
    ) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,?,?,'FIRED',?)""",
            arrayOf<Any>(goal.canonicalKey(), kind.name, trigger, pi),
        )
    }

    private fun resetDispatchAndAnchor() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_recovery_anchor")
        database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
        setStatusLease()
    }

    private fun setStatusLease(
        state: String = "PREPARED",
        activeOwner: String? = null,
        executionEpoch: Long = 3L,
        serviceOwner: String? = null,
        serviceExpiry: Long? = null,
        heartbeat: Long? = null,
    ) {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state=?,active_service_owner_token=?,execution_epoch=?,service_lease_owner=?,service_lease_expires_at=?,heartbeat_at=?,armed_start=0,armed_goal=0,completed_at=NULL,cancelled_at=NULL,failure_reason=NULL WHERE snapshot_id=?""",
            arrayOf<Any?>(
                state,
                activeOwner,
                executionEpoch,
                serviceOwner,
                serviceExpiry,
                heartbeat,
                goal.snapshotId,
            ),
        )
    }

    private fun setTerminalStatus(state: String) {
        database.openHelper.writableDatabase.execSQL(
            """UPDATE wake_run_status SET state=?,active_service_owner_token=NULL,service_lease_owner=NULL,service_lease_expires_at=NULL,heartbeat_at=NULL,armed_start=0,armed_goal=0,completed_at=?,cancelled_at=?,failure_reason=? WHERE snapshot_id=?""",
            arrayOf<Any?>(
                state,
                if (state in setOf("COMPLETED", "NO_CONFIRMATION")) 2_100L else null,
                if (state == "CANCELLED") 2_100L else null,
                if (state == "NO_CONFIRMATION") "NO_CONFIRMATION_DEADLINE" else null,
                goal.snapshotId,
            ),
        )
    }

    private fun setOwner(owner: String) {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = ? WHERE id = 1",
            arrayOf(owner),
        )
    }

    private fun anchorState(
        kind: WakeRecoveryAnchorKind = WakeRecoveryAnchorKind.GOAL_PRIMARY
    ): String? = database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name)?.state

    private fun dispatchFingerprint(): List<String?> = tableFingerprint("wake_event_dispatch")

    private fun dispatchRow(): WakeEventDispatchEntity =
        requireNotNull(database.wakeRecoveryAnchorDao().dispatch(goal.canonicalKey()))

    private fun allFingerprint(): List<List<String?>> =
        listOf(
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "wake_run_snapshot",
                "wake_run_status",
                "migration_state",
                "schedule_outbox",
                "track_lease",
            )
            .map(::tableFingerprint)

    private fun protectedFingerprint(): List<List<String?>> =
        listOf(
                "wake_run_snapshot",
                "wake_run_status",
                "migration_state",
                "schedule_outbox",
                "track_lease",
            )
            .map(::tableFingerprint)

    private fun tableFingerprint(table: String): List<String?> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor
            ->
            buildList {
                while (cursor.moveToNext()) {
                    for (column in 0 until cursor.columnCount) {
                        add(if (cursor.isNull(column)) null else cursor.getString(column))
                    }
                }
            }
        }

    private class InjectedProcessingFault(point: String) : RuntimeException(point)
}
