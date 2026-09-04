/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.data.AlarmDatabase
import com.dsalmun.luxalarm.data.RoomWakeEventDispatchStore
import com.dsalmun.luxalarm.data.WakeEventDispatchEntity
import com.dsalmun.luxalarm.data.WakeEventStoreOutcome
import com.dsalmun.luxalarm.data.WakeRunSnapshotEntity
import com.dsalmun.luxalarm.wake.MAX_CANONICAL_URI_ASCII_CHARS
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorization
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSourceKind
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakeReceiverTrustBoundaryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private val event = WakeEventIdentity("receiver-canary", WakeEventKind.START, 1_000L)

    @Test
    fun exactDigestLeaseOwnerReachesDurableFiredAnchorProcessing() {
        val goal = WakeEventIdentity("owner-goal", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val identity = WakePendingIntentData.primary(goal)
        insertAnchor(goal, WakeRecoveryAnchorKind.GOAL_PRIMARY, identity)
        val expectedSource =
            WakeDispatchAuthorizationFactory.canonicalSource(
                goal,
                WakeDispatchSourceKind.GOAL_PRIMARY,
                identity,
                2_000L,
            )
        val expectedOwner =
            WakeDispatchAuthorizationFactory.create(
                    goal,
                    1L,
                    1L,
                    0L,
                    62_000L,
                    expectedSource,
                )
                .leaseOwner

        WakeReceiverRoutingCoordinator(
                database = database,
                clock = { 2_000L },
            )
            .routeGoal(requireNotNull(WakePendingIntentData.parse(identity)))

        val dispatch = requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        assertEquals(expectedOwner, dispatch.leaseOwner)
        assertEquals(62_000L, dispatch.leaseExpiresAt)
        assertEquals("DISPATCH_REQUESTED", dispatch.state)
        assertEquals(
            "CONSUMED",
            database
                .wakeRecoveryAnchorDao()
                .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name)
                ?.state,
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-receiver-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner = 'WAKE', active_generation = 1 WHERE id = 1"
        )
        database.wakeRunStorageDao().createSnapshot(snapshot(), 900L)
    }

    @After
    fun tearDown() {
        WakeReceiverRuntime.resetForTest()
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun actualStartReceiverUsesCanonicalDataAndIgnoresEveryConflictingAuthoritativeExtra() {
        insertDispatch(WakeRecoverySlotId.A, trigger = 1_500L, token = 7L)
        val extrasEvent = WakeEventIdentity("extras-target", WakeEventKind.START, 9_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(extrasEvent), 900L)
        insertDispatch(WakeRecoverySlotId.B, trigger = 9_500L, token = 99L, extrasEvent)
        val extrasBefore = dispatchFingerprint(extrasEvent)
        val canonicalBefore =
            requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        val otherRowsBefore = databaseFingerprintExceptDispatch(event.canonicalKey())
        val canonical = WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_500L)
        val expectedAuthorization =
            expectedAuthorization(event, WakeDispatchSourceKind.START_DYNAMIC_A, canonical, 1_500L)
        installActualReceiverRuntime(now = 1_500L)
        val receiver = WakeStartReceiver()
        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(canonical)).apply {
                putExtra("snapshot_id", extrasEvent.snapshotId)
                putExtra("event_kind", WakeEventKind.GOAL.name)
                putExtra("slot", WakeRecoverySlotId.B.name)
                putExtra("token", 99L)
                putExtra("trigger", 9_500L)
                putExtra(
                    "pending_intent_identity",
                    WakePendingIntentData.dynamic(
                        extrasEvent,
                        WakeRecoverySlotId.B,
                        99L,
                        9_500L,
                    ),
                )
            },
        )

        assertPendingResultFinished(receiver)
        val canonicalAfter =
            requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        assertEquals(
            canonicalBefore.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 1L,
                attemptCount = 1L,
                lastAttemptAt = 1_500L,
                failureReason = null,
                leaseOwner = expectedAuthorization.leaseOwner,
                leaseExpiresAt = expectedAuthorization.leaseExpiresAt,
                recoverySlotAAt = null,
                recoverySlotAState = "CONSUMED",
                recoverySlotAToken = 8L,
            ),
            canonicalAfter,
        )
        assertEquals(extrasBefore, dispatchFingerprint(extrasEvent))
        assertEquals(otherRowsBefore, databaseFingerprintExceptDispatch(event.canonicalKey()))
    }

    @Test
    fun actualStartReceiverSurvivesAbsentOrMalformedDataWithoutAnyWrite() {
        insertDispatch(WakeRecoverySlotId.A, trigger = 1_500L, token = 7L)
        installActualReceiverRuntime(now = 1_500L)
        val before = databaseFingerprint()

        listOf<Uri?>(null, Uri.parse("gentlewake://wake-event/v1/not-canonical")).forEach { data ->
            val receiver = WakeStartReceiver()
            sendActualReceiver(receiver, Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(data))
            assertEquals(false, shadowOf(receiver).wentAsync())
            assertEquals(before, databaseFingerprint(), data?.toString() ?: "absent")
        }
    }

    @Test
    fun actualStartReceiverRejectsOversizedDataBeforeGoAsyncWithZeroWrites() {
        insertDispatch(WakeRecoverySlotId.A, trigger = 1_500L, token = 7L)
        installActualReceiverRuntime(now = 1_500L)
        val before = databaseFingerprint()
        val receiver = WakeStartReceiver()

        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION)
                .setData(Uri.parse("x".repeat(MAX_CANONICAL_URI_ASCII_CHARS + 1))),
        )

        assertEquals(false, shadowOf(receiver).wentAsync())
        assertEquals(before, databaseFingerprint())
    }

    @Test
    fun actualStartReceiverFinishesPendingResultWhenCoordinatorFails() {
        WakeReceiverRuntime.installForTest(
            executor = Executor { it.run() },
            coordinatorFactory = { error("injected Room/coordinator failure") },
        )
        val receiver = WakeStartReceiver()
        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION)
                .setData(Uri.parse(WakePendingIntentData.primary(event))),
        )

        assertPendingResultFinished(receiver)
    }

    @Test
    fun queuedStartReceiverUsesSingleCapturedRuntimeAfterInstallAndReset() {
        insertSimpleDispatch(event)
        val queued = QueuedExecutor()
        WakeReceiverRuntime.installForTest(
            executor = queued,
            coordinatorFactory = {
                WakeReceiverRoutingCoordinator(database, { 1_000L })
            },
        )
        val receiver = WakeStartReceiver()
        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION)
                .setData(Uri.parse(WakePendingIntentData.primary(event))),
        )
        WakeReceiverRuntime.installForTest(
            executor = Executor { error("config B executor must not run") },
            coordinatorFactory = { error("config B coordinator must not be read") },
        )
        WakeReceiverRuntime.resetForTest()

        assertEquals(1, queued.size)
        queued.runNext()

        assertPendingResultFinished(receiver)
        val row = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", row.state)
        assertEquals(1L, row.dispatchAttemptId)
        assertEquals(1L, row.attemptCount)
        assertEquals(1_000L, row.lastAttemptAt)
    }

    @Test
    fun queuedGoalReceiverUsesSingleCapturedRuntimeAfterInstallAndReset() {
        val goal = WakeEventIdentity("queued-goal", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val identity = WakePendingIntentData.primary(goal)
        insertAnchor(goal, WakeRecoveryAnchorKind.GOAL_PRIMARY, identity)
        val expectedAuthorization =
            expectedAuthorization(goal, WakeDispatchSourceKind.GOAL_PRIMARY, identity, 2_000L)
        val queued = QueuedExecutor()
        WakeReceiverRuntime.installForTest(
            executor = queued,
            coordinatorFactory = {
                WakeReceiverRoutingCoordinator(database, { 2_000L })
            },
        )
        val receiver = WakeGoalReceiver()
        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(identity)),
        )
        WakeReceiverRuntime.installForTest(
            executor = Executor { error("config B executor must not run") },
            coordinatorFactory = { error("config B coordinator must not be read") },
        )
        WakeReceiverRuntime.resetForTest()

        assertEquals(1, queued.size)
        queued.runNext()

        assertPendingResultFinished(receiver)
        val row = requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", row.state)
        assertEquals(expectedAuthorization.leaseOwner, row.leaseOwner)
        assertEquals(expectedAuthorization.leaseExpiresAt, row.leaseExpiresAt)
    }

    @Test
    fun concurrentRuntimeInstallAndResetNeverPublishesAHybridPair() {
        val executorA = Executor { it.run() }
        val executorB = Executor { it.run() }
        val factoryA: (Context) -> WakeReceiverRoutingCoordinator = {
            WakeReceiverRoutingCoordinator(database, { 1L })
        }
        val factoryB: (Context) -> WakeReceiverRoutingCoordinator = {
            WakeReceiverRoutingCoordinator(database, { 2L })
        }
        WakeReceiverRuntime.resetForTest()
        val defaultConfig = WakeReceiverRuntime.capture()
        WakeReceiverRuntime.installForTest(executorA, factoryA)
        val configA = WakeReceiverRuntime.capture()
        WakeReceiverRuntime.installForTest(executorB, factoryB)
        val configB = WakeReceiverRuntime.capture()
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            start.await()
            repeat(10_000) { index ->
                when (index % 3) {
                    0 -> WakeReceiverRuntime.installForTest(executorA, factoryA)
                    1 -> WakeReceiverRuntime.installForTest(executorB, factoryB)
                    else -> WakeReceiverRuntime.resetForTest()
                }
            }
        }
        val reader = Thread {
            start.await()
            repeat(10_000) {
                val observed = WakeReceiverRuntime.capture()
                if (observed != configA && observed != configB && observed != defaultConfig) {
                    failure.compareAndSet(null, AssertionError("hybrid runtime: $observed"))
                }
            }
        }

        writer.start()
        reader.start()
        start.countDown()
        writer.join()
        reader.join()

        failure.get()?.let { throw it }
    }

    @Test
    fun actualGoalReceiverAppliesPrimaryWithExactPostimagesAndFinishes() {
        val goal = WakeEventIdentity("actual-goal-primary", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val identity = WakePendingIntentData.primary(goal)
        insertAnchor(goal, WakeRecoveryAnchorKind.GOAL_PRIMARY, identity)
        val expectedAuthorization =
            expectedAuthorization(goal, WakeDispatchSourceKind.GOAL_PRIMARY, identity, 2_000L)
        val dispatchBefore =
            requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        val anchorBefore =
            requireNotNull(
                database
                    .wakeRecoveryAnchorDao()
                    .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name)
            )
        installActualReceiverRuntime(now = 2_000L)
        val receiver = WakeGoalReceiver()

        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(identity)),
        )

        assertPendingResultFinished(receiver)
        assertEquals(
            dispatchBefore.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 1L,
                leaseOwner = expectedAuthorization.leaseOwner,
                leaseExpiresAt = expectedAuthorization.leaseExpiresAt,
                attemptCount = 1L,
                lastAttemptAt = 2_000L,
                failureReason = null,
                armedPrimary = 0,
            ),
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey()),
        )
        assertEquals(
            anchorBefore.copy(state = "CONSUMED"),
            database
                .wakeRecoveryAnchorDao()
                .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name),
        )
    }

    @Test
    fun actualGoalReceiverAppliesExactDynamicPostimageAndFinishes() {
        val goal = WakeEventIdentity("actual-goal-dynamic", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertDispatch(WakeRecoverySlotId.B, 2_500L, 9L, goal)
        val before = requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        val statusBefore = requireNotNull(database.wakeEventDispatchDao().status(goal.snapshotId))
        val identity = WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.B, 9L, 2_500L)
        val expectedAuthorization =
            expectedAuthorization(goal, WakeDispatchSourceKind.GOAL_DYNAMIC_B, identity, 2_500L)
        installActualReceiverRuntime(now = 2_500L)
        val receiver = WakeGoalReceiver()

        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(identity)),
        )

        assertPendingResultFinished(receiver)
        assertEquals(
            before.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 1L,
                attemptCount = 1L,
                lastAttemptAt = 2_500L,
                failureReason = null,
                leaseOwner = expectedAuthorization.leaseOwner,
                leaseExpiresAt = expectedAuthorization.leaseExpiresAt,
                recoverySlotBAt = null,
                recoverySlotBState = "CONSUMED",
                recoverySlotBToken = 10L,
            ),
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey()),
        )
        assertEquals(statusBefore, database.wakeEventDispatchDao().status(goal.snapshotId))
    }

    @Test
    fun actualGoalReceiverResumesFiredPlusOneAnchorWithExactPostimagesAndFinishes() {
        val goal = WakeEventIdentity("actual-goal-plus-one", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val kind = WakeRecoveryAnchorKind.GOAL_PLUS_1M
        val identity = WakePendingIntentData.anchor(goal, kind)
        insertAnchor(goal, kind, identity, state = "FIRED")
        val expectedAuthorization =
            expectedAuthorization(goal, WakeDispatchSourceKind.GOAL_PLUS_1M, identity, 62_000L)
        val dispatchBefore =
            requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        val anchorBefore =
            requireNotNull(database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name))
        val statusBefore = requireNotNull(database.wakeEventDispatchDao().status(goal.snapshotId))
        installActualReceiverRuntime(now = 62_000L)
        val receiver = WakeGoalReceiver()

        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(identity)),
        )

        assertPendingResultFinished(receiver)
        assertEquals(
            dispatchBefore.copy(
                state = "DISPATCH_REQUESTED",
                dispatchAttemptId = 1L,
                leaseOwner = expectedAuthorization.leaseOwner,
                leaseExpiresAt = expectedAuthorization.leaseExpiresAt,
                attemptCount = 1L,
                lastAttemptAt = 62_000L,
                failureReason = null,
            ),
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey()),
        )
        assertEquals(
            anchorBefore.copy(state = "CONSUMED"),
            database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name),
        )
        assertEquals(statusBefore, database.wakeEventDispatchDao().status(goal.snapshotId))
    }

    @Test
    fun actualGoalReceiverTerminalizesDeadlineWithExactDurablePostimagesAndFinishes() {
        val goal = WakeEventIdentity("actual-goal-deadline", WakeEventKind.GOAL, 2_000L)
        val start = WakeEventIdentity(goal.snapshotId, WakeEventKind.START, 1_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertSimpleDispatch(start)
        insertGoalDispatch(goal)
        WakeRecoveryAnchorKind.entries.forEach { kind ->
            val identity =
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) WakePendingIntentData.primary(goal)
                else WakePendingIntentData.anchor(goal, kind)
            insertAnchor(goal, kind, identity)
        }
        val deadlineKind = WakeRecoveryAnchorKind.GOAL_PLUS_30M
        val deadline = 1_802_000L
        val identity = WakePendingIntentData.anchor(goal, deadlineKind)
        val startBefore =
            requireNotNull(database.wakeEventDispatchDao().dispatch(start.canonicalKey()))
        val goalBefore =
            requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        val statusBefore = requireNotNull(database.wakeEventDispatchDao().status(goal.snapshotId))
        val anchorsBefore = database.wakeRecoveryAnchorDao().anchors(goal.canonicalKey())
        installActualReceiverRuntime(now = deadline)
        val receiver = WakeGoalReceiver()

        sendActualReceiver(
            receiver,
            Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(Uri.parse(identity)),
        )

        assertPendingResultFinished(receiver)
        assertEquals(
            statusBefore.copy(
                state = "NO_CONFIRMATION",
                activeServiceOwnerToken = null,
                executionEpoch = statusBefore.executionEpoch + 1L,
                serviceLeaseOwner = null,
                serviceLeaseExpiresAt = null,
                heartbeatAt = null,
                armedStart = 0,
                armedGoal = 0,
                completedAt = deadline,
                cancelledAt = null,
                failureReason = "NO_CONFIRMATION_DEADLINE",
            ),
            database.wakeEventDispatchDao().status(goal.snapshotId),
        )
        assertEquals(
            terminalDispatchPostimage(startBefore),
            database.wakeEventDispatchDao().dispatch(start.canonicalKey()),
        )
        assertEquals(
            terminalDispatchPostimage(goalBefore),
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey()),
        )
        assertEquals(
            anchorsBefore.map { anchor ->
                anchor.copy(
                    state = if (anchor.anchorKind == deadlineKind.name) "CONSUMED" else "CANCELLED"
                )
            },
            database.wakeRecoveryAnchorDao().anchors(goal.canonicalKey()),
        )
    }

    @Test
    fun actualGoalReceiverRejectsStartMalformedAndAbsentDataWithoutGoAsyncOrWrites() {
        insertSimpleDispatch(event)
        installActualReceiverRuntime(now = 1_000L)
        val before = databaseFingerprint()
        val candidates =
            listOf(
                Uri.parse(WakePendingIntentData.primary(event)),
                Uri.parse("gentlewake://wake-event/v1/not-canonical"),
                null,
            )

        candidates.forEach { data ->
            val receiver = WakeGoalReceiver()
            sendActualReceiver(receiver, Intent(ACTUAL_RECEIVER_TEST_ACTION).setData(data))
            assertEquals(false, shadowOf(receiver).wentAsync(), data?.toString() ?: "absent")
            assertEquals(before, databaseFingerprint(), data?.toString() ?: "absent")
        }
    }

    @Test
    fun parserFactoryAndRealRoomStoreConsumeExactArmedDynamicSlotsAtomically() {
        listOf(WakeRecoverySlotId.A, WakeRecoverySlotId.B).forEachIndexed { index, slot ->
            if (index > 0)
                database.openHelper.writableDatabase.execSQL("DELETE FROM wake_event_dispatch")
            insertDispatch(slot, trigger = 1_500L, token = 7L)
            val uri = WakePendingIntentData.dynamic(event, slot, 7L, 1_500L)

            val parsed = requireNotNull(WakePendingIntentData.parse(uri))
            WakeReceiverRoutingCoordinator(
                    database = database,
                    clock = { 1_500L },
                )
                .routeStart(parsed)

            val row = requireNotNull(database.wakeEventDispatchDao().dispatch(event.canonicalKey()))
            assertEquals("DISPATCH_REQUESTED", row.state)
            assertEquals(1L, row.dispatchAttemptId)
            assertEquals(1L, row.attemptCount)
            if (slot == WakeRecoverySlotId.A) {
                assertEquals("CONSUMED", row.recoverySlotAState)
                assertEquals(null, row.recoverySlotAAt)
                assertEquals(8L, row.recoverySlotAToken)
            } else {
                assertEquals("CONSUMED", row.recoverySlotBState)
                assertEquals(null, row.recoverySlotBAt)
                assertEquals(8L, row.recoverySlotBToken)
            }
        }
    }

    @Test
    fun dynamicTriggerOrTokenSpoofMakesNoRoomWrite() {
        insertDispatch(WakeRecoverySlotId.A, trigger = 1_500L, token = 7L)
        val before = dispatchFingerprint()
        listOf(
                WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 6L, 1_500L),
                WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_501L),
            )
            .forEach { uri ->
                val result =
                    requireNotNull(WakePendingIntentData.parse(uri))
                        .match(
                            onPrimary = { _, _ -> error("not primary") },
                            onDynamic = { parsedEvent, arrival ->
                                val source =
                                    WakeDispatchAuthorizationFactory.canonicalSource(
                                        parsedEvent,
                                        WakeDispatchSourceKind.START_DYNAMIC_A,
                                        uri,
                                        1_500L,
                                    )
                                RoomWakeEventDispatchStore(database)
                                    .reduce(parsedEvent, source, 1_500L, 500L)
                            },
                            onAnchor = { error("not anchor") },
                        )
                assert(result.outcome != WakeEventStoreOutcome.APPLIED)
                assertEquals(before, dispatchFingerprint())
            }
    }

    @Test
    fun startPrimaryAndGoalDynamicRouteToRealDispatchStore() {
        insertSimpleDispatch(event)
        val startCoordinator = coordinatorAt(1_000L)
        startCoordinator.routeStart(
            requireNotNull(WakePendingIntentData.parse(WakePendingIntentData.primary(event)))
        )
        assertEquals(
            "DISPATCH_REQUESTED",
            database.wakeEventDispatchDao().dispatch(event.canonicalKey())?.state,
        )

        val goal = WakeEventIdentity("goal-dynamic", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertDispatch(WakeRecoverySlotId.B, 2_500L, 9L, goal)
        coordinatorAt(2_500L)
            .routeGoal(
                requireNotNull(
                    WakePendingIntentData.parse(
                        WakePendingIntentData.dynamic(goal, WakeRecoverySlotId.B, 9L, 2_500L)
                    )
                )
            )
        val goalRow = requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", goalRow.state)
        assertEquals("CONSUMED", goalRow.recoverySlotBState)
        assertEquals(10L, goalRow.recoverySlotBToken)
    }

    @Test
    fun crossKindAndExtrasOrDataSpoofsLeaveExactDatabaseFingerprintUnchanged() {
        insertDispatch(WakeRecoverySlotId.A, trigger = 1_500L, token = 7L)
        val goal = WakeEventIdentity("spoof-goal", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val before = databaseFingerprint()
        val validStart = WakePendingIntentData.dynamic(event, WakeRecoverySlotId.A, 7L, 1_500L)
        val validGoal = WakePendingIntentData.primary(goal)
        val candidates =
            listOf(
                Intent().setData(Uri.parse(validStart.replace("/7/1500", "/6/1500"))).apply {
                    putExtra("event_key", event.canonicalKey())
                    putExtra("token", 7L)
                    putExtra("trigger", 1_500L)
                    putExtra("data", validStart)
                },
                Intent().setData(Uri.parse(validStart + "?data=$validStart")),
                Intent().setData(Uri.parse(validStart + "/extra")),
            )
        candidates.forEach { intent ->
            intent.dataString
                ?.let(WakePendingIntentData::parse)
                ?.let(coordinatorAt(1_500L)::routeStart)
            assertEquals(before, databaseFingerprint(), intent.dataString)
        }
        coordinatorAt(2_000L).routeStart(requireNotNull(WakePendingIntentData.parse(validGoal)))
        coordinatorAt(1_500L).routeGoal(requireNotNull(WakePendingIntentData.parse(validStart)))
        assertEquals(before, databaseFingerprint(), "receiver/event kind cross-route")
    }

    @Test
    fun duplicateOrCancelledAnchorReceiptsNeverContinueToProcessing() {
        listOf("CONSUMED", "CANCELLED").forEachIndexed { index, anchorState ->
            val goal = WakeEventIdentity("goal-noop-$index", WakeEventKind.GOAL, 2_000L)
            database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
            insertGoalDispatch(goal)
            val identity = WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
            insertAnchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M, identity, anchorState)
            val before = databaseFingerprint()

            coordinatorAt(62_000L).routeGoal(requireNotNull(WakePendingIntentData.parse(identity)))

            assertEquals(before, databaseFingerprint(), anchorState)
        }
    }

    @Test
    fun firedAnchorReceiptResumesProcessing() {
        val goal = WakeEventIdentity("goal-resume", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val kind = WakeRecoveryAnchorKind.GOAL_PLUS_5M
        val identity = WakePendingIntentData.anchor(goal, kind)
        insertAnchor(goal, kind, identity, state = "FIRED")

        coordinatorAt(302_000L).routeGoal(requireNotNull(WakePendingIntentData.parse(identity)))

        assertEquals(
            "CONSUMED",
            database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name)?.state,
        )
        assertEquals(
            "DISPATCH_REQUESTED",
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey())?.state,
        )
    }

    @Test
    fun asyncCompletionGateFinishesExactlyOnceAfterNormalExecution() {
        val finishCount = AtomicInteger()
        val workCount = AtomicInteger()

        executeReceiverWork(
            executor = Executor { it.run() },
            work = { workCount.incrementAndGet() },
            finish = { finishCount.incrementAndGet() },
        )

        assertEquals(1, workCount.get())
        assertEquals(1, finishCount.get())
    }

    @Test
    fun asyncCompletionGateFinishesExactlyOnceWhenSubmissionRejectsRuntimeException() {
        val finishCount = AtomicInteger()
        val workCount = AtomicInteger()

        executeReceiverWork(
            executor = Executor { throw IllegalStateException("executor-rejected") },
            work = { workCount.incrementAndGet() },
            finish = { finishCount.incrementAndGet() },
        )

        assertEquals(0, workCount.get())
        assertEquals(1, finishCount.get())
    }

    @Test
    fun asyncCompletionGateFinishesExactlyOnceWhenSubmissionRejectsError() {
        val finishCount = AtomicInteger()
        val workCount = AtomicInteger()

        executeReceiverWork(
            executor = Executor { throw AssertionError("executor-rejected") },
            work = { workCount.incrementAndGet() },
            finish = { finishCount.incrementAndGet() },
        )

        assertEquals(0, workCount.get())
        assertEquals(1, finishCount.get())
    }

    @Test
    fun asyncCompletionGateFinishesExactlyOnceWhenSynchronousExecutorRunsThenThrows() {
        val finishCount = AtomicInteger()
        val workCount = AtomicInteger()

        executeReceiverWork(
            executor =
                Executor { command ->
                    command.run()
                    throw IllegalStateException("executor-threw-after-run")
                },
            work = { workCount.incrementAndGet() },
            finish = { finishCount.incrementAndGet() },
        )

        assertEquals(1, workCount.get())
        assertEquals(1, finishCount.get())
    }

    @Test
    fun asyncCompletionGateContainsWorkAndFinishFailures() {
        val finishCount = AtomicInteger()
        executeReceiverWork(
            executor = Executor { it.run() },
            work = { error("receiver-work-failure") },
            finish = {
                finishCount.incrementAndGet()
                error("finish-failure-is-contained")
            },
        )
        assertEquals(1, finishCount.get())
    }

    @Test
    fun goalPrimaryUsesOnlyAnchorReceiptAndProcessingPath() {
        val goal = WakeEventIdentity("goal-receiver", WakeEventKind.GOAL, 2_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertGoalDispatch(goal)
        val identity = WakePendingIntentData.primary(goal)
        insertAnchor(goal, WakeRecoveryAnchorKind.GOAL_PRIMARY, identity)

        val route =
            WakeReceiverRoutingCoordinator(
                    database = database,
                    clock = { 2_000L },
                )
                .routeGoal(requireNotNull(WakePendingIntentData.parse(identity)))
        val expectedAuthorization =
            expectedAuthorization(goal, WakeDispatchSourceKind.GOAL_PRIMARY, identity, 2_000L)

        val dispatch = requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
        assertEquals("DISPATCH_REQUESTED", dispatch.state)
        assertEquals(0, dispatch.armedPrimary)
        assertEquals(1L, dispatch.dispatchAttemptId)
        assertEquals(1L, dispatch.attemptCount)
        assertAuthorizationEquals(expectedAuthorization, requireNotNull(route.authorization))
        assertEquals(expectedAuthorization.leaseOwner, dispatch.leaseOwner)
        assertEquals(expectedAuthorization.leaseExpiresAt, dispatch.leaseExpiresAt)
        assertEquals(
            "CONSUMED",
            database
                .wakeRecoveryAnchorDao()
                .anchor(goal.canonicalKey(), WakeRecoveryAnchorKind.GOAL_PRIMARY.name)
                ?.state,
        )
    }

    @Test
    fun predeadlineImmutableAnchorsRequestGoalWorkAndConsumeOnlyTheirAnchor() {
        listOf(
                WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                WakeRecoveryAnchorKind.GOAL_PLUS_15M,
            )
            .forEachIndexed { index, kind ->
                val goal = WakeEventIdentity("goal-anchor-$index", WakeEventKind.GOAL, 2_000L)
                database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
                insertGoalDispatch(goal)
                val identity = WakePendingIntentData.anchor(goal, kind)
                insertAnchor(goal, kind, identity)
                val trigger =
                    requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))

                val route =
                    WakeReceiverRoutingCoordinator(
                            database = database,
                            clock = { trigger },
                        )
                        .routeGoal(requireNotNull(WakePendingIntentData.parse(identity)))
                val sourceKind =
                    when (kind) {
                        WakeRecoveryAnchorKind.GOAL_PLUS_1M -> WakeDispatchSourceKind.GOAL_PLUS_1M
                        WakeRecoveryAnchorKind.GOAL_PLUS_5M -> WakeDispatchSourceKind.GOAL_PLUS_5M
                        WakeRecoveryAnchorKind.GOAL_PLUS_15M -> WakeDispatchSourceKind.GOAL_PLUS_15M
                        else -> error("not a predeadline kind: $kind")
                    }
                val expectedAuthorization =
                    expectedAuthorization(goal, sourceKind, identity, trigger)

                val dispatch =
                    requireNotNull(database.wakeEventDispatchDao().dispatch(goal.canonicalKey()))
                assertEquals("DISPATCH_REQUESTED", dispatch.state, kind.name)
                assertAuthorizationEquals(
                    expectedAuthorization,
                    requireNotNull(route.authorization),
                    kind.name,
                )
                assertEquals(expectedAuthorization.leaseOwner, dispatch.leaseOwner, kind.name)
                assertEquals(
                    expectedAuthorization.leaseExpiresAt,
                    dispatch.leaseExpiresAt,
                    kind.name,
                )
                assertEquals(1, dispatch.armedPrimary, kind.name)
                assertEquals(
                    "CONSUMED",
                    database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), kind.name)?.state,
                    kind.name,
                )
            }
    }

    @Test
    fun deadlineAnchorTerminalizesRealRoomPostimages() {
        val goal = WakeEventIdentity("goal-deadline", WakeEventKind.GOAL, 2_000L)
        val start = WakeEventIdentity(goal.snapshotId, WakeEventKind.START, 1_000L)
        database.wakeRunStorageDao().createSnapshot(snapshot(goal), 900L)
        insertSimpleDispatch(start)
        insertGoalDispatch(goal)
        WakeRecoveryAnchorKind.entries.forEach { kind ->
            val identity =
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) WakePendingIntentData.primary(goal)
                else WakePendingIntentData.anchor(goal, kind)
            insertAnchor(goal, kind, identity)
        }
        val deadlineKind = WakeRecoveryAnchorKind.GOAL_PLUS_30M
        val deadlineIdentity = WakePendingIntentData.anchor(goal, deadlineKind)
        val deadline =
            requireNotNull(deadlineKind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))

        val route =
            WakeReceiverRoutingCoordinator(
                    database = database,
                    clock = { deadline },
                )
                .routeGoal(requireNotNull(WakePendingIntentData.parse(deadlineIdentity)))
        assertEquals(null, route.authorization)

        val status = requireNotNull(database.wakeEventDispatchDao().status(goal.snapshotId))
        assertEquals("NO_CONFIRMATION", status.state)
        assertEquals(0, status.armedStart)
        assertEquals(0, status.armedGoal)
        assertEquals(
            "TERMINAL",
            database.wakeEventDispatchDao().dispatch(start.canonicalKey())?.state,
        )
        assertEquals(
            "TERMINAL",
            database.wakeEventDispatchDao().dispatch(goal.canonicalKey())?.state,
        )
        assertEquals(
            "CONSUMED",
            database.wakeRecoveryAnchorDao().anchor(goal.canonicalKey(), deadlineKind.name)?.state,
        )
    }

    private fun insertSimpleDispatch(event: WakeEventIdentity) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_event_dispatch(
              event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,
              dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,
              failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,
              recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token
            ) VALUES (?,?,?,?, 'RECEIVED',0,NULL,NULL,0,NULL,NULL,1,NULL,'CONSUMED',0,NULL,'CONSUMED',0)
            """
                .trimIndent(),
            arrayOf<Any>(
                event.canonicalKey(),
                event.snapshotId,
                event.kind.name,
                event.expectedTriggerEpochMillis,
            ),
        )
    }

    private fun terminalDispatchPostimage(row: WakeEventDispatchEntity) =
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

    private fun insertGoalDispatch(goal: WakeEventIdentity) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_event_dispatch(
              event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,
              dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,
              failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,
              recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token
            ) VALUES (?,?, 'GOAL',?, 'RECEIVED',0,NULL,NULL,0,NULL,NULL,1,NULL,'CONSUMED',0,NULL,'CONSUMED',0)
            """
                .trimIndent(),
            arrayOf<Any>(goal.canonicalKey(), goal.snapshotId, goal.expectedTriggerEpochMillis),
        )
    }

    private fun insertAnchor(
        goal: WakeEventIdentity,
        kind: WakeRecoveryAnchorKind,
        identity: String,
        state: String = "ARMED",
    ) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO wake_recovery_anchor(event_key,anchor_kind,trigger_epoch_ms,state,pending_intent_identity) VALUES (?,?,?,?,?)",
            arrayOf<Any>(
                goal.canonicalKey(),
                kind.name,
                requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis)),
                state,
                identity,
            ),
        )
    }

    private fun insertDispatch(
        slot: WakeRecoverySlotId,
        trigger: Long,
        token: Long,
        targetEvent: WakeEventIdentity = event,
    ) {
        val a = slot == WakeRecoverySlotId.A
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_event_dispatch(
              event_key,snapshot_id,event_kind,expected_trigger_epoch_ms,state,
              dispatch_attempt_id,lease_owner,lease_expires_at,attempt_count,last_attempt_at,
              failure_reason,armed_primary,recovery_slot_a_at,recovery_slot_a_state,
              recovery_slot_a_token,recovery_slot_b_at,recovery_slot_b_state,recovery_slot_b_token
            ) VALUES (?,?,?,?, 'RECEIVED',0,NULL,NULL,0,NULL,NULL,1,?,?,?,?,?,?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                targetEvent.canonicalKey(),
                targetEvent.snapshotId,
                targetEvent.kind.name,
                targetEvent.expectedTriggerEpochMillis,
                if (a) trigger else null,
                if (a) "ARMED" else "CONSUMED",
                if (a) token else 0L,
                if (a) null else trigger,
                if (a) "CONSUMED" else "ARMED",
                if (a) 0L else token,
            ),
        )
    }

    private fun coordinatorAt(now: Long) =
        WakeReceiverRoutingCoordinator(
            database = database,
            clock = { now },
        )

    private fun databaseFingerprint(): List<String> =
        listOf(
                "migration_state",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "schedule_outbox",
            )
            .flatMap { table ->
                database.openHelper.readableDatabase
                    .query("SELECT * FROM $table ORDER BY rowid")
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    "$table:" +
                                        (0 until cursor.columnCount).joinToString("|") { column ->
                                            if (cursor.isNull(column)) "<null>"
                                            else cursor.getString(column)
                                        }
                                )
                            }
                        }
                    }
            }

    private fun dispatchFingerprint(target: WakeEventIdentity = event): List<String> =
        database.openHelper.readableDatabase
            .query(
                "SELECT * FROM wake_event_dispatch WHERE event_key = ?",
                arrayOf(target.canonicalKey()),
            )
            .use { cursor ->
                check(cursor.moveToFirst())
                (0 until cursor.columnCount).map {
                    if (cursor.isNull(it)) "<null>" else cursor.getString(it)
                }
            }

    private fun databaseFingerprintExceptDispatch(eventKey: String): List<String> =
        listOf(
                "alarms",
                "imported_track",
                "wake_routine",
                "migration_state",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "schedule_outbox",
                "track_lease",
                "schedule_occurrence_claim",
                "legacy_migration_manifest",
                "legacy_coordinator_state",
                "legacy_coordinator_member",
            )
            .flatMap { table ->
                val sql =
                    if (table == "wake_event_dispatch") {
                        "SELECT * FROM wake_event_dispatch WHERE event_key <> ? ORDER BY rowid"
                    } else {
                        "SELECT * FROM $table ORDER BY rowid"
                    }
                val args = if (table == "wake_event_dispatch") arrayOf(eventKey) else emptyArray()
                database.openHelper.readableDatabase.query(sql, args).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                "$table:" +
                                    (0 until cursor.columnCount).joinToString("|") { column ->
                                        if (cursor.isNull(column)) "<null>"
                                        else cursor.getString(column)
                                    }
                            )
                        }
                    }
                }
            }

    private fun installActualReceiverRuntime(now: Long) {
        WakeReceiverRuntime.installForTest(
            executor = Executor { it.run() },
            coordinatorFactory = {
                WakeReceiverRoutingCoordinator(
                    database = database,
                    clock = { now },
                )
            },
        )
    }

    private fun expectedAuthorization(
        targetEvent: WakeEventIdentity,
        sourceKind: WakeDispatchSourceKind,
        identity: String,
        receivedAt: Long,
        attempt: Long = 1L,
    ): WakeDispatchAuthorization {
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                targetEvent,
                sourceKind,
                identity,
                receivedAt,
            )
        return WakeDispatchAuthorizationFactory.create(
            targetEvent,
            1L,
            attempt,
            0L,
            receivedAt + 60_000L,
            source,
        )
    }

    private fun assertAuthorizationEquals(
        expected: WakeDispatchAuthorization,
        actual: WakeDispatchAuthorization,
        message: String? = null,
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

    private fun sendActualReceiver(receiver: android.content.BroadcastReceiver, intent: Intent) {
        context.sendOrderedBroadcast(intent, null, receiver, null, 0, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPendingResultFinished(receiver: android.content.BroadcastReceiver) {
        val receiverShadow = shadowOf(receiver)
        assertTrue(receiverShadow.wentAsync(), "receiver must call goAsync")
        val pending = requireNotNull(receiverShadow.originalPendingResult)
        assertTrue(shadowOf(pending).future.isDone, "goAsync PendingResult must finish")
    }

    private class QueuedExecutor : Executor {
        private val commands = ConcurrentLinkedQueue<Runnable>()
        val size: Int
            get() = commands.size

        override fun execute(command: Runnable) {
            commands.add(command)
        }

        fun runNext() = requireNotNull(commands.poll()).run()
    }

    private companion object {
        const val ACTUAL_RECEIVER_TEST_ACTION = "com.dsalmun.luxalarm.TEST_WAKE_RECEIVER"
    }

    private fun snapshot(forEvent: WakeEventIdentity = event) =
        WakeRunSnapshotEntity(
            id = forEvent.snapshotId,
            occurrenceId = "occurrence-${forEvent.snapshotId}",
            scheduleGeneration = 1L,
            routineRevision = 1L,
            calculationRuleVersion = 1L,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-04",
            wakeStartEpochMs =
                if (forEvent.kind == WakeEventKind.START) forEvent.expectedTriggerEpochMillis
                else 1_000L,
            goalEpochMs =
                if (forEvent.kind == WakeEventKind.GOAL) forEvent.expectedTriggerEpochMillis
                else forEvent.expectedTriggerEpochMillis + 1_000L,
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
