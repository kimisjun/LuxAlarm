/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.data.AlarmDatabase
import com.dsalmun.luxalarm.data.RoomWakePrimaryScheduleStore
import com.dsalmun.luxalarm.data.RoomWakeRecoveryAnchorProcessingStore
import com.dsalmun.luxalarm.data.RoomWakeRecoveryAnchorReceiptStore
import com.dsalmun.luxalarm.data.RoomWakeSchedulePreparationStore
import com.dsalmun.luxalarm.data.WakeRecoveryAnchorProcessingOutcome
import com.dsalmun.luxalarm.data.WakeRecoveryAnchorReceiptStoreOutcome
import com.dsalmun.luxalarm.data.WakeRunSnapshotEntity
import com.dsalmun.luxalarm.data.primaryScheduleStoreWithFaultHook
import com.dsalmun.luxalarm.data.primaryWakeScheduleCoordinatorWithClock
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSourceKind
import com.dsalmun.luxalarm.wake.WakeEventIdentity
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorDelivery
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@Suppress("DEPRECATION")
class PrimaryWakeScheduleCoordinatorTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "primary-wake-schedule-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun successfulPrimariesAreFollowedByFourCanonicalDurableGoalAnchorsInOffsetOrder() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, acquiredAtEpochMillis = 900L)
        val calls = mutableListOf<Pair<Long, PendingIntent>>()

        coordinator(WakeAlarmClockPort { trigger, operation -> calls += trigger to operation })
            .schedule(desired)

        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        val anchorKinds =
            listOf(
                WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                WakeRecoveryAnchorKind.GOAL_PLUS_30M,
            )
        assertEquals(
            listOf(80_000L, 70_000L, 140_000L, 380_000L, 980_000L, 1_880_000L),
            calls.map { it.first },
        )
        assertEquals(
            anchorKinds.map { WakePendingIntentData.anchor(goal, it) },
            calls.drop(2).map { shadowOf(it.second).savedIntent.dataString },
        )
        assertEquals(
            anchorKinds.map { kind ->
                listOf(
                    kind.name,
                    checkNotNull(kind.triggerForGoalOrNull(desired.goalEpochMs)).toString(),
                    "ARMED",
                    WakePendingIntentData.anchor(goal, kind),
                )
            },
            anchorRecords(),
        )
        assertEquals("GOAL=1|START=1", primaryMarkers())
    }

    @Test
    fun goalApiReturnIsRecordedBeforeStartCallAndStartReturnIsRecordedAfterItsCall() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, acquiredAtEpochMillis = 900L)
        val calls = mutableListOf<Pair<Long, PendingIntent>>()
        val markersDuringCalls = mutableListOf<String>()
        val port = WakeAlarmClockPort { trigger, operation ->
            calls += trigger to operation
            markersDuringCalls += primaryMarkers()
        }

        coordinator(port).schedule(desired)

        assertEquals(
            listOf(80_000L, 70_000L, 140_000L, 380_000L, 980_000L, 1_880_000L),
            calls.map { it.first },
        )
        assertEquals(
            listOf(
                "GOAL=0|START=0",
                "GOAL=1|START=0",
                "GOAL=1|START=1",
                "GOAL=1|START=1",
                "GOAL=1|START=1",
                "GOAL=1|START=1",
            ),
            markersDuringCalls,
        )
        assertEquals("GOAL=1|START=1", primaryMarkers())
        assertEquals(
            listOf(
                WakePendingIntentData.primary(
                    WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
                ),
                WakePendingIntentData.primary(
                    WakeEventIdentity(desired.id, WakeEventKind.START, desired.wakeStartEpochMs)
                ),
            ),
            calls.take(2).map { shadowOf(it.second).savedIntent.dataString },
        )
        assertEquals("PREPARING_WAKE|1", ownerAndGeneration())
    }

    @Test
    fun overflowingImmutableAnchorIsRejectedByInitialValidationBeforeAnyApiCall() {
        val desired =
            snapshot()
                .copy(
                    wakeStartEpochMs = Long.MAX_VALUE - 1_800_001L,
                    goalEpochMs = Long.MAX_VALUE - 1_799_999L,
                )
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val before = wholeDatabaseFingerprint()
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }).schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun expiredGoalIsRejectedBeforeAnyApiCallWithoutChangingPreparedPrimaryRows() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        RoomWakePrimaryScheduleStore(database).ensureDesiredPrimaries(desired)
        val before = wholeDatabaseFingerprint()
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }, now = 80_000L)
                .schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun expiredStartIsRejectedBeforeCreatingDispatchRowsOrCallingTheApi() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val before = wholeDatabaseFingerprint()
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }, now = 70_000L)
                .schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals(before, wholeDatabaseFingerprint())
        assertEquals(0L, dispatchCount())
    }

    @Test
    fun completedMarkersDoNotAuthorizeReissuingExpiredPrimaries() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val store = RoomWakePrimaryScheduleStore(database)
        store.ensureDesiredPrimaries(desired)
        store.recordApiReturn(
            desired,
            WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs),
        )
        store.recordApiReturn(
            desired,
            WakeEventIdentity(desired.id, WakeEventKind.START, desired.wakeStartEpochMs),
        )
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor"))
        val before = wholeDatabaseFingerprint()
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }, now = 80_000L)
                .schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals(before, wholeDatabaseFingerprint())
        assertEquals("GOAL=1|START=1", primaryMarkers())
    }

    @Test
    fun startExpiringAfterGoalReturnPreservesOnlyTheGoalReturnRecord() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val times = ArrayDeque(listOf(60_000L, 60_000L, 70_000L))
        val calls = mutableListOf<Long>()
        val coordinator =
            primaryWakeScheduleCoordinatorWithClock(
                context,
                WakeAlarmClockPort { trigger, _ -> calls += trigger },
                RoomWakePrimaryScheduleStore(database),
            ) {
                times.removeFirst()
            }

        assertFailsWith<IllegalStateException> { coordinator.schedule(desired) }

        assertEquals(listOf(80_000L), calls)
        assertEquals("GOAL=1|START=0", primaryMarkers())
        assertEquals("PREPARING_WAKE|1", ownerAndGeneration())
    }

    @Test
    fun goalApiFailureLeavesBothReturnRecordsClearAndPropagatesExactFailure() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val sentinel = IllegalStateException("goal sentinel")
        val calls = mutableListOf<Long>()
        val port = WakeAlarmClockPort { trigger, _ ->
            calls += trigger
            throw sentinel
        }

        val thrown =
            assertFailsWith<IllegalStateException> {
                coordinator(port).schedule(desired)
            }

        assertSame(sentinel, thrown)
        assertEquals(listOf(80_000L), calls)
        assertEquals("GOAL=0|START=0", primaryMarkers())
        assertEquals("PREPARING_WAKE|1", ownerAndGeneration())
    }

    @Test
    fun anchorApiFailurePropagatesExactlyAndLeavesOnlyEarlierSuccessfulReturnRecords() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val sentinel = IllegalArgumentException("five-minute anchor sentinel")
        val calls = mutableListOf<Long>()

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                coordinator(
                        WakeAlarmClockPort { trigger, _ ->
                            calls += trigger
                            if (trigger == 380_000L) throw sentinel
                        }
                    )
                    .schedule(desired)
            }

        assertSame(sentinel, thrown)
        assertEquals(listOf(80_000L, 70_000L, 140_000L, 380_000L), calls)
        assertEquals(listOf("GOAL_PLUS_1M"), anchorRecords().map { it.first() })
    }

    @Test
    fun armedPlusOneApiReturnAllowsDelayedRestartToScheduleOnlyLaterFutureAnchors() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        assertFailsWith<IllegalStateException> {
            coordinator(
                    WakeAlarmClockPort { trigger, _ ->
                        if (trigger == 380_000L) error("stop after plus one return")
                    }
                )
                .schedule(desired)
        }
        assertEquals(listOf("ARMED"), anchorRecords().map { it[2] })
        database.close()
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        val restartCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> restartCalls += trigger }, now = 140_001L)
            .schedule(desired)

        assertEquals(listOf(380_000L, 980_000L, 1_880_000L), restartCalls)
        assertEquals(listOf("ARMED", "ARMED", "ARMED", "ARMED"), anchorRecords().map { it[2] })
    }

    @Test
    fun consumedPlusOneUnderPreparingResumesAfterRestartWithOnlyFutureMissingAnchors() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val firstCalls = mutableListOf<Long>()
        assertFailsWith<IllegalStateException> {
            coordinator(
                    WakeAlarmClockPort { trigger, _ ->
                        firstCalls += trigger
                        if (trigger == 380_000L) error("stop after plus one")
                    }
                )
                .schedule(desired)
        }
        assertEquals(listOf(80_000L, 70_000L, 140_000L, 380_000L), firstCalls)

        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        val kind = WakeRecoveryAnchorKind.GOAL_PLUS_1M
        val identity = WakePendingIntentData.anchor(goal, kind)
        val delivery = WakeRecoveryAnchorDelivery(goal, kind, 140_000L, identity, 140_000L)
        assertEquals(
            WakeRecoveryAnchorReceiptStoreOutcome.APPLIED,
            RoomWakeRecoveryAnchorReceiptStore(database).claim(delivery).outcome,
        )
        val scheduleStore = RoomWakePrimaryScheduleStore(database)
        scheduleStore.recordAnchorApiReturn(desired, goal, kind)
        assertEquals("FIRED", anchorRecords().single().get(2))
        val firedPlan = scheduleStore.prepareSchedule(desired, 140_001L)
        assertEquals(emptyList(), firedPlan.primaryEvents)
        assertEquals(
            listOf(
                WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                WakeRecoveryAnchorKind.GOAL_PLUS_30M,
            ),
            firedPlan.anchorKinds,
        )
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                goal,
                WakeDispatchSourceKind.GOAL_PLUS_1M,
                identity,
                delivery.receivedAtEpochMillis,
            )
        assertEquals(
            WakeRecoveryAnchorProcessingOutcome.DEFERRED_DURABLE,
            RoomWakeRecoveryAnchorProcessingStore(database)
                .processFired(delivery, source, maxHeartbeatAgeMillis = 500L)
                .outcome,
        )
        assertEquals("CONSUMED", anchorRecords().single().get(2))
        scheduleStore.recordAnchorApiReturn(desired, goal, kind)
        assertEquals("CONSUMED", anchorRecords().single().get(2))
        assertEquals("DEFERRED", goalDispatchState())
        database.close()
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        val restartCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> restartCalls += trigger }, now = 140_001L)
            .schedule(desired)

        assertEquals(listOf(380_000L, 980_000L, 1_880_000L), restartCalls)
        assertEquals(
            listOf("CONSUMED", "ARMED", "ARMED", "ARMED"),
            anchorRecords().map { it[2] },
        )
        assertEquals("DEFERRED", goalDispatchState())
    }

    @Test
    fun progressedPrefixStillFailsClosedWhenAPastAnchorHasNoApiReturnRow() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        assertFailsWith<IllegalStateException> {
            coordinator(
                    WakeAlarmClockPort { trigger, _ ->
                        if (trigger == 380_000L) error("leave plus five unrecorded")
                    }
                )
                .schedule(desired)
        }
        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        val kind = WakeRecoveryAnchorKind.GOAL_PLUS_1M
        val identity = WakePendingIntentData.anchor(goal, kind)
        val delivery = WakeRecoveryAnchorDelivery(goal, kind, 140_000L, identity, 140_000L)
        assertEquals(
            WakeRecoveryAnchorReceiptStoreOutcome.APPLIED,
            RoomWakeRecoveryAnchorReceiptStore(database).claim(delivery).outcome,
        )
        val before = wholeDatabaseFingerprint()
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }, now = 380_001L)
                .schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals(before, wholeDatabaseFingerprint())
        assertEquals(listOf("FIRED"), anchorRecords().map { it[2] })
    }

    @Test
    fun anchorMarkerWriteFaultRollsBackOnlyThatMarkerAndRestartReissuesAllFutureAnchors() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val sentinel = IllegalStateException("five-minute marker sentinel")
        val calls = mutableListOf<Long>()
        val store =
            primaryScheduleStoreWithFaultHook(database) { point ->
                if (point == "AFTER_GOAL_PLUS_5M_API_RETURN_RECORD") throw sentinel
            }

        val thrown =
            assertFailsWith<IllegalStateException> {
                primaryWakeScheduleCoordinatorWithClock(
                        context,
                        WakeAlarmClockPort { trigger, _ -> calls += trigger },
                        store,
                    ) {
                        60_000L
                    }
                    .schedule(desired)
            }

        assertSame(sentinel, thrown)
        assertEquals(listOf(80_000L, 70_000L, 140_000L, 380_000L), calls)
        assertEquals(listOf("GOAL_PLUS_1M"), anchorRecords().map { it.first() })
        database.close()
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        val restartCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> restartCalls += trigger }).schedule(desired)

        assertEquals(allTriggerTimes(), restartCalls)
        assertEquals(4, anchorRecords().size)
    }

    @Test
    fun finalAnchorClockRunsAfterPreflightAndPendingIntentCreationThenRejectsExpiredCall() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        val times = ArrayDeque(listOf(60_000L, 60_000L, 60_000L, 140_000L))
        val calls = mutableListOf<Long>()
        val coordinator =
            primaryWakeScheduleCoordinatorWithClock(
                context,
                WakeAlarmClockPort { trigger, _ -> calls += trigger },
                RoomWakePrimaryScheduleStore(database),
            ) {
                val now = times.removeFirst()
                if (now == 140_000L) {
                    assertNotNull(
                        WakePendingIntentFactory.lookupAnchor(
                            context,
                            goal,
                            WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                        )
                    )
                }
                now
            }

        assertFailsWith<IllegalStateException> { coordinator.schedule(desired) }

        assertEquals(listOf(80_000L, 70_000L), calls)
        assertEquals(emptyList(), anchorRecords())
        assertEquals("GOAL=1|START=1", primaryMarkers())
    }

    @Test
    fun authorityChangedAfterFirstAnchorRecordIsFencedBeforeNextPendingIntentCreation() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        val calls = mutableListOf<Long>()
        val store =
            primaryScheduleStoreWithFaultHook(database) { point ->
                if (point == "AFTER_GOAL_PLUS_1M_API_RETURN_RECORD") {
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
                    )
                }
            }

        assertFailsWith<IllegalStateException> {
            primaryWakeScheduleCoordinatorWithClock(
                    context,
                    WakeAlarmClockPort { trigger, _ -> calls += trigger },
                    store,
                ) {
                    60_000L
                }
                .schedule(desired)
        }

        assertEquals(listOf(80_000L, 70_000L, 140_000L), calls)
        assertEquals(listOf("GOAL_PLUS_1M"), anchorRecords().map { it.first() })
        assertNull(
            WakePendingIntentFactory.lookupAnchor(
                context,
                goal,
                WakeRecoveryAnchorKind.GOAL_PLUS_5M,
            )
        )
    }

    @Test
    fun startApiFailureKeepsGoalReturnRecordAndRestartReissuesGoalBeforeStart() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val sentinel = IllegalArgumentException("start sentinel")
        val firstCalls = mutableListOf<Long>()
        val failingPort = WakeAlarmClockPort { trigger, _ ->
            firstCalls += trigger
            if (trigger == desired.wakeStartEpochMs) throw sentinel
        }

        val thrown =
            assertFailsWith<IllegalArgumentException> {
                coordinator(failingPort).schedule(desired)
            }

        assertSame(sentinel, thrown)
        assertEquals(listOf(80_000L, 70_000L), firstCalls)
        assertEquals("GOAL=1|START=0", primaryMarkers())
        database.close()
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        val restartCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> restartCalls += trigger }).schedule(desired)

        assertEquals(allTriggerTimes(), restartCalls)
        assertEquals("GOAL=1|START=1", primaryMarkers())
        assertEquals("PREPARING_WAKE|1", ownerAndGeneration())
    }

    @Test
    fun successfulRerunReissuesBothFutureDesiredPrimariesInSafetyOrder() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        coordinator(WakeAlarmClockPort { _, _ -> }).schedule(desired)
        val rerunCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> rerunCalls += trigger }).schedule(desired)

        assertEquals(allTriggerTimes(), rerunCalls)
        assertEquals("GOAL=1|START=1", primaryMarkers())
    }

    @Test
    fun simultaneousFirstInsertFromIndependentRoomHandlesConvergesToTwoCanonicalRows() {
        repeat(8) { iteration ->
            if (iteration > 0) resetDatabase()
            val desired = snapshot()
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)

            val outcomes = racePrimaryRowCreation(desired, desired)

            outcomes.forEach { it.getOrThrow() }
            assertEquals("GOAL=0|START=0", primaryMarkers(), iteration.toString())
            assertEquals(2L, dispatchCount(), iteration.toString())
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor"))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM schedule_outbox"))
        }
    }

    @Test
    fun simultaneousConflictingFirstInsertRejectsOnlyNoncanonicalSnapshotWithoutResidue() {
        repeat(8) { iteration ->
            if (iteration > 0) resetDatabase()
            val desired = snapshot()
            val conflicting = desired.copy(wakeStartEpochMs = 69_999L)
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)

            val outcomes = racePrimaryRowCreation(desired, conflicting)

            assertEquals(1, outcomes.count { it.isSuccess }, iteration.toString())
            assertEquals(
                1,
                outcomes.count { it.exceptionOrNull() is IllegalStateException },
                iteration.toString(),
            )
            assertEquals("GOAL=0|START=0", primaryMarkers(), iteration.toString())
            assertEquals(2L, dispatchCount(), iteration.toString())
            assertEquals(
                0L,
                scalarLong(
                    "SELECT COUNT(*) FROM wake_event_dispatch WHERE expected_trigger_epoch_ms=69999"
                ),
            )
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor"))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM schedule_outbox"))
        }
    }

    @Test
    fun androidPortRegistersCanonicalPrimariesAndAllFourImmutableAnchorAlarmClockEntries() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        coordinator(AndroidWakeAlarmClockPort(alarmManager)).schedule(desired)

        val scheduled = shadowOf(alarmManager).scheduledAlarms.filter { it.alarmClockInfo != null }
        assertEquals(allTriggerTimes().sorted(), scheduled.map { it.triggerAtTime }.sorted())
        val identities = scheduled.associate {
            it.triggerAtTime to shadowOf(it.operation).savedIntent.dataString
        }
        val receiverClasses = scheduled.associate {
            it.triggerAtTime to shadowOf(it.operation).savedIntent.component?.className
        }
        assertEquals(
            WakePendingIntentData.primary(
                WakeEventIdentity(desired.id, WakeEventKind.START, desired.wakeStartEpochMs)
            ),
            identities.getValue(70_000L),
        )
        assertEquals(
            WakePendingIntentData.primary(
                WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
            ),
            identities.getValue(80_000L),
        )
        val goal = WakeEventIdentity(desired.id, WakeEventKind.GOAL, desired.goalEpochMs)
        listOf(
                WakeRecoveryAnchorKind.GOAL_PLUS_1M,
                WakeRecoveryAnchorKind.GOAL_PLUS_5M,
                WakeRecoveryAnchorKind.GOAL_PLUS_15M,
                WakeRecoveryAnchorKind.GOAL_PLUS_30M,
            )
            .forEach { kind ->
                val trigger = checkNotNull(kind.triggerForGoalOrNull(desired.goalEpochMs))
                assertEquals(
                    WakePendingIntentData.anchor(goal, kind),
                    identities.getValue(trigger),
                )
                assertEquals(WakeGoalReceiver::class.java.name, receiverClasses.getValue(trigger))
            }
        scheduled.forEach {
            assertEquals(it.triggerAtTime, assertNotNull(it.alarmClockInfo).triggerTime)
        }
    }

    @Test
    fun authorityCorruptionCommittedAfterGoalRecordIsFencedBeforeStartApiCall() {
        val corruptions =
            listOf(
                "owner" to "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1",
                "generation" to "UPDATE migration_state SET active_generation=2 WHERE id=1",
                "snapshot" to
                    "UPDATE wake_run_snapshot SET wake_start_epoch_ms=69999 WHERE id='primary-order'",
                "status" to
                    "UPDATE wake_run_status SET armed_start=1 WHERE snapshot_id='primary-order'",
            )
        corruptions.forEachIndexed { index, (label, sql) ->
            if (index > 0) resetDatabase()
            val desired = snapshot()
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
            val calls = mutableListOf<Long>()
            val store =
                primaryScheduleStoreWithFaultHook(database) { point ->
                    if (point == "AFTER_GOAL_PRIMARY_API_RETURN_RECORD") {
                        database.openHelper.writableDatabase.execSQL(sql)
                    }
                }

            assertFailsWith<IllegalStateException>(label) {
                primaryWakeScheduleCoordinatorWithClock(
                        context,
                        WakeAlarmClockPort { trigger, _ -> calls += trigger },
                        store,
                    ) {
                        60_000L
                    }
                    .schedule(desired)
            }

            assertEquals(listOf(80_000L), calls, label)
            assertEquals("GOAL=1|START=0", primaryMarkers(), label)
            assertNull(
                WakePendingIntentFactory.lookupPrimary(
                    context,
                    WakeEventIdentity(
                        desired.id,
                        WakeEventKind.START,
                        desired.wakeStartEpochMs,
                    ),
                ),
                label,
            )
            assertEquals(2L, dispatchCount(), label)
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor"), label)
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM schedule_outbox"), label)
        }
    }

    @Test
    fun transactionFaultsRollbackThenRestartConvergesInGoalBeforeStartOrder() {
        val scenarios =
            listOf(
                Triple("AFTER_PRIMARY_DISPATCH_INSERT", emptyList<Long>(), ""),
                Triple(
                    "AFTER_GOAL_PRIMARY_API_RETURN_RECORD",
                    listOf(80_000L),
                    "GOAL=0|START=0",
                ),
                Triple(
                    "AFTER_START_PRIMARY_API_RETURN_RECORD",
                    listOf(80_000L, 70_000L),
                    "GOAL=1|START=0",
                ),
            )
        scenarios.forEachIndexed { index, (faultPoint, expectedCalls, expectedMarkers) ->
            if (index > 0) resetDatabase()
            val desired = snapshot()
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
            val sentinel = IllegalStateException("$faultPoint sentinel")
            val firstCalls = mutableListOf<Long>()
            val store =
                primaryScheduleStoreWithFaultHook(database) { point ->
                    if (point == faultPoint) throw sentinel
                }

            val thrown =
                assertFailsWith<IllegalStateException> {
                    primaryWakeScheduleCoordinatorWithClock(
                            context,
                            WakeAlarmClockPort { trigger, _ -> firstCalls += trigger },
                            store,
                        ) {
                            60_000L
                        }
                        .schedule(desired)
                }

            assertSame(sentinel, thrown, faultPoint)
            assertEquals(expectedCalls, firstCalls, faultPoint)
            assertEquals(expectedMarkers, primaryMarkers(), faultPoint)
            assertEquals("PREPARING_WAKE|1", ownerAndGeneration(), faultPoint)

            database.close()
            database =
                AlarmDatabase.databaseBuilder(context, databaseName)
                    .allowMainThreadQueries()
                    .build()
            val restartCalls = mutableListOf<Long>()
            coordinator(WakeAlarmClockPort { trigger, _ -> restartCalls += trigger })
                .schedule(desired)

            assertEquals(allTriggerTimes(), restartCalls, faultPoint)
            assertEquals("GOAL=1|START=1", primaryMarkers(), faultPoint)
            assertEquals("PREPARING_WAKE|1", ownerAndGeneration(), faultPoint)
            assertEquals(2L, dispatchCount(), faultPoint)
            assertEquals(4L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor"), faultPoint)
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM schedule_outbox"), faultPoint)
        }
    }

    @Test
    fun noncanonicalPrimaryMarkerFailsClosedBeforeAnyApiCall() {
        val desired = snapshot()
        RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
        RoomWakePrimaryScheduleStore(database).ensureDesiredPrimaries(desired)
        database.openHelper.writableDatabase.execSQL("PRAGMA ignore_check_constraints = ON")
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_event_dispatch SET armed_primary=2 WHERE event_kind='GOAL'"
        )
        val calls = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }).schedule(desired)
        }

        assertEquals(emptyList(), calls)
        assertEquals("GOAL=2|START=0", primaryMarkers())
        assertEquals("PREPARING_WAKE|1", ownerAndGeneration())
    }

    @Test
    fun selectedTrackAggregateSchedulesWhenCanonicalAndFailsClosedForEveryCorruption() {
        insertTrack("primary-track")
        val selected = snapshot("primary-track")
        RoomWakeSchedulePreparationStore(database).prepare(selected, 900L)
        val normalCalls = mutableListOf<Long>()

        coordinator(WakeAlarmClockPort { trigger, _ -> normalCalls += trigger }).schedule(selected)

        assertEquals(allTriggerTimes(), normalCalls)
        assertEquals("GOAL=1|START=1", primaryMarkers())

        val corruptions =
            listOf<Pair<String, () -> Unit>>(
                "missing lease" to
                    {
                        database.openHelper.writableDatabase.execSQL("DELETE FROM track_lease")
                    },
                "wrong lease" to
                    {
                        insertTrack("other-track")
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE track_lease SET track_id='other-track'"
                        )
                    },
                "storage key" to
                    {
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE imported_track SET storage_key='tracks/wrong' WHERE id='primary-track'"
                        )
                    },
                "lifecycle" to
                    {
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE imported_track SET lifecycle_state='PENDING_DELETE' WHERE id='primary-track'"
                        )
                    },
                "missing track" to
                    {
                        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=OFF")
                        database.openHelper.writableDatabase.execSQL(
                            "DELETE FROM imported_track WHERE id='primary-track'"
                        )
                        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys=ON")
                        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM track_lease"))
                        assertEquals(
                            0L,
                            scalarLong(
                                "SELECT COUNT(*) FROM imported_track WHERE id='primary-track'"
                            ),
                        )
                    },
                "stale ref-count" to
                    {
                        database.openHelper.writableDatabase.execSQL(
                            "UPDATE imported_track SET ref_count_cache=2 WHERE id='primary-track'"
                        )
                    },
            )
        corruptions.forEachIndexed { index, (label, corrupt) ->
            resetDatabase()
            insertTrack("primary-track")
            val desired = snapshot("primary-track")
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
            corrupt()
            val before = wholeDatabaseFingerprint()
            val calls = mutableListOf<Long>()

            assertFailsWith<IllegalStateException>("$index:$label") {
                coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }).schedule(desired)
            }

            assertEquals(emptyList(), calls, label)
            assertEquals(before, wholeDatabaseFingerprint(), label)
            assertEquals(0L, dispatchCount(), label)
        }
    }

    @Test
    fun ownerGenerationSnapshotAndStatusCorruptionFailClosedBeforeApiCalls() {
        val corruptions =
            listOf(
                "owner" to "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1",
                "generation" to "UPDATE migration_state SET active_generation=2 WHERE id=1",
                "snapshot" to
                    "UPDATE wake_run_snapshot SET wake_start_epoch_ms=69999 WHERE id='primary-order'",
                "status" to
                    "UPDATE wake_run_status SET armed_start=1 WHERE snapshot_id='primary-order'",
            )
        corruptions.forEachIndexed { index, (label, sql) ->
            if (index > 0) resetDatabase()
            val desired = snapshot()
            RoomWakeSchedulePreparationStore(database).prepare(desired, 900L)
            database.openHelper.writableDatabase.execSQL(sql)
            val calls = mutableListOf<Long>()

            assertFailsWith<IllegalStateException>(label) {
                coordinator(WakeAlarmClockPort { trigger, _ -> calls += trigger }).schedule(desired)
            }

            assertEquals(emptyList(), calls, label)
            assertEquals(0L, dispatchCount(), label)
        }
    }

    private fun allTriggerTimes() =
        listOf(80_000L, 70_000L, 140_000L, 380_000L, 980_000L, 1_880_000L)

    private fun coordinator(port: WakeAlarmClockPort, now: Long = 60_000L) =
        primaryWakeScheduleCoordinatorWithClock(
            context,
            port,
            RoomWakePrimaryScheduleStore(database),
        ) {
            now
        }

    private fun resetDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
    }

    private fun racePrimaryRowCreation(
        firstSnapshot: WakeRunSnapshotEntity,
        secondSnapshot: WakeRunSnapshotEntity,
    ): List<Result<Unit>> {
        val second =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        return try {
            val requests =
                listOf(
                    RoomWakePrimaryScheduleStore(database) to firstSnapshot,
                    RoomWakePrimaryScheduleStore(second) to secondSnapshot,
                )
            val futures = requests.map { (store, desired) ->
                executor.submit<Result<Unit>> {
                    runCatching {
                        check(start.await(10, TimeUnit.SECONDS)) { "Race start timed out" }
                        store.ensureDesiredPrimaries(desired)
                    }
                }
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            try {
                check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    "Race executor did not terminate"
                }
            } finally {
                second.close()
            }
        }
    }

    private fun primaryMarkers(): String =
        database.openHelper.readableDatabase
            .query(
                "SELECT event_kind || '=' || armed_primary FROM wake_event_dispatch ORDER BY event_kind"
            )
            .use { cursor ->
                buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                    .joinToString("|")
            }

    private fun anchorRecords(): List<List<String>> =
        database.openHelper.readableDatabase
            .query(
                "SELECT anchor_kind,trigger_epoch_ms,state,pending_intent_identity " +
                    "FROM wake_recovery_anchor ORDER BY trigger_epoch_ms"
            )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add((0 until cursor.columnCount).map(cursor::getString))
                    }
                }
            }

    private fun ownerAndGeneration(): String =
        database.openHelper.readableDatabase
            .query(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            )
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun dispatchCount(): Long =
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM wake_event_dispatch")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }

    private fun goalDispatchState(): String =
        database.openHelper.readableDatabase
            .query("SELECT state FROM wake_event_dispatch WHERE event_kind='GOAL'")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun scalarLong(sql: String): Long =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun wholeDatabaseFingerprint():
        List<Triple<String, List<String>, List<List<String?>>>> =
        database.openHelper.readableDatabase
            .query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
            .use { tables ->
                buildList {
                    while (tables.moveToNext()) {
                        val table = tables.getString(0)
                        val escapedTable = table.replace("`", "``")
                        val columns =
                            database.openHelper.readableDatabase
                                .query("PRAGMA table_info(`$escapedTable`)")
                                .use { info ->
                                    buildList {
                                        while (info.moveToNext()) add(info.getString(1))
                                    }
                                }
                        val projection = columns.joinToString { "`${it.replace("`", "``")}`" }
                        val ordering = columns.indices.joinToString { (it + 1).toString() }
                        val rows =
                            database.openHelper.readableDatabase
                                .query("SELECT $projection FROM `$escapedTable` ORDER BY $ordering")
                                .use { cursor ->
                                    buildList {
                                        while (cursor.moveToNext()) {
                                            add(
                                                buildList {
                                                    for (column in 0 until cursor.columnCount) {
                                                        add(
                                                            if (cursor.isNull(column)) null
                                                            else cursor.getString(column)
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                        add(Triple(table, columns, rows))
                    }
                }
            }

    private fun insertTrack(id: String) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO imported_track(
              id, storage_key, title, artist, duration_ms, mime_type, content_hash,
              lifecycle_state, availability, deletion_token, ref_count_cache, added_at
            ) VALUES (?, ?, ?, NULL, 1000, 'audio/mpeg', ?, 'AVAILABLE', 'AVAILABLE', NULL, 0, 1)
            """
                .trimIndent(),
            arrayOf<Any?>(id, "tracks/$id", id, "hash-$id"),
        )
    }

    private fun snapshot(
        selectedTrackId: String? = null,
        selectedTrackStorageKey: String? = selectedTrackId?.let { "tracks/$it" },
    ) =
        WakeRunSnapshotEntity(
            id = "primary-order",
            occurrenceId = "occurrence-primary-order",
            scheduleGeneration = 1L,
            routineRevision = 1L,
            calculationRuleVersion = 1L,
            zoneId = "UTC",
            occurrenceLocalDate = "2026-09-05",
            wakeStartEpochMs = 70_000L,
            goalEpochMs = 80_000L,
            lightPayload = "light",
            musicPayload = "music",
            vibrationPayload = "vibration",
            selectedTrackId = selectedTrackId,
            selectedTrackStorageKey = selectedTrackStorageKey,
            dismissal = "CONFIRM",
            createdAt = 800L,
            installEpoch = installEpoch(),
        )

    private fun installEpoch(): String =
        database.openHelper.readableDatabase
            .query("SELECT install_epoch FROM migration_state WHERE id=1")
            .use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
}
