/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

internal val MIGRATION_5_6_IMPLEMENTATION =
    object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            createAndInitializeV6Schema(db)
        }
    }

internal val V6_NEW_DATABASE_CALLBACK =
    object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            // Room creates its annotation-derived projection before this callback. Preflight every
            // additive table before replacing that projection: never partially drop a populated
            // database, even if the populated table is last in drop order.
            val populatedTables = V6_ADDITIVE_TABLES_IN_DROP_ORDER.filter { table ->
                db.query("SELECT 1 FROM $table LIMIT 1").use { it.moveToFirst() }
            }
            check(populatedTables.isEmpty()) {
                "Refusing to replace populated V6 tables: ${populatedTables.joinToString()}"
            }
            V6_ADDITIVE_TABLES_IN_DROP_ORDER.forEach { table ->
                db.execSQL("DROP TABLE IF EXISTS $table")
            }
            createAndInitializeV6Schema(db)
        }
    }

internal fun createAndInitializeV6Schema(
    db: SupportSQLiteDatabase,
    installEpochSupplier: () -> String = { UUID.randomUUID().toString() },
    timestampSupplier: () -> Long = System::currentTimeMillis,
) {
    V6_SCHEMA_STATEMENTS.forEach(db::execSQL)
    db.execSQL(
        """
        INSERT INTO migration_state(
          id, schedule_owner, active_generation, bootstrap_version,
          rollback_allowed_until_version, handoff_fence_occurrence_id,
          install_epoch, source_fingerprint, target_storage_key,
          bootstrap_phase, attempt_token
        ) VALUES (1, 'LEGACY', NULL, 0, 16, NULL, ?, NULL, NULL, NULL, NULL)
        """
            .trimIndent(),
        arrayOf(installEpochSupplier()),
    )
    db.execSQL(
        """
        INSERT INTO legacy_coordinator_state(
          id, token_generation, scheduled_goal_epoch_ms,
          pending_intent_identity, state, updated_at
        ) VALUES (1, 0, NULL, NULL, 'IDLE', ?)
        """
            .trimIndent(),
        arrayOf(timestampSupplier()),
    )
}

private val V6_ADDITIVE_TABLES_IN_DROP_ORDER =
    listOf(
        "schedule_outbox",
        "wake_recovery_anchor",
        "wake_event_dispatch",
        "wake_run_status",
        "track_lease",
        "wake_run_snapshot",
        "wake_routine",
        "legacy_coordinator_member",
        "legacy_coordinator_state",
        "legacy_migration_manifest",
        "schedule_occurrence_claim",
        "migration_state",
        "imported_track",
    )

/**
 * The approved V6 contract's authoritative runtime DDL.
 *
 * Room annotations/KSP cannot represent the contract's CHECK clauses, partial UNIQUE index, or
 * every inline UNIQUE constraint. [V6_NEW_DATABASE_CALLBACK] and [MIGRATION_5_6_IMPLEMENTATION]
 * therefore install these statements at runtime. The genuine KSP `6.json` export is a Room
 * validation projection, not the complete runtime contract.
 */
internal val V6_SCHEMA_STATEMENTS =
    listOf(
        """
        CREATE TABLE imported_track (
          id TEXT NOT NULL PRIMARY KEY,
          storage_key TEXT NOT NULL UNIQUE,
          title TEXT NOT NULL,
          artist TEXT NULL,
          duration_ms INTEGER NOT NULL CHECK(duration_ms >= 0),
          mime_type TEXT NOT NULL,
          content_hash TEXT NOT NULL,
          lifecycle_state TEXT NOT NULL CHECK(lifecycle_state IN ('STAGING','VALIDATED','AVAILABLE','PENDING_DELETE','DELETING','DELETED')),
          availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE','MISSING_OR_BROKEN')),
          deletion_token TEXT NULL,
          ref_count_cache INTEGER NOT NULL DEFAULT 0 CHECK(ref_count_cache >= 0),
          added_at INTEGER NOT NULL,
          CHECK((lifecycle_state = 'DELETING' AND deletion_token IS NOT NULL) OR
                (lifecycle_state <> 'DELETING' AND deletion_token IS NULL))
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_track_lifecycle ON imported_track(lifecycle_state)",
        """
        CREATE UNIQUE INDEX uq_track_live_hash ON imported_track(content_hash)
        WHERE lifecycle_state IN ('STAGING','VALIDATED','AVAILABLE','PENDING_DELETE','DELETING')
        """
            .trimIndent(),
        """
        CREATE TABLE wake_routine (
          id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1),
          enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN (0,1)),
          goal_minute_of_day INTEGER NOT NULL CHECK(goal_minute_of_day BETWEEN 0 AND 1439),
          repeat_days_mask INTEGER NOT NULL CHECK(repeat_days_mask BETWEEN 0 AND 127),
          zone_id TEXT NOT NULL,
          preset_selection TEXT NOT NULL CHECK(preset_selection IN ('VERY_GENTLE','BALANCED','RELIABLE','CUSTOM')),
          preset_base TEXT NOT NULL CHECK(preset_base IN ('VERY_GENTLE','BALANCED','RELIABLE')),
          light_enabled INTEGER NOT NULL CHECK(light_enabled IN (0,1)),
          light_offset_min INTEGER NOT NULL CHECK(light_offset_min BETWEEN 0 AND 60),
          light_start REAL NOT NULL CHECK(light_start BETWEEN 0.0 AND 1.0),
          light_target REAL NOT NULL CHECK(light_target BETWEEN light_start AND 1.0),
          light_curve TEXT NOT NULL CHECK(light_curve IN ('LINEAR','SMOOTHSTEP')),
          music_enabled INTEGER NOT NULL CHECK(music_enabled IN (0,1)),
          music_offset_min INTEGER NOT NULL CHECK(music_offset_min BETWEEN 0 AND 60),
          music_start REAL NOT NULL CHECK(music_start BETWEEN 0.0 AND 1.0),
          music_target REAL NOT NULL CHECK(music_target BETWEEN music_start AND 1.0),
          music_curve TEXT NOT NULL CHECK(music_curve IN ('LINEAR','SMOOTHSTEP')),
          vibration_enabled INTEGER NOT NULL CHECK(vibration_enabled IN (0,1)),
          vibration_offset_min INTEGER NOT NULL CHECK(vibration_offset_min BETWEEN 0 AND 60),
          vibration_start REAL NOT NULL CHECK(vibration_start BETWEEN 0.0 AND 1.0),
          vibration_target REAL NOT NULL CHECK(vibration_target BETWEEN vibration_start AND 1.0),
          vibration_curve TEXT NOT NULL CHECK(vibration_curve IN ('LINEAR','SMOOTHSTEP')),
          selected_track_id TEXT NULL,
          dismissal TEXT NOT NULL CHECK(dismissal IN ('CONFIRM','LUX')),
          revision INTEGER NOT NULL CHECK(revision >= 1),
          updated_at INTEGER NOT NULL,
          FOREIGN KEY(selected_track_id) REFERENCES imported_track(id) ON DELETE SET NULL,
          CHECK(light_enabled + music_enabled + vibration_enabled >= 1)
        )
        """
            .trimIndent(),
        """
        CREATE TABLE wake_run_snapshot (
          id TEXT NOT NULL PRIMARY KEY,
          occurrence_id TEXT NOT NULL UNIQUE,
          schedule_generation INTEGER NOT NULL,
          routine_revision INTEGER NOT NULL,
          calculation_rule_version INTEGER NOT NULL,
          zone_id TEXT NOT NULL,
          occurrence_local_date TEXT NOT NULL,
          wake_start_epoch_ms INTEGER NOT NULL,
          goal_epoch_ms INTEGER NOT NULL CHECK(goal_epoch_ms >= wake_start_epoch_ms),
          light_payload TEXT NOT NULL,
          music_payload TEXT NOT NULL,
          vibration_payload TEXT NOT NULL,
          selected_track_id TEXT NULL,
          selected_track_storage_key TEXT NULL,
          dismissal TEXT NOT NULL CHECK(dismissal IN ('CONFIRM','LUX')),
          created_at INTEGER NOT NULL,
          install_epoch TEXT NOT NULL,
          FOREIGN KEY(selected_track_id) REFERENCES imported_track(id) ON DELETE RESTRICT
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_snapshot_generation ON wake_run_snapshot(schedule_generation)",
        "CREATE INDEX idx_snapshot_goal ON wake_run_snapshot(goal_epoch_ms)",
        """
        CREATE TABLE wake_run_status (
          snapshot_id TEXT NOT NULL PRIMARY KEY,
          state TEXT NOT NULL CHECK(state IN ('PREPARED','ACTIVE','GOAL_REACHED','COMPLETED','NO_CONFIRMATION','FAILED','CANCELLED','SUPERSEDED','EXPIRED')),
          processed_start_at INTEGER NULL,
          processed_goal_at INTEGER NULL,
          active_service_owner_token TEXT NULL,
          execution_epoch INTEGER NOT NULL DEFAULT 0 CHECK(execution_epoch >= 0),
          service_lease_owner TEXT NULL,
          service_lease_expires_at INTEGER NULL,
          heartbeat_at INTEGER NULL,
          armed_start INTEGER NOT NULL DEFAULT 0 CHECK(armed_start IN (0,1)),
          armed_goal INTEGER NOT NULL DEFAULT 0 CHECK(armed_goal IN (0,1)),
          started_at INTEGER NULL,
          completed_at INTEGER NULL,
          cancelled_at INTEGER NULL,
          failure_reason TEXT NULL,
          FOREIGN KEY(snapshot_id) REFERENCES wake_run_snapshot(id) ON DELETE CASCADE
        )
        """
            .trimIndent(),
        """
        CREATE TABLE wake_event_dispatch (
          event_key TEXT NOT NULL PRIMARY KEY,
          snapshot_id TEXT NOT NULL,
          event_kind TEXT NOT NULL CHECK(event_kind IN ('START','GOAL')),
          expected_trigger_epoch_ms INTEGER NOT NULL,
          state TEXT NOT NULL CHECK(state IN ('RECEIVED','DEFERRED','DISPATCH_REQUESTED','SERVICE_ACKED','TERMINAL')),
          dispatch_attempt_id INTEGER NOT NULL DEFAULT 0 CHECK(dispatch_attempt_id >= 0),
          lease_owner TEXT NULL,
          lease_expires_at INTEGER NULL,
          attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
          last_attempt_at INTEGER NULL,
          failure_reason TEXT NULL,
          armed_primary INTEGER NOT NULL DEFAULT 0 CHECK(armed_primary IN (0,1)),
          recovery_slot_a_at INTEGER NULL,
          recovery_slot_a_state TEXT NOT NULL DEFAULT 'CONSUMED' CHECK(recovery_slot_a_state IN ('ARMED','FIRED','IN_FLIGHT','CONSUMED','CANCELLED')),
          recovery_slot_a_token INTEGER NOT NULL DEFAULT 0 CHECK(recovery_slot_a_token >= 0),
          recovery_slot_b_at INTEGER NULL,
          recovery_slot_b_state TEXT NOT NULL DEFAULT 'CONSUMED' CHECK(recovery_slot_b_state IN ('ARMED','FIRED','IN_FLIGHT','CONSUMED','CANCELLED')),
          recovery_slot_b_token INTEGER NOT NULL DEFAULT 0 CHECK(recovery_slot_b_token >= 0),
          FOREIGN KEY(snapshot_id) REFERENCES wake_run_snapshot(id) ON DELETE CASCADE,
          UNIQUE(snapshot_id, event_kind)
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_dispatch_due ON wake_event_dispatch(state, recovery_slot_a_at, recovery_slot_b_at)",
        """
        CREATE TABLE wake_recovery_anchor (
          event_key TEXT NOT NULL,
          anchor_kind TEXT NOT NULL CHECK(anchor_kind IN ('GOAL_PRIMARY','GOAL_PLUS_1M','GOAL_PLUS_5M','GOAL_PLUS_15M','GOAL_PLUS_30M')),
          trigger_epoch_ms INTEGER NOT NULL,
          state TEXT NOT NULL CHECK(state IN ('ARMED','FIRED','CONSUMED','CANCELLED')),
          pending_intent_identity TEXT NOT NULL,
          PRIMARY KEY(event_key, anchor_kind),
          FOREIGN KEY(event_key) REFERENCES wake_event_dispatch(event_key) ON DELETE CASCADE
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_anchor_due ON wake_recovery_anchor(state, trigger_epoch_ms)",
        """
        CREATE TABLE schedule_outbox (
          id TEXT NOT NULL PRIMARY KEY,
          generation INTEGER NOT NULL,
          command TEXT NOT NULL CHECK(command IN ('ARM_PRIMARY','ARM_RECOVERY','CANCEL_PRIMARY','CANCEL_RECOVERY','CREATE_NEXT','RECONCILE')),
          event_key TEXT NULL,
          state TEXT NOT NULL CHECK(state IN ('PENDING','RUNNING','DONE','FAILED')),
          attempt_count INTEGER NOT NULL DEFAULT 0,
          not_before_epoch_ms INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          last_error TEXT NULL,
          FOREIGN KEY(event_key) REFERENCES wake_event_dispatch(event_key) ON DELETE CASCADE
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_outbox_pending ON schedule_outbox(state, not_before_epoch_ms)",
        """
        CREATE TABLE track_lease (
          snapshot_id TEXT NOT NULL,
          track_id TEXT NOT NULL,
          acquired_at INTEGER NOT NULL,
          PRIMARY KEY(snapshot_id, track_id),
          FOREIGN KEY(snapshot_id) REFERENCES wake_run_snapshot(id) ON DELETE CASCADE,
          FOREIGN KEY(track_id) REFERENCES imported_track(id) ON DELETE RESTRICT
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_track_lease_track ON track_lease(track_id)",
        """
        CREATE TABLE schedule_occurrence_claim (
          canonical_occurrence_key TEXT NOT NULL PRIMARY KEY,
          legacy_alarm_id INTEGER NULL,
          goal_epoch_ms INTEGER NOT NULL,
          owner TEXT NOT NULL CHECK(owner IN ('LEGACY','WAKE')),
          state TEXT NOT NULL CHECK(state IN ('PREPARED','CLAIMED','TERMINAL')),
          fence_token INTEGER NOT NULL DEFAULT 0 CHECK(fence_token >= 0),
          claimed_by TEXT NULL,
          claimed_at INTEGER NULL
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_occurrence_goal ON schedule_occurrence_claim(goal_epoch_ms)",
        """
        CREATE TABLE legacy_migration_manifest (
          legacy_alarm_id INTEGER NOT NULL,
          goal_epoch_ms INTEGER NOT NULL,
          pending_intent_identity TEXT NOT NULL,
          proposed_disposition TEXT NOT NULL CHECK(proposed_disposition IN ('SELECT_AS_WAKE','KEEP_UNTIL_TERMINAL','DISABLE_AFTER_CONFIRM')),
          user_confirmed INTEGER NOT NULL DEFAULT 0 CHECK(user_confirmed IN (0,1)),
          terminal_at INTEGER NULL,
          PRIMARY KEY(legacy_alarm_id, goal_epoch_ms)
        )
        """
            .trimIndent(),
        """
        CREATE TABLE legacy_coordinator_state (
          id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1),
          token_generation INTEGER NOT NULL DEFAULT 0 CHECK(token_generation >= 0),
          scheduled_goal_epoch_ms INTEGER NULL,
          pending_intent_identity TEXT NULL,
          state TEXT NOT NULL CHECK(state IN ('IDLE','ARMING_SUCCESSOR','ARMED')),
          updated_at INTEGER NOT NULL
        )
        """
            .trimIndent(),
        """
        CREATE TABLE legacy_coordinator_member (
          token_generation INTEGER NOT NULL,
          legacy_alarm_id INTEGER NOT NULL,
          goal_epoch_ms INTEGER NOT NULL,
          PRIMARY KEY(token_generation, legacy_alarm_id, goal_epoch_ms)
        )
        """
            .trimIndent(),
        "CREATE INDEX idx_legacy_member_goal ON legacy_coordinator_member(token_generation, goal_epoch_ms)",
        """
        CREATE TABLE migration_state (
          id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1),
          schedule_owner TEXT NOT NULL CHECK(schedule_owner IN ('LEGACY','PREPARING_WAKE','WAKE','RESTORING')),
          active_generation INTEGER NULL,
          bootstrap_version INTEGER NOT NULL DEFAULT 0,
          rollback_allowed_until_version INTEGER NOT NULL DEFAULT 16,
          handoff_fence_occurrence_id TEXT NULL,
          install_epoch TEXT NOT NULL,
          source_fingerprint TEXT NULL,
          target_storage_key TEXT NULL,
          bootstrap_phase TEXT NULL CHECK(bootstrap_phase IS NULL OR bootstrap_phase IN ('DISCOVERED','COPIED','VALIDATED','COMMITTED')),
          attempt_token TEXT NULL
        )
        """
            .trimIndent(),
    )
