/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacyAudioBootstrapCommitterTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AlarmDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "legacy-commit-${UUID.randomUUID()}.db"
        db = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun deterministicTrackIdIsRepeatableBoundedAndCanonicallySeparatesFields() {
        val first = legacyImportedTrackId("a", "bc", "d", "ef")
        val repeated = legacyImportedTrackId("a", "bc", "d", "ef")
        val boundaryShift = legacyImportedTrackId("ab", "c", "d", "ef")

        assertEquals(first, repeated)
        assertNotEquals(first, boundaryShift)
        assertTrue(first.matches(Regex("legacy-track-v1:[0-9a-f]{64}")))
        assertTrue(first.toByteArray(Charsets.US_ASCII).size <= 128)
        assertContentEquals(
            legacyImportedTrackIdentityPayload("a", "bc", "d", "ef"),
            legacyImportedTrackIdentityPayload("a", "bc", "d", "ef"),
        )
        assertNotEquals(
            legacyImportedTrackIdentityPayload("a", "bc", "d", "ef").toList(),
            legacyImportedTrackIdentityPayload("ab", "c", "d", "ef").toList(),
        )
    }

    @Test
    fun validatedTrackAndCommittedPhaseAreWrittenTogether() {
        val fixture = fixture()

        val committed =
            RoomLegacyAudioBootstrapCommitter(db)
                .commit(
                    fixture.descriptor,
                    fixture.result,
                    addedAt = 1234L,
                )

        assertEquals(fixture.expectedTrack.copy(addedAt = 1234L), committed.track)
        assertEquals(committed.track, db.legacyBootstrapDao().importedTrackById(committed.track.id))
        assertEquals("COMMITTED", phase())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun committedRetryReturnsExactExistingTrackAndKeepsOriginalAddedAt() {
        val fixture = fixture()
        val first =
            RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 1234)

        val retry =
            RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 9999)

        assertEquals(first, retry)
        assertEquals(1234, retry.track.addedAt)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun transactionFaultsRollbackBothTrackAndPhase() {
        listOf(
                LegacyAudioCommitFaultPoint.BEFORE_INSERT,
                LegacyAudioCommitFaultPoint.AFTER_INSERT_BEFORE_CAS,
                LegacyAudioCommitFaultPoint.AFTER_CAS_BEFORE_RETURN,
            )
            .forEach { point ->
                resetTables()
                val fixture = fixture()
                assertFailsWith<TestFault>(point.name) {
                    RoomLegacyAudioBootstrapCommitter(db) { reached ->
                            if (reached == point) throw TestFault()
                        }
                        .commit(fixture.descriptor, fixture.result, 1234)
                }
                assertEquals("VALIDATED", phase(), point.name)
                assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"), point.name)
            }
    }

    @Test
    fun responseLossAfterCommitConvergesOnRetry() {
        val fixture = fixture()
        assertFailsWith<TestFault> {
            RoomLegacyAudioBootstrapCommitter(db) { point ->
                    if (
                        point == LegacyAudioCommitFaultPoint.AFTER_TRANSACTION_COMMIT_BEFORE_RETURN
                    ) {
                        throw TestFault()
                    }
                }
                .commit(fixture.descriptor, fixture.result, 1234)
        }
        assertEquals("COMMITTED", phase())

        val retry =
            RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 9999)

        assertEquals(1234, retry.track.addedAt)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun invalidResultFieldsAndAddedAtFailBeforeWrites() {
        val base = fixture()
        val invalid =
            listOf(
                base.result.copy(
                    copyEvidence = base.result.copyEvidence.copy(storageKey = "wrong")
                ),
                base.result.copy(
                    copyEvidence = base.result.copyEvidence.copy(sha256 = "A".repeat(64))
                ),
                base.result.copy(copyEvidence = base.result.copyEvidence.copy(sizeBytes = 0)),
                base.result.copy(
                    copyEvidence =
                        base.result.copyEvidence.copy(sizeBytes = MAX_LEGACY_AUDIO_BYTES + 1)
                ),
                base.result.copy(metadata = base.result.metadata.copy(title = " ")),
                base.result.copy(metadata = base.result.metadata.copy(title = "가".repeat(1366))),
                base.result.copy(metadata = base.result.metadata.copy(artist = "가".repeat(1366))),
                base.result.copy(metadata = base.result.metadata.copy(durationMillis = 0)),
                base.result.copy(
                    metadata =
                        base.result.metadata.copy(durationMillis = MAX_AUDIO_DURATION_MILLIS + 1)
                ),
                base.result.copy(metadata = base.result.metadata.copy(mime = "")),
                base.result.copy(metadata = base.result.metadata.copy(mime = "x".repeat(257))),
            )
        invalid.forEachIndexed { index, result ->
            assertFailsWith<IllegalArgumentException>("invalid $index") {
                RoomLegacyAudioBootstrapCommitter(db).commit(base.descriptor, result, 1234)
            }
            assertEquals("VALIDATED", phase(), "invalid $index")
            assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"), "invalid $index")
        }
        assertFailsWith<IllegalArgumentException> {
            RoomLegacyAudioBootstrapCommitter(db).commit(base.descriptor, base.result, -1)
        }
    }

    @Test
    fun wrongPhaseOrExactStoredIdentityFailsClosed() {
        listOf("DISCOVERED", "COPIED").forEach { wrongPhase ->
            resetTables()
            val fixture = fixture(wrongPhase)
            assertFailsWith<IllegalStateException> {
                RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 1)
            }
            assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"))
        }
        listOf(
                "schedule_owner='WAKE'",
                "attempt_token='${"b".repeat(64)}'",
                "source_fingerprint='legacy-canonical-v1:${"b".repeat(64)}:${"c".repeat(64)}'",
                "target_storage_key='wrong'",
            )
            .forEach { mutation ->
                resetTables()
                val fixture = fixture()
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE migration_state SET $mutation WHERE id=1"
                )
                assertFailsWith<IllegalStateException>(mutation) {
                    RoomLegacyAudioBootstrapCommitter(db)
                        .commit(fixture.descriptor, fixture.result, 1)
                }
                assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"), mutation)
            }
    }

    @Test
    fun currentCanonicalSourceChangeInsideTransactionRollsBack() {
        val checks = AtomicInteger()
        val fixture =
            fixture(
                sourceSnapshot = {
                    if (checks.incrementAndGet() == 1) it
                    else it.copy(sourceFingerprint = "changed")
                }
            )

        assertFailsWith<IllegalArgumentException> {
            RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 1)
        }

        assertEquals(2, checks.get())
        assertEquals("VALIDATED", phase())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun deterministicIdStorageKeyAndLiveHashConflictsFailClosed() {
        val fixture = fixture()
        val expected = fixture.expectedTrack.copy(addedAt = 7)
        val conflicts =
            listOf(
                expected.copy(title = "Different"),
                expected.copy(id = "other-id", contentHash = "b".repeat(64)),
                expected.copy(id = "other-id", storageKey = "other-key"),
            )
        conflicts.forEachIndexed { index, conflict ->
            resetTables()
            fixture("VALIDATED")
            val actualConflict =
                when (index) {
                    1 -> conflict.copy(storageKey = expected.storageKey)
                    2 -> conflict.copy(contentHash = expected.contentHash)
                    else -> conflict
                }
            assertTrue(db.legacyBootstrapDao().insertImportedTrack(actualConflict) != -1L)
            assertFailsWith<IllegalStateException>("conflict $index") {
                RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 7)
            }
            assertEquals("VALIDATED", phase(), "conflict $index")
            assertEquals(
                actualConflict,
                db.legacyBootstrapDao().importedTrackById(actualConflict.id),
            )
            assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
        }
    }

    @Test
    fun validatedInsertConflictWithDifferentAddedAtFailsClosed() {
        val fixture = fixture()
        val preexisting = fixture.expectedTrack.copy(addedAt = 1111)
        assertTrue(db.legacyBootstrapDao().insertImportedTrack(preexisting) != -1L)

        assertFailsWith<IllegalStateException> {
            RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 2222)
        }

        assertEquals("VALIDATED", phase())
        assertEquals(preexisting, db.legacyBootstrapDao().importedTrackById(preexisting.id))
        assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun committedMissingOrMismatchingTrackFailsClosed() {
        val missing = fixture("COMMITTED")
        assertFailsWith<IllegalStateException> {
            RoomLegacyAudioBootstrapCommitter(db).commit(missing.descriptor, missing.result, 1)
        }
        resetTables()
        val mismatch = fixture("COMMITTED")
        db.legacyBootstrapDao()
            .insertImportedTrack(mismatch.expectedTrack.copy(title = "wrong", addedAt = 1))
        assertFailsWith<IllegalStateException> {
            RoomLegacyAudioBootstrapCommitter(db).commit(mismatch.descriptor, mismatch.result, 2)
        }
        assertEquals("COMMITTED", phase())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun commitDoesNotMutateAlarmManifestOrSourceFile() {
        val fixture = fixture()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO alarms(id,hour,minute,isActive,repeatDays,ringtoneUri,volume,vibrationEnabled,skippedOccurrenceDay) " +
                "VALUES(41,6,45,1,'','legacy',1.0,1,NULL)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO legacy_migration_manifest(legacy_alarm_id,goal_epoch_ms,pending_intent_identity,proposed_disposition,user_confirmed,terminal_at) " +
                "VALUES(41,1000,'identity','SELECT_AS_WAKE',0,NULL)"
        )
        val source =
            java.io.File(context.cacheDir, "legacy-source-${UUID.randomUUID()}").apply {
                writeText("old")
            }
        val alarmBefore = text("SELECT id || ':' || ringtoneUri FROM alarms WHERE id=41")
        val manifestBefore =
            text("SELECT legacy_alarm_id || ':' || user_confirmed FROM legacy_migration_manifest")

        RoomLegacyAudioBootstrapCommitter(db).commit(fixture.descriptor, fixture.result, 1)

        assertEquals(alarmBefore, text("SELECT id || ':' || ringtoneUri FROM alarms WHERE id=41"))
        assertEquals(
            manifestBefore,
            text("SELECT legacy_alarm_id || ':' || user_confirmed FROM legacy_migration_manifest"),
        )
        assertEquals("old", source.readText())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM wake_routine"))
        source.delete()
    }

    @Test
    fun concurrentCallersConvergeToOneCommittedTrack() {
        val fixture = fixture()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1L..2L).map { addedAt ->
                    executor.submit<LegacyAudioBootstrapCommitResult> {
                        RoomLegacyAudioBootstrapCommitter(db)
                            .commit(
                                fixture.descriptor,
                                fixture.result,
                                addedAt,
                            )
                    }
                }
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(1L, scalar("SELECT COUNT(*) FROM imported_track"))
            assertEquals("COMMITTED", phase())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun fixture(
        phase: String = "VALIDATED",
        sourceSnapshot: (LegacyAudioSourceSnapshot) -> LegacyAudioSourceSnapshot = { it },
    ): Fixture {
        val epoch = db.legacyBootstrapDao().migrationState()!!.installEpoch
        val fingerprint = legacyDiscoverySourceFingerprint("commit-source", emptyList())
        val token = legacyDiscoveryAttemptToken(epoch, fingerprint)
        val key = "bootstrap/$token/legacy-audio"
        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET source_fingerprint=?, target_storage_key=?, " +
                "bootstrap_phase=?, attempt_token=? WHERE id=1",
            arrayOf(fingerprint, key, phase, token),
        )
        val descriptor =
            LegacyAudioBootstrapDescriptorFixtureFactory.create(
                LegacyAudioBootstrapEvidence("LEGACY", epoch, fingerprint, token, key, phase),
                "legacy/audio.mp3",
            ) {
                sourceSnapshot(LegacyAudioSourceSnapshot("legacy/audio.mp3", fingerprint))
            }
        val result =
            LegacyAudioBootstrapResult(
                LegacyAudioCopyEvidence(key, "a".repeat(64), 4096),
                AudioValidationMetadata("Morning Song", "Artist", 60_000, "audio/mpeg"),
            )
        val expected =
            ImportedTrackEntity(
                legacyImportedTrackId(epoch, fingerprint, token, key),
                key,
                "Morning Song",
                "Artist",
                60_000,
                "audio/mpeg",
                "a".repeat(64),
                "AVAILABLE",
                "AVAILABLE",
                null,
                0,
                0,
            )
        return Fixture(descriptor, result, expected)
    }

    private fun phase(): String =
        db.openHelper.writableDatabase
            .query("SELECT bootstrap_phase FROM migration_state WHERE id=1")
            .use {
                it.moveToFirst()
                it.getString(0)
            }

    private fun scalar(sql: String): Long =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun text(sql: String): String =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }

    private fun resetTables() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM imported_track")
        db.openHelper.writableDatabase.execSQL("UPDATE migration_state SET schedule_owner='LEGACY'")
    }

    private data class Fixture(
        val descriptor: LegacyAudioBootstrapDescriptor,
        val result: LegacyAudioBootstrapResult,
        val expectedTrack: ImportedTrackEntity,
    )

    private class TestFault : RuntimeException()
}
