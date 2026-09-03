/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class WakeRunStorageDaoTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: AlarmDatabase
    private lateinit var dao: WakeRunStorageDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "wake-run-storage-${UUID.randomUUID()}.db"
        database =
            AlarmDatabase.databaseBuilder(context, databaseName).allowMainThreadQueries().build()
        dao = database.wakeRunStorageDao()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun createSnapshotWithAvailableTrackCreatesPreparedAggregateAndExactLeaseCount() {
        insertTrack(id = "track-1", lifecycleState = "AVAILABLE")

        dao.createSnapshot(snapshot(id = "snapshot-1", trackId = "track-1"), acquiredAt = 1234L)

        assertEquals(1L, count("wake_run_snapshot"))
        assertEquals(1L, count("wake_run_status"))
        assertEquals("PREPARED", scalarText("SELECT state FROM wake_run_status"))
        assertEquals(1L, count("track_lease"))
        assertEquals(1L, trackRefCount("track-1"))
    }

    @Test
    fun selectedTrackIdAndStorageKeyMustDescribeTheSameAvailableTrack() {
        insertTrack(id = "track-consistent", lifecycleState = "AVAILABLE")
        val mismatched =
            listOf(
                snapshot("wrong-key", "track-consistent")
                    .copy(selectedTrackStorageKey = "tracks/another"),
                snapshot("missing-key", "track-consistent").copy(selectedTrackStorageKey = null),
                snapshot("orphan-key", null)
                    .copy(selectedTrackStorageKey = "tracks/track-consistent"),
            )

        mismatched.forEach { candidate ->
            assertFailsWith<IllegalStateException> { dao.createSnapshot(candidate, 1234L) }
        }

        assertEquals(0L, count("wake_run_snapshot"))
        assertEquals(0L, count("wake_run_status"))
        assertEquals(0L, count("track_lease"))
        assertEquals(0L, trackRefCount("track-consistent"))
    }

    @Test
    fun nullTrackCreatesPreparedSnapshotWithoutLease() {
        dao.createSnapshot(snapshot(id = "snapshot-null", trackId = null), acquiredAt = 1234L)

        assertEquals(1L, count("wake_run_snapshot"))
        assertEquals(1L, count("wake_run_status"))
        assertEquals("PREPARED", scalarText("SELECT state FROM wake_run_status"))
        assertEquals(0L, count("track_lease"))
    }

    @Test
    fun nonAvailableAndMissingTracksRejectWholeAggregateWithoutChangingCache() {
        val states = listOf("STAGING", "VALIDATED", "PENDING_DELETE", "DELETING", "DELETED")
        states.forEachIndexed { index, state ->
            val trackId = "blocked-$index"
            insertTrack(id = trackId, lifecycleState = state, refCount = 7)
            assertFailsWith<IllegalStateException> {
                dao.createSnapshot(snapshot(id = "blocked-snapshot-$index", trackId = trackId), 10)
            }
            assertEquals(7L, trackRefCount(trackId))
        }
        assertFailsWith<IllegalStateException> {
            dao.createSnapshot(snapshot(id = "missing-snapshot", trackId = "missing"), 10)
        }

        assertEquals(0L, count("wake_run_snapshot"))
        assertEquals(0L, count("wake_run_status"))
        assertEquals(0L, count("track_lease"))
    }

    @Test
    fun missingOrBrokenAvailabilityStillLeasesLifecycleAvailableSelectionForFallback() {
        insertTrack(
            id = "broken-selection",
            lifecycleState = "AVAILABLE",
            availability = "MISSING_OR_BROKEN",
        )

        dao.createSnapshot(snapshot("broken-run", "broken-selection"), 10)

        assertEquals(1L, count("track_lease"))
        assertEquals(1L, trackRefCount("broken-selection"))
    }

    @Test
    fun laterRefCountFailureRollsBackSnapshotStatusAndLease() {
        insertTrack(id = "track-fault", lifecycleState = "AVAILABLE", refCount = 0)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_ref_count_update
            BEFORE UPDATE OF ref_count_cache ON imported_track
            BEGIN SELECT RAISE(ABORT, 'injected ref count failure'); END
            """
                .trimIndent()
        )

        assertFailsWith<Exception> {
            dao.createSnapshot(snapshot(id = "snapshot-fault", trackId = "track-fault"), 10)
        }

        assertEquals(0L, count("wake_run_snapshot"))
        assertEquals(0L, count("wake_run_status"))
        assertEquals(0L, count("track_lease"))
        assertEquals(0L, trackRefCount("track-fault"))
    }

    @Test
    fun duplicateOccurrenceRollsBackWithoutChangingExistingAggregateOrCache() {
        insertTrack(id = "track-duplicate", lifecycleState = "AVAILABLE")
        val first = snapshot(id = "snapshot-first", trackId = "track-duplicate")
        dao.createSnapshot(first, 10)

        assertFailsWith<Exception> {
            dao.createSnapshot(
                snapshot(id = "snapshot-second", trackId = "track-duplicate")
                    .copy(occurrenceId = first.occurrenceId),
                20,
            )
        }

        assertEquals(1L, count("wake_run_snapshot"))
        assertEquals(1L, count("wake_run_status"))
        assertEquals(1L, count("track_lease"))
        assertEquals(1L, trackRefCount("track-duplicate"))
    }

    @Test
    fun concurrentCreatesRemainExactAcrossMultipleTracks() {
        insertTrack(id = "track-a", lifecycleState = "AVAILABLE")
        insertTrack(id = "track-b", lifecycleState = "AVAILABLE")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures =
                executor.invokeAll(
                    (0 until 20).map { index ->
                        Callable {
                            val trackId = if (index % 2 == 0) "track-a" else "track-b"
                            dao.createSnapshot(
                                snapshot("concurrent-$index", trackId),
                                index.toLong(),
                            )
                        }
                    }
                )
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(20L, count("wake_run_snapshot"))
        assertEquals(20L, count("track_lease"))
        assertEquals(10L, trackRefCount("track-a"))
        assertEquals(10L, trackRefCount("track-b"))
    }

    @Test
    fun explicitSnapshotGcCascadesDependentsAndRecomputesEveryAffectedTrack() {
        insertTrack(id = "gc-a", lifecycleState = "AVAILABLE")
        insertTrack(id = "gc-b", lifecycleState = "AVAILABLE")
        dao.createSnapshot(snapshot("gc-a-1", "gc-a"), 1)
        dao.createSnapshot(snapshot("gc-a-2", "gc-a"), 2)
        dao.createSnapshot(snapshot("gc-b-1", "gc-b"), 3)
        insertEvent("gc-a-1")
        insertEvent("gc-a-2")
        insertEvent("gc-b-1")

        val deleted = dao.deleteSnapshots(listOf("gc-a-1", "gc-b-1", "missing"))

        assertEquals(2, deleted)
        assertEquals(1L, count("wake_run_snapshot"))
        assertEquals(1L, count("wake_run_status"))
        assertEquals(1L, count("wake_event_dispatch"))
        assertEquals(1L, count("track_lease"))
        assertEquals(1L, trackRefCount("gc-a"))
        assertEquals(0L, trackRefCount("gc-b"))
    }

    @Test
    fun hugeDuplicateGcInputIsDeduplicatedBeforeSqlBinding() {
        assertEquals(0, dao.deleteSnapshots(List(40_000) { "missing" }))
        assertEquals(0L, count("wake_run_snapshot"))
    }

    @Test
    fun moreThanOneThousandUniqueSnapshotsAreDeletedInOneAtomicBatchedGc() {
        val ids = (0 until 1_100).map { "bulk-$it" }
        ids.forEach { dao.createSnapshot(snapshot(it, null), it.removePrefix("bulk-").toLong()) }

        assertEquals(1_100, dao.deleteSnapshots(ids))
        assertEquals(0L, count("wake_run_snapshot"))
        assertEquals(0L, count("wake_run_status"))
    }

    @Test
    fun repeatedAndEmptyGcAreIdempotent() {
        insertTrack(id = "gc-idempotent", lifecycleState = "AVAILABLE")
        dao.createSnapshot(snapshot("gc-once", "gc-idempotent"), 1)

        assertEquals(1, dao.deleteSnapshots(listOf("gc-once", "missing")))
        assertEquals(0, dao.deleteSnapshots(listOf("gc-once", "missing")))
        assertEquals(0, dao.deleteSnapshots(emptyList()))
        assertEquals(0L, count("wake_run_snapshot"))
        assertEquals(0L, count("wake_run_status"))
        assertEquals(0L, count("track_lease"))
        assertEquals(0L, trackRefCount("gc-idempotent"))
    }

    @Test
    fun gcRefCountFailureRollsBackSnapshotAndCascadedRows() {
        insertTrack(id = "gc-fault", lifecycleState = "AVAILABLE")
        dao.createSnapshot(snapshot("gc-fault-snapshot", "gc-fault"), 1)
        insertEvent("gc-fault-snapshot")
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_gc_ref_count_update
            BEFORE UPDATE OF ref_count_cache ON imported_track
            BEGIN SELECT RAISE(ABORT, 'injected gc ref count failure'); END
            """
                .trimIndent()
        )

        assertFailsWith<Exception> { dao.deleteSnapshots(listOf("gc-fault-snapshot")) }

        assertEquals(1L, count("wake_run_snapshot"))
        assertEquals(1L, count("wake_run_status"))
        assertEquals(1L, count("wake_event_dispatch"))
        assertEquals(1L, count("track_lease"))
        assertEquals(1L, trackRefCount("gc-fault"))
    }

    @Test
    fun startupReconstructionRepairsAllCorruptedCachesAndReportsMismatchCount() {
        insertTrack(id = "repair-a", lifecycleState = "AVAILABLE")
        insertTrack(id = "repair-b", lifecycleState = "AVAILABLE", refCount = 4)
        insertTrack(id = "repair-c", lifecycleState = "AVAILABLE")
        dao.createSnapshot(snapshot("repair-a-1", "repair-a"), 1)
        dao.createSnapshot(snapshot("repair-a-2", "repair-a"), 2)
        dao.createSnapshot(snapshot("repair-c-1", "repair-c"), 3)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET ref_count_cache = 99 WHERE id = 'repair-a'"
        )

        assertEquals(2, dao.reconstructTrackRefCountsAtStartup())
        assertEquals(2L, trackRefCount("repair-a"))
        assertEquals(0L, trackRefCount("repair-b"))
        assertEquals(1L, trackRefCount("repair-c"))
        assertEquals(0, dao.reconstructTrackRefCountsAtStartup())
    }

    @Test
    fun availableTrackWithoutLeasesCanBeClaimedWithProposedTokenAndRepairsCache() {
        insertTrack(id = "claimable", lifecycleState = "AVAILABLE", refCount = 9)

        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("claimable", "delete-attempt-1"),
        )

        assertEquals("DELETING", trackLifecycle("claimable"))
        assertEquals("delete-attempt-1", trackDeletionToken("claimable"))
        assertEquals(0L, trackRefCount("claimable"))
    }

    @Test
    fun sameTrackAndTokenClaimRetryReportsAlreadyOwned() {
        insertTrack(id = "retry-owner", lifecycleState = "AVAILABLE")

        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("retry-owner", "retry-token"),
        )
        assertEquals(
            DeletionClaimResult.ALREADY_OWNED,
            dao.claimTrackDeletion("retry-owner", "retry-token"),
        )
        assertEquals("DELETING", trackLifecycle("retry-owner"))
        assertEquals("retry-token", trackDeletionToken("retry-owner"))
    }

    @Test
    fun actualLeaseBlocksClaimWithoutChangingLifecycleOrTokenAndRepairsCache() {
        insertTrack(id = "leased", lifecycleState = "AVAILABLE")
        dao.createSnapshot(snapshot("lease-owner", "leased"), 1)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET ref_count_cache = 8 WHERE id = 'leased'"
        )

        assertEquals(
            DeletionClaimResult.REJECTED,
            dao.claimTrackDeletion("leased", "blocked-token"),
        )

        assertEquals("AVAILABLE", trackLifecycle("leased"))
        assertEquals(null, trackDeletionToken("leased"))
        assertEquals(1L, trackRefCount("leased"))
    }

    @Test
    fun blankDeletionTokenIsRejectedBeforeAnyWrite() {
        listOf("", " \t\n").forEachIndexed { index, token ->
            val trackId = "blank-token-$index"
            insertTrack(id = trackId, lifecycleState = "AVAILABLE", refCount = 7)

            assertFailsWith<IllegalArgumentException> { dao.claimTrackDeletion(trackId, token) }

            assertEquals("AVAILABLE", trackLifecycle(trackId))
            assertEquals(null, trackDeletionToken(trackId))
            assertEquals(7L, trackRefCount(trackId))
        }
    }

    @Test
    fun oversizedDeletionTokenIsRejectedBeforeAnyWrite() {
        insertTrack(id = "oversized-token", lifecycleState = "AVAILABLE", refCount = 7)

        assertFailsWith<IllegalArgumentException> {
            dao.claimTrackDeletion("oversized-token", "x".repeat(129))
        }

        assertEquals("AVAILABLE", trackLifecycle("oversized-token"))
        assertEquals(null, trackDeletionToken("oversized-token"))
        assertEquals(7L, trackRefCount("oversized-token"))
    }

    @Test
    fun deletionTokenAlreadyUsedByDeletingTrackIsRejectedAtomically() {
        insertTrack(id = "token-owner", lifecycleState = "AVAILABLE")
        insertTrack(id = "token-contender", lifecycleState = "AVAILABLE", refCount = 6)
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("token-owner", "shared-token"),
        )

        assertEquals(
            DeletionClaimResult.REJECTED,
            dao.claimTrackDeletion("token-contender", "shared-token"),
        )

        assertEquals("DELETING", trackLifecycle("token-owner"))
        assertEquals("shared-token", trackDeletionToken("token-owner"))
        assertEquals("AVAILABLE", trackLifecycle("token-contender"))
        assertEquals(null, trackDeletionToken("token-contender"))
        assertEquals(0L, trackRefCount("token-contender"))
    }

    @Test
    fun pendingDeleteIsEligibleWhileAllOtherLifecycleStatesAreRejected() {
        insertTrack(id = "pending", lifecycleState = "PENDING_DELETE", refCount = 4)
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("pending", "pending-token"),
        )
        assertEquals("DELETING", trackLifecycle("pending"))
        assertEquals("pending-token", trackDeletionToken("pending"))
        assertEquals(0L, trackRefCount("pending"))

        listOf("STAGING", "VALIDATED", "DELETING", "DELETED").forEachIndexed { index, state ->
            val trackId = "ineligible-$index"
            insertTrack(id = trackId, lifecycleState = state, refCount = 5)
            val originalToken = trackDeletionToken(trackId)

            assertEquals(
                DeletionClaimResult.REJECTED,
                dao.claimTrackDeletion(trackId, "ineligible-token-$index"),
            )

            assertEquals(state, trackLifecycle(trackId))
            assertEquals(originalToken, trackDeletionToken(trackId))
            assertEquals(0L, trackRefCount(trackId))
        }
        assertEquals(
            DeletionClaimResult.REJECTED,
            dao.claimTrackDeletion("missing-track", "missing-token"),
        )
    }

    @Test
    fun exactTokenFinalizationMarksRowDeletedClearsTokenAndRepairsCache() {
        insertTrack(id = "finalize", lifecycleState = "AVAILABLE")
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("finalize", "finalize-token"),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE imported_track SET ref_count_cache = 11 WHERE id = 'finalize'"
        )

        assertEquals(true, dao.finalizeTrackDeletion("finalize", "finalize-token"))

        assertEquals("DELETED", trackLifecycle("finalize"))
        assertEquals(null, trackDeletionToken("finalize"))
        assertEquals(0L, trackRefCount("finalize"))
        assertEquals(1L, count("imported_track"))
    }

    @Test
    fun wrongAndStaleFinalizationTokensAreNoOps() {
        insertTrack(id = "token-cas", lifecycleState = "AVAILABLE")
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("token-cas", "current-token"),
        )

        assertEquals(false, dao.finalizeTrackDeletion("token-cas", "wrong-token"))
        assertEquals("DELETING", trackLifecycle("token-cas"))
        assertEquals("current-token", trackDeletionToken("token-cas"))

        assertEquals(true, dao.finalizeTrackDeletion("token-cas", "current-token"))
        assertEquals(false, dao.finalizeTrackDeletion("token-cas", "current-token"))
        assertEquals("DELETED", trackLifecycle("token-cas"))
        assertEquals(null, trackDeletionToken("token-cas"))
        assertEquals(false, dao.finalizeTrackDeletion("missing", "current-token"))
    }

    @Test
    fun sameHashCanCompleteTwoDeleteCyclesButCannotHaveTwoLiveRows() {
        insertTrack(id = "hash-first", lifecycleState = "AVAILABLE", contentHash = "same-hash")
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("hash-first", "hash-token-1"),
        )
        assertEquals(true, dao.finalizeTrackDeletion("hash-first", "hash-token-1"))

        insertTrack(id = "hash-second", lifecycleState = "AVAILABLE", contentHash = "same-hash")
        assertFailsWith<Exception> {
            insertTrack(id = "hash-third", lifecycleState = "STAGING", contentHash = "same-hash")
        }
        assertEquals(
            DeletionClaimResult.CLAIMED,
            dao.claimTrackDeletion("hash-second", "hash-token-2"),
        )
        assertEquals(true, dao.finalizeTrackDeletion("hash-second", "hash-token-2"))

        assertEquals("DELETED", trackLifecycle("hash-first"))
        assertEquals("DELETED", trackLifecycle("hash-second"))
        assertEquals(2L, count("imported_track"))
    }

    @Test
    fun concurrentSnapshotCreateAndDeleteClaimAlwaysSerializeToOneValidOutcome() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { index ->
                val trackId = "race-track-$index"
                insertTrack(id = trackId, lifecycleState = "AVAILABLE", refCount = 12)
                val start = CountDownLatch(1)
                val create =
                    executor.submit(
                        Callable {
                            start.await()
                            runCatching {
                                dao.createSnapshot(
                                    snapshot("race-snapshot-$index", trackId),
                                    index.toLong(),
                                )
                            }
                        }
                    )
                val claim =
                    executor.submit(
                        Callable {
                            start.await()
                            dao.claimTrackDeletion(trackId, "race-token-$index")
                        }
                    )

                start.countDown()
                val createResult = create.get(10, TimeUnit.SECONDS)
                val claimResult = claim.get(10, TimeUnit.SECONDS)
                val createSucceeded = createResult.isSuccess
                val claimSucceeded = claimResult == DeletionClaimResult.CLAIMED
                assertEquals(1, listOf(createSucceeded, claimSucceeded).count { it })

                if (createSucceeded) {
                    assertEquals(DeletionClaimResult.REJECTED, claimResult)
                    assertEquals("AVAILABLE", trackLifecycle(trackId))
                    assertEquals(null, trackDeletionToken(trackId))
                    assertEquals(1L, actualLeaseCount(trackId))
                    assertEquals(1L, trackRefCount(trackId))
                } else {
                    val failure = createResult.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertTrue(failure.message.orEmpty().contains("not AVAILABLE"))
                    assertEquals(DeletionClaimResult.CLAIMED, claimResult)
                    assertEquals("DELETING", trackLifecycle(trackId))
                    assertEquals("race-token-$index", trackDeletionToken(trackId))
                    assertEquals(0L, actualLeaseCount(trackId))
                    assertEquals(0L, trackRefCount(trackId))
                }
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentClaimsCannotReuseTheSameDeletionToken() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(25) { index ->
                val firstId = "shared-token-first-$index"
                val secondId = "shared-token-second-$index"
                insertTrack(firstId, "AVAILABLE")
                insertTrack(secondId, "AVAILABLE")
                val start = CountDownLatch(1)
                val claims =
                    listOf(firstId, secondId).map { trackId ->
                        executor.submit(
                            Callable {
                                start.await()
                                dao.claimTrackDeletion(trackId, "concurrent-shared-token-$index")
                            }
                        )
                    }

                start.countDown()
                assertEquals(
                    1,
                    claims.count {
                        it.get(10, TimeUnit.SECONDS) == DeletionClaimResult.CLAIMED
                    },
                )
                assertEquals(
                    1L,
                    scalarLong(
                        """
                        SELECT COUNT(*) FROM imported_track
                        WHERE lifecycle_state = 'DELETING'
                          AND deletion_token = ?
                        """
                            .trimIndent(),
                        arrayOf("concurrent-shared-token-$index"),
                    ),
                )
            }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun deletionStateUpdateFailureRollsBackPriorCacheRepair() {
        insertTrack(id = "claim-fault", lifecycleState = "AVAILABLE", refCount = 9)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_deletion_claim
            BEFORE UPDATE OF lifecycle_state ON imported_track
            WHEN NEW.lifecycle_state = 'DELETING'
            BEGIN SELECT RAISE(ABORT, 'injected deletion claim failure'); END
            """
                .trimIndent()
        )

        assertFailsWith<Exception> { dao.claimTrackDeletion("claim-fault", "fault-token") }

        assertEquals("AVAILABLE", trackLifecycle("claim-fault"))
        assertEquals(null, trackDeletionToken("claim-fault"))
        assertEquals(9L, trackRefCount("claim-fault"))
    }

    private fun insertEvent(snapshotId: String) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO wake_event_dispatch(
              event_key, snapshot_id, event_kind, expected_trigger_epoch_ms, state
            ) VALUES (?, ?, 'START', 1000, 'RECEIVED')
            """
                .trimIndent(),
            arrayOf<Any?>("event-$snapshotId", snapshotId),
        )
    }

    private fun snapshot(id: String, trackId: String?): WakeRunSnapshotEntity =
        WakeRunSnapshotEntity(
            id = id,
            occurrenceId = "occurrence-$id",
            scheduleGeneration = 1,
            routineRevision = 1,
            calculationRuleVersion = 1,
            zoneId = "Asia/Seoul",
            occurrenceLocalDate = "2026-09-04",
            wakeStartEpochMs = 1_000,
            goalEpochMs = 2_000,
            lightPayload = "{}",
            musicPayload = "{}",
            vibrationPayload = "{}",
            selectedTrackId = trackId,
            selectedTrackStorageKey = trackId?.let { "tracks/$it" },
            dismissal = "CONFIRM",
            createdAt = 900,
            installEpoch = "install-1",
        )

    private fun insertTrack(
        id: String,
        lifecycleState: String,
        refCount: Long = 0,
        availability: String = "AVAILABLE",
        contentHash: String = "hash-$id",
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO imported_track(
              id, storage_key, title, artist, duration_ms, mime_type, content_hash,
              lifecycle_state, availability, deletion_token, ref_count_cache, added_at
            ) VALUES (?, ?, ?, NULL, 1000, 'audio/mpeg', ?, ?, ?, ?, ?, 1)
            """
                .trimIndent(),
            arrayOf<Any?>(
                id,
                "tracks/$id",
                id,
                contentHash,
                lifecycleState,
                availability,
                if (lifecycleState == "DELETING") "delete-$id" else null,
                refCount,
            ),
        )
    }

    private fun count(table: String): Long = scalarLong("SELECT COUNT(*) FROM $table")

    private fun trackRefCount(id: String): Long =
        scalarLong("SELECT ref_count_cache FROM imported_track WHERE id = ?", arrayOf(id))

    private fun actualLeaseCount(id: String): Long =
        scalarLong("SELECT COUNT(*) FROM track_lease WHERE track_id = ?", arrayOf(id))

    private fun trackLifecycle(id: String): String =
        scalarText("SELECT lifecycle_state FROM imported_track WHERE id = ?", arrayOf(id))

    private fun trackDeletionToken(id: String): String? =
        database.openHelper.readableDatabase
            .query("SELECT deletion_token FROM imported_track WHERE id = ?", arrayOf(id))
            .use { cursor ->
                check(cursor.moveToFirst())
                if (cursor.isNull(0)) null else cursor.getString(0)
            }

    private fun scalarLong(sql: String, args: Array<out Any?> = emptyArray()): Long =
        database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun scalarText(sql: String, args: Array<out Any?> = emptyArray()): String =
        database.openHelper.readableDatabase.query(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
}
