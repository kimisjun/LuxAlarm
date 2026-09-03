/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface LegacyBootstrapDao {
    @Query("SELECT * FROM alarms WHERE isActive = 1 ORDER BY id ASC LIMIT 65")
    fun activeLegacyAlarmsForDiscovery(): List<AlarmItem>

    @Query("SELECT * FROM migration_state WHERE id = 1") fun migrationState(): MigrationStateEntity?

    @Query("SELECT COUNT(*) FROM legacy_migration_manifest WHERE user_confirmed = 1")
    fun confirmedManifestCount(): Int

    @Query("SELECT COUNT(*) FROM legacy_migration_manifest") fun manifestCount(): Int

    @Query(
        "SELECT * FROM legacy_migration_manifest ORDER BY goal_epoch_ms ASC, legacy_alarm_id ASC"
    )
    fun manifestRows(): List<LegacyMigrationManifestEntity>

    @Query("DELETE FROM legacy_migration_manifest") fun deleteManifest()

    @Insert fun insertManifest(rows: List<LegacyMigrationManifestEntity>)

    @Query(
        """
        UPDATE migration_state
        SET source_fingerprint = :fingerprint,
            target_storage_key = :targetStorageKey,
            bootstrap_phase = 'DISCOVERED',
            attempt_token = :attemptToken
        WHERE id = 1
        """
    )
    fun markDiscovered(fingerprint: String, targetStorageKey: String?, attemptToken: String): Int
}

/** Atomic Room boundary for the discovery-only part of bootstrap. */
internal class RoomLegacyDiscoveryStore(
    private val database: AlarmDatabase,
    private val beforeStateWrite: () -> Unit = {},
) : LegacyDiscoveryStore {
    private val dao
        get() = database.legacyBootstrapDao()

    override fun requireReady(): LegacyDiscoveryReadiness {
        val state = checkNotNull(dao.migrationState()) { "Missing migration_state singleton" }
        check(state.id == 1 && state.installEpoch.isNotBlank()) {
            "Invalid migration_state singleton"
        }
        return LegacyDiscoveryReadiness(state.installEpoch)
    }

    override fun persistDiscovery(
        discovery: LegacyDiscoveryPersistence,
        revalidate: () -> LegacyDiscoveryPersistence,
    ) {
        require(
            discovery.sourceFingerprint ==
                legacyDiscoverySourceFingerprint(discovery.sourceFingerprintSeed, discovery.rows)
        ) {
            "Incoming rows/source fingerprint conflict"
        }
        require(discovery.rows.all { it.userConfirmed == 0 && it.terminalAt == null }) {
            "Discovery may persist only unconfirmed, non-terminal rows"
        }
        require(
            discovery.rows.map { it.legacyAlarmId }.distinct().size == discovery.rows.size &&
                discovery.rows.all { it.legacyAlarmId > 0 && it.goalEpochMs > 0 }
        ) {
            "Discovery manifest alarm ids and goals must be valid and unambiguous"
        }
        require(
            discovery.rows.all {
                LegacyDisposition.entries.any { disposition ->
                    disposition.name == it.proposedDisposition
                } && it.pendingIntentIdentity == legacyPendingIntentIdentity()
            }
        ) {
            "Discovery manifest disposition or PendingIntent identity is invalid"
        }
        val selected =
            discovery.rows.count {
                it.proposedDisposition == LegacyDisposition.SELECT_AS_WAKE.name
            }
        require(
            (discovery.rows.isEmpty() && selected == 0) ||
                (discovery.rows.isNotEmpty() && selected == 1)
        ) {
            "Discovery manifest must select exactly one alarm when candidates exist"
        }
        database.runInTransaction {
            val state = checkNotNull(dao.migrationState()) { "Missing migration_state singleton" }
            check(state.scheduleOwner == "LEGACY") {
                "Discovery requires LEGACY schedule ownership"
            }
            check(state.bootstrapPhase == null || state.bootstrapPhase == "DISCOVERED") {
                "Discovery cannot replace advanced bootstrap evidence"
            }
            check(dao.confirmedManifestCount() == 0) {
                "Discovery cannot replace user-confirmed evidence"
            }
            check(
                discovery.attemptToken ==
                    legacyDiscoveryAttemptToken(state.installEpoch, discovery.sourceFingerprint)
            ) {
                "Incoming fingerprint/attempt token conflict"
            }
            check(
                discovery.targetStorageKey == null ||
                    discovery.targetStorageKey == "bootstrap/${discovery.attemptToken}/legacy-audio"
            ) {
                "Incoming staging storage key is not deterministic"
            }
            // Room reads above and the alarm read in this callback share this transaction.
            // Preferences
            // cannot join a Room transaction, so this is intentionally a two-phase
            // snapshot/recheck:
            // no Room write occurs when either source changed before this immediate revalidation.
            check(revalidate() == discovery) {
                "Legacy discovery sources changed before persistence"
            }
            if (state.bootstrapPhase == null) {
                check(dao.manifestCount() == 0) { "Manifest exists without DISCOVERED state" }
                check(
                    state.sourceFingerprint == null &&
                        state.attemptToken == null &&
                        state.targetStorageKey == null
                ) {
                    "Attempt evidence exists without DISCOVERED state"
                }
            } else {
                val oldFingerprint =
                    checkNotNull(state.sourceFingerprint) {
                        "DISCOVERED state is missing source fingerprint"
                    }
                check(
                    state.attemptToken ==
                        legacyDiscoveryAttemptToken(state.installEpoch, oldFingerprint)
                ) {
                    "Stored fingerprint/attempt token conflict"
                }
                check(legacyDiscoveryRowsMatchFingerprint(oldFingerprint, dao.manifestRows())) {
                    "Stored manifest/source fingerprint conflict"
                }
                check(
                    state.targetStorageKey == null ||
                        state.targetStorageKey == "bootstrap/${state.attemptToken}/legacy-audio"
                ) {
                    "Stored staging storage key is not deterministic"
                }
            }
            dao.deleteManifest()
            if (discovery.rows.isNotEmpty()) dao.insertManifest(discovery.rows)
            beforeStateWrite()
            check(
                dao.markDiscovered(
                    discovery.sourceFingerprint,
                    discovery.targetStorageKey,
                    discovery.attemptToken,
                ) == 1
            ) {
                "Failed to update migration_state"
            }
        }
    }
}
