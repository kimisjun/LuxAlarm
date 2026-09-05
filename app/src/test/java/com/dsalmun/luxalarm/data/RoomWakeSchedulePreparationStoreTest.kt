/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomWakeSchedulePreparationStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var store: RoomWakeSchedulePreparationStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-schedule-preparation-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeSchedulePreparationStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun firstDesiredSnapshotIsPersistedWithPreparedStatusAndPreparingGeneration() {
        val desired = snapshot(id = "desired-1", generation = 1L)

        assertEquals(
            WakeSchedulePreparationOutcome.PREPARED,
            store.prepare(desired, acquiredAtEpochMillis = 900L),
        )

        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals("PREPARED", scalarText("SELECT state FROM wake_run_status"))
        assertEquals(
            "PREPARING_WAKE|1",
            scalarText(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun legacyOwnerWithActiveGenerationRejectsFirstDesiredSnapshotWithoutAnyDatabaseMutation() {
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET active_generation=7 WHERE id=1"
        )
        val before = wholeDatabaseFingerprint()

        val failure =
            assertFailsWith<IllegalStateException> {
                store.prepare(snapshot(id = "legacy-fenced", generation = 1L), 900L)
            }

        assertEquals("First wake generation requires unfenced LEGACY ownership", failure.message)
        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun firstDesiredGenerationOtherThanOneIsRejectedWithoutAnyDatabaseMutation() {
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "not-first-generation", generation = 2L), 900L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun nextDesiredSnapshotAdvancesTheDurableGeneration() {
        store.prepare(snapshot(id = "completed-1", generation = 1L), 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state='COMPLETED', completed_at=2100 WHERE snapshot_id='completed-1'"
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
        )

        assertEquals(
            WakeSchedulePreparationOutcome.PREPARED,
            store.prepare(snapshot(id = "desired-2", generation = 2L), 2_200L),
        )

        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals(
            "PREPARING_WAKE|2",
            scalarText(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun wakeOwnerRejectsCompletedCurrentAggregateMissingExpectedLeaseWithoutAnyDatabaseMutation() {
        val trackId = "current-missing-lease-track"
        insertTrack(trackId)
        val current =
            snapshot(id = "current-missing-lease", generation = 1L)
                .copy(
                    selectedTrackId = trackId,
                    selectedTrackStorageKey = "tracks/$trackId",
                )
        completeCurrentGeneration(current)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM track_lease WHERE snapshot_id='current-missing-lease'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "blocked-missing-lease", generation = 2L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun wakeOwnerRejectsCompletedCurrentAggregateWithLeaseDespiteNullSelectionWithoutMutation() {
        completeCurrentGeneration("current-null-selection")
        insertTrack("current-unexpected-track")
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_lease(snapshot_id, track_id, acquired_at) " +
                "VALUES ('current-null-selection', 'current-unexpected-track', 900)"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "blocked-null-selection", generation = 2L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun wakeOwnerRejectsCompletedCurrentAggregateWithDifferentTrackLeaseWithoutAnyDatabaseMutation() {
        val expectedTrackId = "current-expected-lease-track"
        val differentTrackId = "current-different-lease-track"
        insertTrack(expectedTrackId)
        insertTrack(differentTrackId)
        val current =
            snapshot(id = "current-different-lease", generation = 1L)
                .copy(
                    selectedTrackId = expectedTrackId,
                    selectedTrackStorageKey = "tracks/$expectedTrackId",
                )
        completeCurrentGeneration(current)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM track_lease WHERE snapshot_id='current-different-lease'"
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_lease(snapshot_id, track_id, acquired_at) " +
                "VALUES ('current-different-lease', 'current-different-lease-track', 900)"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "blocked-different-lease", generation = 2L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun wakeOwnerRejectsCompletedCurrentAggregateWithStaleGlobalRefCountWithoutAnyDatabaseMutation() {
        val trackId = "current-stale-ref-count-track"
        insertTrack(trackId)
        val current =
            snapshot(id = "current-stale-ref-count", generation = 1L)
                .copy(
                    selectedTrackId = trackId,
                    selectedTrackStorageKey = "tracks/$trackId",
                )
        completeCurrentGeneration(current)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET ref_count_cache=0 WHERE id=?",
            arrayOf<Any?>(trackId),
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "blocked-stale-ref-count", generation = 2L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun wakeOwnerRejectsCompletedCurrentAggregateWithCorruptAuthoritativeTrackWithoutMutation() {
        listOf(
                "storage-key" to
                    "UPDATE imported_track SET storage_key='tracks/relocated' WHERE id=?",
                "lifecycle" to
                    "UPDATE imported_track SET lifecycle_state='PENDING_DELETE' WHERE id=?",
            )
            .forEachIndexed { index, (corruption, corruptionSql) ->
                if (index > 0) resetDatabase()
                val trackId = "current-corrupt-$corruption-track"
                insertTrack(trackId)
                val current =
                    snapshot(id = "current-corrupt-$corruption", generation = 1L)
                        .copy(
                            selectedTrackId = trackId,
                            selectedTrackStorageKey = "tracks/$trackId",
                        )
                completeCurrentGeneration(current)
                database.openHelper.writableDatabase.execSQL(
                    corruptionSql,
                    arrayOf<Any?>(trackId),
                )
                val before = wholeDatabaseFingerprint()

                assertFailsWith<IllegalStateException>(corruption) {
                    store.prepare(
                        snapshot(id = "blocked-corrupt-$corruption", generation = 2L),
                        2_200L,
                    )
                }

                assertEquals(before, wholeDatabaseFingerprint(), corruption)
            }
    }

    @Test
    fun successorReadyCanonicalStatusMatrixAllowsExactNextGeneration() {
        val readyStatuses =
            listOf(
                StatusFixture("COMPLETED", completedAt = 2_100L),
                StatusFixture(
                    "NO_CONFIRMATION",
                    completedAt = 2_100L,
                    failureReason = "NO_CONFIRMATION_DEADLINE",
                ),
                StatusFixture("FAILED"),
                StatusFixture("CANCELLED", cancelledAt = 2_100L),
                StatusFixture("SUPERSEDED"),
                StatusFixture("EXPIRED"),
                StatusFixture("GOAL_REACHED", processedGoalAt = 2_000L),
            )
        var current = snapshot(id = "ready-0", generation = 1L)
        store.prepare(current, 900L)

        readyStatuses.forEachIndexed { index, status ->
            setCanonicalStatus(current.id, status)
            database.openHelper.writableDatabase.execSQL(
                "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
            )
            val next =
                snapshot(id = "ready-${index + 1}", generation = current.scheduleGeneration + 1L)

            assertEquals(
                WakeSchedulePreparationOutcome.PREPARED,
                store.prepare(next, 2_200L + index),
                status.state,
            )

            current = next
        }
    }

    @Test
    fun successorNotReadyCanonicalStatusMatrixRejectsWithoutAnyDatabaseMutation() {
        val current = snapshot(id = "not-ready-current", generation = 1L)
        store.prepare(current, 900L)
        listOf(
                StatusFixture("PREPARED"),
                StatusFixture("ACTIVE"),
                StatusFixture("GOAL_REACHED"),
            )
            .forEach { status ->
                setCanonicalStatus(current.id, status)
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
                )
                val before = wholeDatabaseFingerprint()

                assertFailsWith<IllegalStateException>(status.state) {
                    store.prepare(snapshot(id = "blocked-${status.state}", generation = 2L), 2_200L)
                }

                assertEquals(before, wholeDatabaseFingerprint(), status.state)
            }
    }

    @Test
    fun wakeOwnerRejectsStaleDesiredGenerationAfterCurrentRunCompletedWithoutAnyDatabaseMutation() {
        completeCurrentGeneration("stale-current")
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "stale-desired", generation = 1L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun wakeOwnerRejectsSkippedDesiredGenerationAfterCurrentRunCompletedWithoutAnyDatabaseMutation() {
        completeCurrentGeneration("skipped-current")
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "skipped-desired", generation = 3L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryConvergesWithoutDuplicatingTheAggregate() {
        val desired = snapshot(id = "retry-1", generation = 1L)
        assertEquals(WakeSchedulePreparationOutcome.PREPARED, store.prepare(desired, 900L))

        assertEquals(
            WakeSchedulePreparationOutcome.CONVERGED,
            store.prepare(desired, 900L),
        )

        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_status"))
        assertEquals(
            "PREPARING_WAKE|1",
            scalarText(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun exactPreparingRetryRejectsCorruptPreparedStatusWithoutAnyDatabaseMutation() {
        val desired = snapshot(id = "retry-corrupt-status", generation = 1L)
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state='COMPLETED' WHERE snapshot_id='retry-corrupt-status'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsAuthoritativeTrackStorageKeyMismatchWithoutMutation() {
        insertTrack("retry-storage-key")
        val desired =
            snapshot(id = "retry-storage-key-run", generation = 1L)
                .copy(
                    selectedTrackId = "retry-storage-key",
                    selectedTrackStorageKey = "tracks/retry-storage-key",
                )
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET storage_key='tracks/relocated' " +
                "WHERE id='retry-storage-key'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsRefCountCacheThatOmitsAnotherSnapshotsLeaseWithoutMutation() {
        insertTrack("retry-ref-count")
        val desired =
            snapshot(id = "retry-ref-count-run", generation = 1L)
                .copy(
                    selectedTrackId = "retry-ref-count",
                    selectedTrackStorageKey = "tracks/retry-ref-count",
                )
        store.prepare(desired, 900L)
        database
            .wakeRunStorageDao()
            .createSnapshot(
                snapshot(id = "other-ref-count-run", generation = 99L)
                    .copy(
                        selectedTrackId = "retry-ref-count",
                        selectedTrackStorageKey = "tracks/retry-ref-count",
                    ),
                acquiredAt = 901L,
            )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET ref_count_cache=1 WHERE id='retry-ref-count'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsMissingSelectedTrackWithoutMutation() {
        insertTrack("retry-missing-track")
        val desired =
            snapshot(id = "retry-missing-track-run", generation = 1L)
                .copy(
                    selectedTrackId = "retry-missing-track",
                    selectedTrackStorageKey = "tracks/retry-missing-track",
                )
        store.prepare(desired, 900L)
        val writable = database.openHelper.writableDatabase
        writable.execSQL("PRAGMA foreign_keys=OFF")
        try {
            writable.execSQL("DELETE FROM imported_track WHERE id='retry-missing-track'")
        } finally {
            writable.execSQL("PRAGMA foreign_keys=ON")
        }
        assertEquals(1L, scalarLong("PRAGMA foreign_keys"))
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryAllowsMissingOrBrokenAvailabilityForLifecycleAvailableTrack() {
        insertTrack("retry-fallback")
        val desired =
            snapshot(id = "retry-fallback-run", generation = 1L)
                .copy(
                    selectedTrackId = "retry-fallback",
                    selectedTrackStorageKey = "tracks/retry-fallback",
                )
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET availability='MISSING_OR_BROKEN' " +
                "WHERE id='retry-fallback'"
        )
        val before = wholeDatabaseFingerprint()

        assertEquals(WakeSchedulePreparationOutcome.CONVERGED, store.prepare(desired, 900L))

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsLeaseForSnapshotWithoutSelectedTrack() {
        val desired = snapshot(id = "retry-null-track-run", generation = 1L)
        store.prepare(desired, 900L)
        insertTrack("unexpected-track")
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_lease(snapshot_id, track_id, acquired_at) " +
                "VALUES ('retry-null-track-run', 'unexpected-track', 900)"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsLeaseForDifferentSelectedTrackWithoutAnyDatabaseMutation() {
        insertTrack("expected-lease-track")
        insertTrack("different-lease-track")
        val desired =
            snapshot(id = "retry-different-lease", generation = 1L)
                .copy(
                    selectedTrackId = "expected-lease-track",
                    selectedTrackStorageKey = "tracks/expected-lease-track",
                )
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM track_lease WHERE snapshot_id='retry-different-lease'"
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO track_lease(snapshot_id, track_id, acquired_at) " +
                "VALUES ('retry-different-lease', 'different-lease-track', 900)"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsMismatchedLeaseAcquisitionEpochWithoutAnyDatabaseMutation() {
        insertTrack("lease-epoch-track")
        val desired =
            snapshot(id = "retry-lease-epoch", generation = 1L)
                .copy(
                    selectedTrackId = "lease-epoch-track",
                    selectedTrackStorageKey = "tracks/lease-epoch-track",
                )
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE track_lease SET acquired_at=901 WHERE snapshot_id='retry-lease-epoch'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun exactPreparingRetryRejectsNonAvailableAuthoritativeTrackLifecycleWithoutMutation() {
        insertTrack("retry-lifecycle")
        val desired =
            snapshot(id = "retry-lifecycle-run", generation = 1L)
                .copy(
                    selectedTrackId = "retry-lifecycle",
                    selectedTrackStorageKey = "tracks/retry-lifecycle",
                )
        store.prepare(desired, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET lifecycle_state='PENDING_DELETE' " +
                "WHERE id='retry-lifecycle'"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> { store.prepare(desired, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun sameGenerationWithDifferentDesiredSnapshotIsRejectedWithoutMutation() {
        val durable = snapshot(id = "conflict-1", generation = 1L)
        store.prepare(durable, 900L)
        val conflicting = durable.copy(goalEpochMs = 2_001L)

        assertFailsWith<IllegalStateException> { store.prepare(conflicting, 900L) }

        assertEquals(
            2_000L,
            scalarLong("SELECT goal_epoch_ms FROM wake_run_snapshot WHERE id='conflict-1'"),
        )
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals(
            "PREPARING_WAKE|1",
            scalarText(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun concurrentSameFirstDesiredSnapshotHasOnePreparedAndOneConvergedCanonicalAggregate() {
        repeat(CONCURRENT_PREPARATION_ITERATIONS) { iteration ->
            withIsolatedRaceDatabases("same-desired", iteration) { first, second, executor ->
                val trackId = "same-desired-track-$iteration"
                insertTrack(first, trackId)
                assertUnfencedLegacyWithoutWakeAggregate(first)
                val installEpoch =
                    scalarText("SELECT install_epoch FROM migration_state WHERE id=1", first)
                val desired =
                    snapshot(id = "same-desired-$iteration", generation = 1L)
                        .copy(
                            selectedTrackId = trackId,
                            selectedTrackStorageKey = "tracks/$trackId",
                            installEpoch = installEpoch,
                        )
                val attempts =
                    runConcurrentPrepares(
                        iteration,
                        executor,
                        first to desired,
                        second to desired,
                    )

                assertEquals(
                    listOf(
                        WakeSchedulePreparationOutcome.CONVERGED,
                        WakeSchedulePreparationOutcome.PREPARED,
                    ),
                    attempts.mapNotNull { it.value }.sortedBy { it.name },
                    "result multiset at iteration $iteration: $attempts",
                )
                assertTrue(
                    attempts.all { it.throwable == null },
                    "unexpected failure at iteration $iteration: $attempts",
                )
                assertCanonicalFirstGeneration(first, desired, trackId)
                assertEquals(
                    wholeDatabaseFingerprint(first),
                    wholeDatabaseFingerprint(second),
                    "database handles disagree at iteration $iteration",
                )
            }
        }
    }

    @Test
    fun concurrentConflictingFirstDesiredSnapshotsLeaveOnlyThePreparedWinner() {
        repeat(CONCURRENT_PREPARATION_ITERATIONS) { iteration ->
            withIsolatedRaceDatabases("conflicting-desired", iteration) { first, second, executor ->
                val trackId = "conflicting-desired-track-$iteration"
                insertTrack(first, trackId)
                assertUnfencedLegacyWithoutWakeAggregate(first)
                val installEpoch =
                    scalarText("SELECT install_epoch FROM migration_state WHERE id=1", first)
                val firstDesired =
                    snapshot(id = "conflicting-first-$iteration", generation = 1L)
                        .copy(
                            selectedTrackId = trackId,
                            selectedTrackStorageKey = "tracks/$trackId",
                            installEpoch = installEpoch,
                        )
                val secondDesired =
                    snapshot(id = "conflicting-second-$iteration", generation = 1L)
                        .copy(
                            selectedTrackId = trackId,
                            selectedTrackStorageKey = "tracks/$trackId",
                            installEpoch = installEpoch,
                            goalEpochMs = 2_001L,
                        )
                val desiredByAttempt = listOf(firstDesired, secondDesired)
                val attempts =
                    runConcurrentPrepares(
                        iteration,
                        executor,
                        first to firstDesired,
                        second to secondDesired,
                    )
                val winnerIndexes =
                    attempts.indices.filter {
                        attempts[it].value == WakeSchedulePreparationOutcome.PREPARED &&
                            attempts[it].throwable == null
                    }
                val loserIndexes = attempts.indices - winnerIndexes.toSet()

                assertEquals(
                    1,
                    winnerIndexes.size,
                    "expected one PREPARED winner at iteration $iteration: $attempts",
                )
                assertEquals(
                    1,
                    loserIndexes.size,
                    "expected one loser at iteration $iteration: $attempts",
                )
                val loser = attempts[loserIndexes.single()]
                assertEquals(null, loser.value, "loser returned a value at iteration $iteration")
                assertEquals(
                    IllegalStateException::class.java.name,
                    loser.throwable?.javaClass?.name,
                    "unexpected loser at iteration $iteration: $loser",
                )
                assertEquals(
                    "Preparing generation conflicts with the durable snapshot",
                    loser.throwable?.message,
                    "unexpected loser message at iteration $iteration",
                )

                val winner = desiredByAttempt[winnerIndexes.single()]
                val loserDesired = desiredByAttempt[loserIndexes.single()]
                assertCanonicalFirstGeneration(first, winner, trackId)
                assertEquals(
                    0L,
                    scalarLong(
                        "SELECT COUNT(*) FROM wake_run_snapshot WHERE id='${loserDesired.id}'",
                        first,
                    ),
                    "loser snapshot residue at iteration $iteration",
                )
                assertEquals(
                    0L,
                    scalarLong(
                        "SELECT COUNT(*) FROM wake_run_status WHERE snapshot_id='${loserDesired.id}'",
                        first,
                    ),
                    "loser status residue at iteration $iteration",
                )
                assertEquals(
                    0L,
                    scalarLong(
                        "SELECT COUNT(*) FROM track_lease WHERE snapshot_id='${loserDesired.id}'",
                        first,
                    ),
                    "loser lease residue at iteration $iteration",
                )
                assertEquals(
                    wholeDatabaseFingerprint(first),
                    wholeDatabaseFingerprint(second),
                    "database handles disagree at iteration $iteration",
                )
            }
        }
    }

    @Test
    fun invalidDesiredSnapshotZoneIsRejectedBeforeAnyDatabaseMutation() {
        val malformed = snapshot(id = "invalid-zone", generation = 1L).copy(zoneId = "Not/AZone")
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalArgumentException> { store.prepare(malformed, 900L) }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun productionJvmSurfaceExposesOnlyTheDatabaseConstructorAndNoFaultSeamMembers() {
        val implementation = RoomWakeSchedulePreparationStore::class.java
        val databaseConstructor = implementation.getDeclaredConstructor(AlarmDatabase::class.java)
        assertFalse(Modifier.isPrivate(databaseConstructor.modifiers))
        assertFalse(databaseConstructor.isSynthetic)

        val faultConstructor =
            implementation.getDeclaredConstructor(
                AlarmDatabase::class.java,
                Function1::class.java,
            )
        assertTrue(Modifier.isPrivate(faultConstructor.modifiers))
        assertFalse(faultConstructor.isSynthetic)

        val nonPrivateSourceConstructors =
            implementation.declaredConstructors.filter {
                !Modifier.isPrivate(it.modifiers) && !it.isSynthetic
            }
        assertEquals(listOf(databaseConstructor), nonPrivateSourceConstructors)
        implementation.declaredConstructors
            .filter { it.isSynthetic }
            .forEach { constructor ->
                assertEquals(
                    listOf(
                        AlarmDatabase::class.java,
                        Function1::class.java,
                        Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
                    ),
                    constructor.parameterTypes.toList(),
                    constructor.toString(),
                )
            }

        val exposedFaultFields =
            implementation.declaredFields.filter { field ->
                (Modifier.isPublic(field.modifiers) || Modifier.isStatic(field.modifiers)) &&
                    (field.name.contains("faultHook", ignoreCase = true) ||
                        field.name.contains("AFTER_AGGREGATE_INSERT") ||
                        field.type == Function1::class.java)
            }
        assertTrue(exposedFaultFields.isEmpty(), exposedFaultFields.joinToString())

        val exposedFaultMethods =
            implementation.declaredMethods.filter { method ->
                (Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers)) &&
                    (method.name.contains("faultHook", ignoreCase = true) ||
                        method.name.contains("AFTER_AGGREGATE_INSERT") ||
                        method.returnType == Function1::class.java ||
                        method.parameterTypes.contains(Function1::class.java))
            }
        assertTrue(exposedFaultMethods.isEmpty(), exposedFaultMethods.joinToString())
    }

    @Test
    fun failureAfterAggregateInsertRollsBackSnapshotStatusLeaseCacheAndOwner() {
        insertTrack("fault-track")
        val desired =
            snapshot(id = "fault-1", generation = 1L)
                .copy(
                    selectedTrackId = "fault-track",
                    selectedTrackStorageKey = "tracks/fault-track",
                )
        val faulting =
            WakeSchedulePreparationStoreFaultFixture.create(database) { point ->
                if (point == "AFTER_AGGREGATE_INSERT") error("injected owner-commit failure")
            }

        assertFailsWith<IllegalStateException> { faulting.prepare(desired, 900L) }

        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_status"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM track_lease"))
        assertEquals(
            0L,
            scalarLong("SELECT ref_count_cache FROM imported_track WHERE id='fault-track'"),
        )
        assertEquals(
            "LEGACY|none",
            scalarText(
                "SELECT schedule_owner || '|' || IFNULL(active_generation, 'none') FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun ownerUpdateSqliteAbortPropagatesAndRollsBackEntirePreparedAggregate() {
        insertTrack("owner-update-abort-track")
        val desired =
            snapshot(id = "owner-update-abort-run", generation = 1L)
                .copy(
                    selectedTrackId = "owner-update-abort-track",
                    selectedTrackStorageKey = "tracks/owner-update-abort-track",
                )
        val triggerName = "abort_legacy_to_preparing_wake"
        val triggerMessage = "blocker-3b-owner-update-abort"
        val writable = database.openHelper.writableDatabase
        writable.setForeignKeyConstraintsEnabled(true)
        writable.execSQL(
            """
            CREATE TRIGGER $triggerName
            BEFORE UPDATE ON migration_state
            WHEN OLD.schedule_owner = 'LEGACY'
              AND NEW.schedule_owner = 'PREPARING_WAKE'
              AND EXISTS (
                SELECT 1 FROM wake_run_snapshot
                WHERE id = 'owner-update-abort-run'
              )
              AND EXISTS (
                SELECT 1 FROM wake_run_status
                WHERE snapshot_id = 'owner-update-abort-run'
              )
              AND EXISTS (
                SELECT 1 FROM track_lease
                WHERE snapshot_id = 'owner-update-abort-run'
              )
            BEGIN
              SELECT RAISE(ABORT, '$triggerMessage');
            END
            """
                .trimIndent()
        )

        try {
            val before = wholeDatabaseFingerprint()

            // Characterization: the existing transaction should already preserve this atomicity.
            val failure =
                assertFailsWith<SQLiteException> {
                    store.prepare(desired, acquiredAtEpochMillis = 900L)
                }

            assertTrue(failure.message.orEmpty().contains(triggerMessage), failure.message)
            assertEquals(before, wholeDatabaseFingerprint())
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_status"))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM track_lease"))
            assertEquals(
                0L,
                scalarLong(
                    "SELECT ref_count_cache FROM imported_track " +
                        "WHERE id='owner-update-abort-track'"
                ),
            )
            assertEquals(
                "LEGACY|none",
                scalarText(
                    "SELECT schedule_owner || '|' || IFNULL(active_generation, 'none') " +
                        "FROM migration_state WHERE id=1"
                ),
            )
        } finally {
            writable.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
    }

    @Test
    fun unprocessedCurrentGoalBlocksNextGenerationWithoutMutation() {
        val current = snapshot(id = "current-goal", generation = 1L)
        store.prepare(current, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
        )

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "too-early-next", generation = 2L), 2_200L)
        }

        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot"))
        assertEquals(
            "PREPARED",
            scalarText("SELECT state FROM wake_run_status WHERE snapshot_id='current-goal'"),
        )
        assertEquals(
            "WAKE|1",
            scalarText(
                "SELECT schedule_owner || '|' || active_generation FROM migration_state WHERE id=1"
            ),
        )
    }

    @Test
    fun goalReachedWithoutProcessedGoalTimestampRejectsNextGenerationWithoutAnyDatabaseMutation() {
        val current = snapshot(id = "uncorrelated-goal", generation = 1L)
        store.prepare(current, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state='GOAL_REACHED', processed_goal_at=NULL " +
                "WHERE snapshot_id='uncorrelated-goal'"
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
        )
        val before = wholeDatabaseFingerprint()

        assertFailsWith<IllegalStateException> {
            store.prepare(snapshot(id = "blocked-next", generation = 2L), 2_200L)
        }

        assertEquals(before, wholeDatabaseFingerprint())
    }

    @Test
    fun migrationStateReadFailurePropagatesTheDatabaseException() {
        val desired = snapshot(id = "query-failure", generation = 1L)
        database.openHelper.writableDatabase.execSQL(
            "ALTER TABLE migration_state RENAME TO unavailable_migration_state"
        )

        assertFailsWith<SQLiteException> { store.prepare(desired, 900L) }
    }

    private fun completeCurrentGeneration(snapshotId: String) {
        completeCurrentGeneration(snapshot(id = snapshotId, generation = 1L))
    }

    private fun resetDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        store = RoomWakeSchedulePreparationStore(database)
    }

    private fun setCanonicalStatus(snapshotId: String, fixture: StatusFixture) {
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE wake_run_status
            SET state=?, processed_start_at=NULL, processed_goal_at=?,
                active_service_owner_token=NULL, execution_epoch=0,
                service_lease_owner=NULL, service_lease_expires_at=NULL, heartbeat_at=NULL,
                armed_start=0, armed_goal=0, started_at=NULL, completed_at=?, cancelled_at=?,
                failure_reason=?
            WHERE snapshot_id=?
            """
                .trimIndent(),
            arrayOf<Any?>(
                fixture.state,
                fixture.processedGoalAt,
                fixture.completedAt,
                fixture.cancelledAt,
                fixture.failureReason,
                snapshotId,
            ),
        )
    }

    private fun completeCurrentGeneration(current: WakeRunSnapshotEntity) {
        store.prepare(current, 900L)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE wake_run_status SET state='COMPLETED', completed_at=2100 WHERE snapshot_id=?",
            arrayOf<Any?>(current.id),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='WAKE' WHERE id=1"
        )
    }

    private fun insertTrack(id: String) = insertTrack(database, id)

    private fun insertTrack(target: AlarmDatabase, id: String) {
        target.openHelper.writableDatabase.execSQL(
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

    private fun runConcurrentPrepares(
        iteration: Int,
        executor: ExecutorService,
        first: Pair<AlarmDatabase, WakeRunSnapshotEntity>,
        second: Pair<AlarmDatabase, WakeRunSnapshotEntity>,
    ): List<Attempt<WakeSchedulePreparationOutcome>> {
        val start = CountDownLatch(1)
        fun attempt(target: Pair<AlarmDatabase, WakeRunSnapshotEntity>) = Callable {
            captureAttempt {
                check(start.await(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Start gate timed out at iteration $iteration"
                }
                RoomWakeSchedulePreparationStore(target.first)
                    .prepare(target.second, RACE_LEASE_ACQUIRED_AT)
            }
        }

        val firstFuture: java.util.concurrent.Future<Attempt<WakeSchedulePreparationOutcome>>
        val secondFuture: java.util.concurrent.Future<Attempt<WakeSchedulePreparationOutcome>>
        if (iteration % 2 == 0) {
            firstFuture = executor.submit(attempt(first))
            secondFuture = executor.submit(attempt(second))
        } else {
            secondFuture = executor.submit(attempt(second))
            firstFuture = executor.submit(attempt(first))
        }
        start.countDown()
        return listOf(
            firstFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            secondFuture.get(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    private fun assertUnfencedLegacyWithoutWakeAggregate(target: AlarmDatabase) {
        assertEquals(
            "LEGACY|none",
            scalarText(
                "SELECT schedule_owner || '|' || IFNULL(active_generation, 'none') " +
                    "FROM migration_state WHERE id=1",
                target,
            ),
        )
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot", target))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_run_status", target))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM track_lease", target))
    }

    private fun assertCanonicalFirstGeneration(
        target: AlarmDatabase,
        desired: WakeRunSnapshotEntity,
        trackId: String,
    ) {
        val dao = target.wakeSchedulePreparationDao()
        val migration = checkNotNull(dao.migrationState())
        assertEquals("PREPARING_WAKE", migration.scheduleOwner)
        assertEquals(1L, migration.activeGeneration)
        assertEquals(listOf(desired), dao.snapshotsForGeneration(1L))
        assertEquals(preparedWakeRunStatus(desired.id), dao.status(desired.id))
        assertEquals(
            listOf(TrackLeaseEntity(desired.id, trackId, RACE_LEASE_ACQUIRED_AT)),
            dao.leases(desired.id),
        )
        assertEquals(1L, dao.leaseCountForTrack(trackId))
        assertEquals(1L, checkNotNull(dao.importedTrack(trackId)).refCountCache)
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_snapshot", target))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM wake_run_status", target))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM track_lease", target))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM imported_track", target))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_event_dispatch", target))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM wake_recovery_anchor", target))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM schedule_outbox", target))
    }

    private fun <T> withIsolatedRaceDatabases(
        label: String,
        iteration: Int,
        block: (AlarmDatabase, AlarmDatabase, ExecutorService) -> T,
    ): T {
        val name = "wake-schedule-preparation-$label-${UUID.randomUUID()}.db"
        val executor = Executors.newFixedThreadPool(2)
        var first: AlarmDatabase? = null
        var second: AlarmDatabase? = null
        var value: Any? = null
        var primaryFailure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        var terminated = false
        var interrupted = false
        try {
            first = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
            second = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
            value = block(first, second, executor)
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            try {
                executor.shutdown()
                terminated = executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!terminated) {
                    executor.shutdownNow()
                    terminated = executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            } catch (failure: Throwable) {
                cleanupFailures += failure
                interrupted = interrupted || failure is InterruptedException
                try {
                    executor.shutdownNow()
                } catch (shutdownFailure: Throwable) {
                    cleanupFailures += shutdownFailure
                }
                try {
                    terminated =
                        executor.isTerminated ||
                            executor.awaitTermination(RACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (terminationFailure: Throwable) {
                    cleanupFailures += terminationFailure
                    interrupted = interrupted || terminationFailure is InterruptedException
                    terminated = executor.isTerminated
                }
            }
            try {
                second?.close()
            } catch (failure: Throwable) {
                cleanupFailures += failure
            } finally {
                try {
                    first?.close()
                } catch (failure: Throwable) {
                    cleanupFailures += failure
                } finally {
                    try {
                        context.deleteDatabase(name)
                    } catch (failure: Throwable) {
                        cleanupFailures += failure
                    }
                }
            }
        }

        if (interrupted) Thread.currentThread().interrupt()
        if (!terminated) {
            cleanupFailures +=
                AssertionError("race executor did not terminate for $label iteration $iteration")
        }
        primaryFailure?.let { original ->
            cleanupFailures.forEach(original::addSuppressed)
            throw original
        }
        if (cleanupFailures.isNotEmpty()) {
            val cleanupFailure = cleanupFailures.first()
            cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun snapshot(id: String, generation: Long): WakeRunSnapshotEntity =
        WakeRunSnapshotEntity(
            id = id,
            occurrenceId = "occurrence-$id",
            scheduleGeneration = generation,
            routineRevision = 1L,
            calculationRuleVersion = 1L,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-05",
            wakeStartEpochMs = 1_000L,
            goalEpochMs = 2_000L,
            lightPayload = "{}",
            musicPayload = "{}",
            vibrationPayload = "{}",
            selectedTrackId = null,
            selectedTrackStorageKey = null,
            dismissal = "CONFIRM",
            createdAt = 900L,
            installEpoch = installEpoch(),
        )

    private fun installEpoch(): String =
        scalarText("SELECT install_epoch FROM migration_state WHERE id=1")

    private fun wholeDatabaseFingerprint(
        target: AlarmDatabase = database
    ): List<Triple<String, List<String>, List<List<String?>>>> =
        target.openHelper.readableDatabase
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
                            target.openHelper.readableDatabase
                                .query("PRAGMA table_info(`$escapedTable`)")
                                .use { info ->
                                    buildList {
                                        while (info.moveToNext()) add(info.getString(1))
                                    }
                                }
                        val projection = columns.joinToString { "`${it.replace("`", "``")}`" }
                        val ordering = columns.indices.joinToString { (it + 1).toString() }
                        val rows =
                            target.openHelper.readableDatabase
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

    private fun scalarLong(sql: String, target: AlarmDatabase = database): Long =
        target.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarText(sql: String, target: AlarmDatabase = database): String =
        target.openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun <T> captureAttempt(block: () -> T): Attempt<T> =
        try {
            Attempt.success(block())
        } catch (throwable: Throwable) {
            Attempt.failure(throwable)
        }

    private data class Attempt<T>(val value: T?, val throwable: Throwable?) {
        companion object {
            fun <T> success(value: T) = Attempt(value, null)

            fun <T> failure(throwable: Throwable) = Attempt<T>(null, throwable)
        }
    }

    private data class StatusFixture(
        val state: String,
        val processedGoalAt: Long? = null,
        val completedAt: Long? = null,
        val cancelledAt: Long? = null,
        val failureReason: String? = null,
    )

    private companion object {
        const val CONCURRENT_PREPARATION_ITERATIONS = 12
        const val RACE_TIMEOUT_SECONDS = 5L
        const val RACE_LEASE_ACQUIRED_AT = 900L
    }
}
