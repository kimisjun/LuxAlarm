/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.channels.FileChannel
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertContentEquals
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
class RoomLegacyDiscoveryStoreTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var db: AlarmDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "legacy-discovery-${UUID.randomUUID()}.db"
        db = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun realRoomStateAndFilesystemConvergeAfterEveryDurableProcessDeathPoint() {
        val durablePoints =
            listOf(
                LegacyAudioBootstrapFaultPoint.AFTER_TEMP_CREATE,
                LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY,
                LegacyAudioBootstrapFaultPoint.AFTER_FSYNC,
                LegacyAudioBootstrapFaultPoint.BEFORE_DESTINATION_PUBLISH,
                LegacyAudioBootstrapFaultPoint.AFTER_RENAME,
                LegacyAudioBootstrapFaultPoint.BEFORE_COPY_SIDECAR_PUBLISH,
                LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH,
                LegacyAudioBootstrapFaultPoint.AFTER_COPIED_STATE_WRITE,
                LegacyAudioBootstrapFaultPoint.AFTER_DECODE,
                LegacyAudioBootstrapFaultPoint.BEFORE_METADATA_SIDECAR_PUBLISH,
                LegacyAudioBootstrapFaultPoint.AFTER_METADATA_SIDECAR_PUBLISH,
                LegacyAudioBootstrapFaultPoint.AFTER_VALIDATED_STATE_WRITE,
            )
        durablePoints.forEach { point ->
            db.close()
            context.deleteDatabase(name)
            name = "legacy-room-crash-${point.name}-${UUID.randomUUID()}.db"
            db = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
            db.openHelper.writableDatabase
            insertLegacyAlarm()
            val alarmBefore = alarmEvidence()
            val filesDir =
                File(context.cacheDir, "legacy-room-${UUID.randomUUID()}").apply { mkdirs() }
            val sourceBytes = ByteArray(96 * 1024) { (it % 251).toByte() }
            val source =
                File(filesDir, "legacy/audio.mp3").apply {
                    parentFile.mkdirs()
                    writeBytes(sourceBytes)
                }
            val store = RoomLegacyDiscoveryStore(db)
            val discovered =
                persistence("room-crash-${point.name}", store.requireReady().installEpoch)
            val token = discovered.attemptToken
            val audioDiscovery = discovered.copy(targetStorageKey = "bootstrap/$token/legacy-audio")
            store.persistDiscovery(audioDiscovery) { audioDiscovery }
            val firstDescriptor = descriptorFromRoom(source)
            val attemptDir =
                File(filesDir, firstDescriptor.targetStorageKey).parentFile.apply { mkdirs() }
            val unrelated = File(attemptDir, "unrelated.keep").apply { writeText("keep") }

            var injected = false
            val crash = LegacyAudioBootstrapFaultInjector { reached ->
                if (!injected && reached == point) {
                    injected = true
                    throw LegacyAudioBootstrapProcessDeath()
                }
            }
            val metadata = AudioValidationMetadata("Morning", "Artist", 1234, "audio/mpeg")
            val capabilityFactory = AnchoredTestCapabilityFactory()

            assertFailsWith<LegacyAudioBootstrapProcessDeath>(point.name) {
                legacyAudioTestReconciler(
                        filesDir,
                        RoomLegacyAudioBootstrapStatePort(db),
                        LegacyAudioDecoder { _: FileChannel -> metadata },
                        crash,
                        capabilities = capabilityFactory,
                    )
                    .reconcile(firstDescriptor)
            }
            assertTrue(injected, point.name)
            val expectedCrashPhase =
                when (point) {
                    LegacyAudioBootstrapFaultPoint.AFTER_COPIED_STATE_WRITE,
                    LegacyAudioBootstrapFaultPoint.AFTER_DECODE,
                    LegacyAudioBootstrapFaultPoint.BEFORE_METADATA_SIDECAR_PUBLISH,
                    LegacyAudioBootstrapFaultPoint.AFTER_METADATA_SIDECAR_PUBLISH -> "COPIED"
                    LegacyAudioBootstrapFaultPoint.AFTER_VALIDATED_STATE_WRITE -> "VALIDATED"
                    else -> "DISCOVERED"
                }
            assertEquals(
                expectedCrashPhase,
                text("SELECT bootstrap_phase FROM migration_state WHERE id=1"),
                point.name,
            )
            assertTrue(
                attemptDir.listFiles().orEmpty().none {
                    it.name.endsWith(".copying") || it.name.endsWith(".writing")
                },
                "UUID temporary residue: $point",
            )
            assertTrue(
                attemptDir.listFiles().orEmpty().count { it.name == "legacy-audio" } <= 1,
                "more than one deterministic large slot: $point",
            )

            db.close()
            db = AlarmDatabase.databaseBuilder(context, name).allowMainThreadQueries().build()
            db.openHelper.writableDatabase
            val freshDescriptor = descriptorFromRoom(source)
            val result =
                legacyAudioTestReconciler(
                        filesDir,
                        RoomLegacyAudioBootstrapStatePort(db),
                        LegacyAudioDecoder { _: FileChannel -> metadata },
                        capabilities = capabilityFactory,
                    )
                    .reconcile(freshDescriptor)

            assertEquals(
                "VALIDATED",
                text("SELECT bootstrap_phase FROM migration_state WHERE id=1"),
                point.name,
            )
            assertEquals(alarmBefore, alarmEvidence(), point.name)
            assertContentEquals(
                sourceBytes,
                File(filesDir, freshDescriptor.targetStorageKey).readBytes(),
                point.name,
            )
            assertEquals(sourceBytes.size.toLong(), result.copyEvidence.sizeBytes, point.name)
            assertEquals("keep", unrelated.readText(), point.name)
            assertTrue(
                attemptDir.listFiles().orEmpty().none {
                    it.name.endsWith(".copying") || it.name.endsWith(".writing")
                },
                "legacy temporary residue after convergence: $point",
            )
            runtimeTables().forEach { table ->
                assertEquals(0L, scalar("SELECT COUNT(*) FROM $table"), "$point:$table")
            }
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun audioPhasePortUsesExactFingerprintTokenAndCurrentPhaseCas() {
        val discoveryStore = RoomLegacyDiscoveryStore(db)
        val persistence =
            persistence("audio-source", discoveryStore.requireReady().installEpoch)
                .copy(targetStorageKey = null)
        val token = persistence.attemptToken
        val audioPersistence = persistence.copy(targetStorageKey = "bootstrap/$token/legacy-audio")
        discoveryStore.persistDiscovery(audioPersistence) { audioPersistence }
        val descriptor =
            LegacyAudioBootstrapDescriptorFixtureFactory.create(
                db.legacyBootstrapDao().migrationState()!!.toLegacyAudioBootstrapEvidence(),
                "legacy/audio.mp3",
            ) {
                LegacyAudioSourceSnapshot("legacy/audio.mp3", audioPersistence.sourceFingerprint)
            }
        val copy = LegacyAudioCopyEvidence(descriptor.targetStorageKey, "c".repeat(64), 42)
        val port = RoomLegacyAudioBootstrapStatePort(db)

        assertEquals(
            PhaseCasOutcome.ADVANCED,
            port.compareAndSetPhase(
                descriptor,
                BootstrapPhase.DISCOVERED,
                BootstrapPhase.COPIED,
                copy,
            ),
        )
        assertEquals(
            PhaseCasOutcome.ALREADY_AT_OR_BEYOND,
            port.compareAndSetPhase(
                descriptor,
                BootstrapPhase.DISCOVERED,
                BootstrapPhase.COPIED,
                copy,
            ),
        )
        val otherFingerprint = "legacy-canonical-v1:${"d".repeat(64)}:${"e".repeat(64)}"
        val otherToken = legacyDiscoveryAttemptToken(descriptor.installEpoch, otherFingerprint)
        assertEquals(
            PhaseCasOutcome.REJECTED,
            port.compareAndSetPhase(
                LegacyAudioBootstrapDescriptorFixtureFactory.create(
                    LegacyAudioBootstrapEvidence(
                        "LEGACY",
                        descriptor.installEpoch,
                        otherFingerprint,
                        otherToken,
                        "bootstrap/$otherToken/legacy-audio",
                        "COPIED",
                    ),
                    descriptor.sourcePath,
                ) {
                    LegacyAudioSourceSnapshot(descriptor.sourcePath, otherFingerprint)
                },
                BootstrapPhase.COPIED,
                BootstrapPhase.VALIDATED,
                copy.copy(storageKey = "bootstrap/$otherToken/legacy-audio"),
            ),
        )
        assertEquals(
            PhaseCasOutcome.ADVANCED,
            port.compareAndSetPhase(
                descriptor,
                BootstrapPhase.COPIED,
                BootstrapPhase.VALIDATED,
                copy,
            ),
        )
        assertEquals("VALIDATED", text("SELECT bootstrap_phase FROM migration_state WHERE id=1"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM imported_track"))
    }

    @Test
    fun manifestAndDiscoveredStateCommitAtomicallyAndRerunIsIdempotent() {
        val store = RoomLegacyDiscoveryStore(db)
        val discovery = persistence("fingerprint-a", store.requireReady().installEpoch)

        store.persistDiscovery(discovery) { discovery }
        store.persistDiscovery(discovery) { discovery }

        assertEquals(1L, scalar("SELECT COUNT(*) FROM legacy_migration_manifest"))
        assertEquals("DISCOVERED", text("SELECT bootstrap_phase FROM migration_state WHERE id=1"))
        assertEquals(
            discovery.attemptToken,
            text("SELECT attempt_token FROM migration_state WHERE id=1"),
        )
    }

    @Test
    fun changedRoomAlarmAtTransactionalRevalidationRejectsWithoutWritingEvidence() {
        insertLegacyAlarm()
        var reads = 0
        val roomSource = RoomOpenedLegacyAlarmSource {
            assertEquals(reads > 1, db.inTransaction())
            db.legacyBootstrapDao().activeLegacyAlarmsForDiscovery()
        }
        val changingSource = LegacyAlarmSource {
            if (reads++ == 1) {
                db.openHelper.writableDatabase.execSQL("UPDATE alarms SET minute=46 WHERE id=41")
            }
            roomSource.readAlarms()
        }
        val evidenceBefore = migrationStateEvidence()

        assertFailsWith<IllegalStateException> {
            LegacyBootstrapMigrator(
                    changingSource,
                    { validSettings() },
                    RoomLegacyDiscoveryStore(db),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(41L to LegacyDisposition.SELECT_AS_WAKE))
        }

        assertEquals(2, reads)
        assertEquals(evidenceBefore, migrationStateEvidence())
        assertEquals(emptyList(), manifestEvidence())
    }

    @Test
    fun changedPreferencesAtTransactionalRevalidationRejectsWithoutWritingEvidence() {
        insertLegacyAlarm()
        val prefs = context.getSharedPreferences("toctou-$name", Context.MODE_PRIVATE)
        prefs.edit().putInt("wake_ramp_minutes", 20).commit()
        val exactSettings = SharedPreferencesLegacyWakeSettingsSource(prefs)
        var reads = 0
        val changingSettings = LegacyWakeSettingsSource {
            if (reads++ == 1) prefs.edit().putInt("wake_ramp_minutes", 21).commit()
            exactSettings.readSettings()
        }
        val evidenceBefore = migrationStateEvidence()

        assertFailsWith<IllegalStateException> {
            LegacyBootstrapMigrator(
                    RoomOpenedLegacyAlarmSource {
                        db.legacyBootstrapDao().activeLegacyAlarmsForDiscovery()
                    },
                    changingSettings,
                    RoomLegacyDiscoveryStore(db),
                    { 0L },
                    ZoneId.of("UTC"),
                )
                .discover(mapOf(41L to LegacyDisposition.SELECT_AS_WAKE))
        }

        assertEquals(2, reads)
        assertEquals(evidenceBefore, migrationStateEvidence())
        assertEquals(emptyList(), manifestEvidence())
    }

    @Test
    fun changedUnconfirmedDiscoverySafelyReplacesStaleAttempt() {
        val store = RoomLegacyDiscoveryStore(db)
        val first = persistence("source-a", store.requireReady().installEpoch)
        val replacementRows =
            listOf(
                first.rows
                    .single()
                    .copy(
                        legacyAlarmId = 20,
                        goalEpochMs = 2000,
                        proposedDisposition = LegacyDisposition.SELECT_AS_WAKE.name,
                    )
            )
        val replacement =
            persistenceWithRows("source-b", store.requireReady().installEpoch, replacementRows)

        store.persistDiscovery(first) { first }
        store.persistDiscovery(replacement) { replacement }

        assertEquals(20L, scalar("SELECT legacy_alarm_id FROM legacy_migration_manifest"))
        assertEquals(
            replacement.sourceFingerprint,
            text("SELECT source_fingerprint FROM migration_state WHERE id=1"),
        )
        assertEquals(
            replacement.attemptToken,
            text("SELECT attempt_token FROM migration_state WHERE id=1"),
        )
    }

    @Test
    fun discoveryDoesNotWriteLegacyAlarmOrRuntimeTables() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO alarms(id,hour,minute,isActive,repeatDays,volume,vibrationEnabled) " +
                "VALUES(41,6,45,1,'',1.0,1)"
        )
        val alarmBefore =
            text(
                "SELECT id || ':' || hour || ':' || minute || ':' || isActive || ':' || repeatDays " +
                    "FROM alarms WHERE id=41"
            )
        val runtimeTables =
            listOf(
                "wake_routine",
                "imported_track",
                "wake_run_snapshot",
                "wake_run_status",
                "wake_event_dispatch",
                "wake_recovery_anchor",
                "schedule_outbox",
                "track_lease",
                "schedule_occurrence_claim",
                "legacy_coordinator_member",
            )

        RoomLegacyDiscoveryStore(db).let { store ->
            val discovery = persistence("source-a", store.requireReady().installEpoch)
            store.persistDiscovery(discovery) { discovery }
        }

        assertEquals(
            alarmBefore,
            text(
                "SELECT id || ':' || hour || ':' || minute || ':' || isActive || ':' || repeatDays " +
                    "FROM alarms WHERE id=41"
            ),
        )
        runtimeTables.forEach { table ->
            assertEquals(0L, scalar("SELECT COUNT(*) FROM $table"), table)
        }
    }

    @Test
    fun incomingRowsCannotChangeIndependentlyOfFingerprintAndToken() {
        val store = RoomLegacyDiscoveryStore(db)
        val original = persistence("source-a", store.requireReady().installEpoch)
        val tampered =
            original.copy(rows = original.rows.map { it.copy(goalEpochMs = it.goalEpochMs + 1) })

        assertFailsWith<IllegalArgumentException> { store.persistDiscovery(tampered) { tampered } }

        assertEquals(0L, scalar("SELECT COUNT(*) FROM legacy_migration_manifest"))
        assertEquals(null, nullableText("SELECT bootstrap_phase FROM migration_state WHERE id=1"))
    }

    @Test
    fun malformedIncomingManifestIsRejectedBeforeTransaction() {
        val store = RoomLegacyDiscoveryStore(db)
        val selected =
            LegacyMigrationManifestEntity(
                10,
                1000,
                legacyPendingIntentIdentity(),
                "SELECT_AS_WAKE",
                0,
                null,
            )
        val invalidRows =
            listOf(
                listOf(selected, selected.copy(goalEpochMs = 2000)),
                listOf(selected.copy(proposedDisposition = "KEEP_UNTIL_TERMINAL")),
                listOf(selected, selected.copy(legacyAlarmId = 11)),
                listOf(selected.copy(pendingIntentIdentity = "not-the-global-alarm")),
            )

        invalidRows.forEachIndexed { index, rows ->
            assertFailsWith<IllegalArgumentException> {
                val invalid =
                    persistenceWithRows("invalid-$index", store.requireReady().installEpoch, rows)
                store.persistDiscovery(invalid) { invalid }
            }
        }

        assertEquals(0L, scalar("SELECT COUNT(*) FROM legacy_migration_manifest"))
        assertEquals(null, nullableText("SELECT bootstrap_phase FROM migration_state WHERE id=1"))
    }

    @Test
    fun corruptStoredDiscoveredEvidenceFailsClosed() {
        val store = RoomLegacyDiscoveryStore(db)
        val original = persistence("source-a", store.requireReady().installEpoch)
        store.persistDiscovery(original) { original }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE legacy_migration_manifest SET goal_epoch_ms=goal_epoch_ms+1"
        )

        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }

        db.openHelper.writableDatabase.execSQL(
            "UPDATE legacy_migration_manifest SET goal_epoch_ms=goal_epoch_ms-1"
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET target_storage_key='tampered' WHERE id=1"
        )
        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }
    }

    @Test
    fun initialStateWithOrphanedTargetStorageKeyFailsClosedWithoutChangingEvidence() {
        val store = RoomLegacyDiscoveryStore(db)
        val incoming = persistence("source-a", store.requireReady().installEpoch)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET target_storage_key='orphaned-target' WHERE id=1"
        )
        val stateBefore = migrationStateEvidence()
        val manifestBefore = manifestEvidence()

        assertFailsWith<IllegalStateException> { store.persistDiscovery(incoming) { incoming } }

        assertEquals(stateBefore, migrationStateEvidence())
        assertEquals(manifestBefore, manifestEvidence())
    }

    @Test
    fun injectedFailureRollsBackManifestReplacementAndState() {
        val normal = RoomLegacyDiscoveryStore(db)
        val first = persistence("fingerprint-a", normal.requireReady().installEpoch)
        normal.persistDiscovery(first) { first }
        val failing = RoomLegacyDiscoveryStore(db) { throw Fault() }

        assertFailsWith<Fault> {
            val replacement = persistence("fingerprint-b", failing.requireReady().installEpoch)
            failing.persistDiscovery(replacement) { replacement }
        }

        assertEquals(10L, scalar("SELECT legacy_alarm_id FROM legacy_migration_manifest"))
        assertEquals(
            first.sourceFingerprint,
            text("SELECT source_fingerprint FROM migration_state WHERE id=1"),
        )
    }

    @Test
    fun confirmedAdvancedOwnerAndAttemptConflictAllFailClosed() {
        val store = RoomLegacyDiscoveryStore(db)
        val original = persistence("fingerprint-a", store.requireReady().installEpoch)
        store.persistDiscovery(original) { original }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE legacy_migration_manifest SET user_confirmed=1"
        )
        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE legacy_migration_manifest SET user_confirmed=0"
        )

        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET bootstrap_phase='COPIED'"
        )
        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET bootstrap_phase='DISCOVERED', schedule_owner='WAKE'"
        )
        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE migration_state SET schedule_owner='LEGACY', attempt_token='tampered'"
        )
        assertFailsWith<IllegalStateException> { store.persistDiscovery(original) { original } }

        assertEquals(1L, scalar("SELECT COUNT(*) FROM legacy_migration_manifest"))
        assertEquals(
            original.sourceFingerprint,
            text("SELECT source_fingerprint FROM migration_state WHERE id=1"),
        )
    }

    private fun insertLegacyAlarm() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO alarms(id,hour,minute,isActive,repeatDays,ringtoneUri,volume,vibrationEnabled,skippedOccurrenceDay) " +
                "VALUES(41,6,45,1,'',NULL,1.0,1,NULL)"
        )
    }

    private fun descriptorFromRoom(source: File): LegacyAudioBootstrapDescriptor {
        val evidence = db.legacyBootstrapDao().migrationState()!!.toLegacyAudioBootstrapEvidence()
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(evidence, source.path) {
            LegacyAudioSourceSnapshot(source.path, requireNotNull(evidence.sourceFingerprint))
        }
    }

    private fun alarmEvidence(): List<String?> =
        db.openHelper.writableDatabase.query("SELECT * FROM alarms WHERE id=41").use { cursor ->
            cursor.moveToFirst()
            List(cursor.columnCount) { index ->
                if (cursor.isNull(index)) null else cursor.getString(index)
            }
        }

    private fun runtimeTables() =
        listOf(
            "wake_routine",
            "imported_track",
            "wake_run_snapshot",
            "wake_run_status",
            "wake_event_dispatch",
            "wake_recovery_anchor",
            "schedule_outbox",
            "track_lease",
            "schedule_occurrence_claim",
            "legacy_coordinator_member",
        )

    private fun validSettings() = LegacyWakeSettingsSnapshot(50f, 20, .05f, .35f, "CONFIRM", null)

    private fun persistence(seed: String, epoch: String): LegacyDiscoveryPersistence {
        val rows =
            listOf(
                LegacyMigrationManifestEntity(
                    10,
                    1000,
                    legacyPendingIntentIdentity(),
                    "SELECT_AS_WAKE",
                    0,
                    null,
                )
            )
        return persistenceWithRows(seed, epoch, rows)
    }

    private fun persistenceWithRows(
        seed: String,
        epoch: String,
        rows: List<LegacyMigrationManifestEntity>,
    ): LegacyDiscoveryPersistence {
        val fingerprint = legacyDiscoverySourceFingerprint(seed, rows)
        val token = legacyDiscoveryAttemptToken(epoch, fingerprint)
        return LegacyDiscoveryPersistence(
            rows,
            fingerprint,
            token,
            null,
            seed,
        )
    }

    private fun scalar(sql: String): Long =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            it.getLong(0)
        }

    private fun migrationStateEvidence(): List<String?> =
        db.openHelper.writableDatabase
            .query(
                "SELECT id, schedule_owner, active_generation, bootstrap_version, " +
                    "rollback_allowed_until_version, handoff_fence_occurrence_id, install_epoch, " +
                    "source_fingerprint, target_storage_key, bootstrap_phase, attempt_token " +
                    "FROM migration_state WHERE id=1"
            )
            .use { cursor ->
                cursor.moveToFirst()
                List(cursor.columnCount) { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }
            }

    private fun manifestEvidence(): List<String> =
        db.openHelper.writableDatabase
            .query(
                "SELECT legacy_alarm_id || ':' || goal_epoch_ms || ':' || pending_intent_identity || " +
                    "':' || proposed_disposition || ':' || user_confirmed || ':' || " +
                    "COALESCE(terminal_at, 'null') FROM legacy_migration_manifest " +
                    "ORDER BY goal_epoch_ms, legacy_alarm_id"
            )
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

    private fun nullableText(sql: String): String? =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getString(0)
        }

    private fun text(sql: String): String =
        db.openHelper.writableDatabase.query(sql).use {
            it.moveToFirst()
            it.getString(0)
        }

    private class Fault : RuntimeException()
}
