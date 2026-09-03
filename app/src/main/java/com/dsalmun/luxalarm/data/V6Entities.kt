/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room-validation projection of V6, used to generate the genuine KSP schema export.
 *
 * This is deliberately not the full runtime contract: Room annotations cannot encode its CHECK
 * clauses, partial UNIQUE index, or every inline UNIQUE constraint. In particular, the global
 * unique [ImportedTrackEntity.contentHash] index is a metadata surrogate; the fresh-database
 * callback and 5→6 migration replace it with the approved partial `uq_track_live_hash` index from
 * [V6_SCHEMA_STATEMENTS]. Never treat generated `6.json` as the complete runtime DDL contract.
 */
@Entity(
    tableName = "imported_track",
    indices =
        [
            Index(value = ["lifecycle_state"], name = "idx_track_lifecycle"),
            Index(value = ["content_hash"], unique = true, name = "uq_track_live_hash"),
        ],
)
data class ImportedTrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "storage_key") val storageKey: String,
    val title: String,
    val artist: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "lifecycle_state") val lifecycleState: String,
    val availability: String,
    @ColumnInfo(name = "deletion_token") val deletionToken: String?,
    @ColumnInfo(name = "ref_count_cache", defaultValue = "0") val refCountCache: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(
    tableName = "wake_routine",
    foreignKeys =
        [
            ForeignKey(
                entity = ImportedTrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["selected_track_id"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
)
data class WakeRoutineEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(defaultValue = "0") val enabled: Int,
    @ColumnInfo(name = "goal_minute_of_day") val goalMinuteOfDay: Int,
    @ColumnInfo(name = "repeat_days_mask") val repeatDaysMask: Int,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "preset_selection") val presetSelection: String,
    @ColumnInfo(name = "preset_base") val presetBase: String,
    @ColumnInfo(name = "light_enabled") val lightEnabled: Int,
    @ColumnInfo(name = "light_offset_min") val lightOffsetMin: Int,
    @ColumnInfo(name = "light_start") val lightStart: Double,
    @ColumnInfo(name = "light_target") val lightTarget: Double,
    @ColumnInfo(name = "light_curve") val lightCurve: String,
    @ColumnInfo(name = "music_enabled") val musicEnabled: Int,
    @ColumnInfo(name = "music_offset_min") val musicOffsetMin: Int,
    @ColumnInfo(name = "music_start") val musicStart: Double,
    @ColumnInfo(name = "music_target") val musicTarget: Double,
    @ColumnInfo(name = "music_curve") val musicCurve: String,
    @ColumnInfo(name = "vibration_enabled") val vibrationEnabled: Int,
    @ColumnInfo(name = "vibration_offset_min") val vibrationOffsetMin: Int,
    @ColumnInfo(name = "vibration_start") val vibrationStart: Double,
    @ColumnInfo(name = "vibration_target") val vibrationTarget: Double,
    @ColumnInfo(name = "vibration_curve") val vibrationCurve: String,
    @ColumnInfo(name = "selected_track_id") val selectedTrackId: String?,
    val dismissal: String,
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "wake_run_snapshot",
    foreignKeys =
        [
            ForeignKey(
                entity = ImportedTrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["selected_track_id"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices =
        [
            Index(value = ["schedule_generation"], name = "idx_snapshot_generation"),
            Index(value = ["goal_epoch_ms"], name = "idx_snapshot_goal"),
        ],
)
data class WakeRunSnapshotEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "schedule_generation") val scheduleGeneration: Long,
    @ColumnInfo(name = "routine_revision") val routineRevision: Long,
    @ColumnInfo(name = "calculation_rule_version") val calculationRuleVersion: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "occurrence_local_date") val occurrenceLocalDate: String,
    @ColumnInfo(name = "wake_start_epoch_ms") val wakeStartEpochMs: Long,
    @ColumnInfo(name = "goal_epoch_ms") val goalEpochMs: Long,
    @ColumnInfo(name = "light_payload") val lightPayload: String,
    @ColumnInfo(name = "music_payload") val musicPayload: String,
    @ColumnInfo(name = "vibration_payload") val vibrationPayload: String,
    @ColumnInfo(name = "selected_track_id") val selectedTrackId: String?,
    @ColumnInfo(name = "selected_track_storage_key") val selectedTrackStorageKey: String?,
    val dismissal: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "install_epoch") val installEpoch: String,
)

@Entity(
    tableName = "wake_run_status",
    foreignKeys =
        [
            ForeignKey(
                entity = WakeRunSnapshotEntity::class,
                parentColumns = ["id"],
                childColumns = ["snapshot_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class WakeRunStatusEntity(
    @PrimaryKey @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    val state: String,
    @ColumnInfo(name = "processed_start_at") val processedStartAt: Long?,
    @ColumnInfo(name = "processed_goal_at") val processedGoalAt: Long?,
    @ColumnInfo(name = "active_service_owner_token") val activeServiceOwnerToken: String?,
    @ColumnInfo(name = "execution_epoch", defaultValue = "0") val executionEpoch: Long,
    @ColumnInfo(name = "service_lease_owner") val serviceLeaseOwner: String?,
    @ColumnInfo(name = "service_lease_expires_at") val serviceLeaseExpiresAt: Long?,
    @ColumnInfo(name = "heartbeat_at") val heartbeatAt: Long?,
    @ColumnInfo(name = "armed_start", defaultValue = "0") val armedStart: Int,
    @ColumnInfo(name = "armed_goal", defaultValue = "0") val armedGoal: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "cancelled_at") val cancelledAt: Long?,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
)

@Entity(
    tableName = "wake_event_dispatch",
    foreignKeys =
        [
            ForeignKey(
                entity = WakeRunSnapshotEntity::class,
                parentColumns = ["id"],
                childColumns = ["snapshot_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(
                value = ["state", "recovery_slot_a_at", "recovery_slot_b_at"],
                name = "idx_dispatch_due",
            )
        ],
)
data class WakeEventDispatchEntity(
    @PrimaryKey @ColumnInfo(name = "event_key") val eventKey: String,
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "event_kind") val eventKind: String,
    @ColumnInfo(name = "expected_trigger_epoch_ms") val expectedTriggerEpochMs: Long,
    val state: String,
    @ColumnInfo(name = "dispatch_attempt_id", defaultValue = "0") val dispatchAttemptId: Long,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Long,
    @ColumnInfo(name = "last_attempt_at") val lastAttemptAt: Long?,
    @ColumnInfo(name = "failure_reason") val failureReason: String?,
    @ColumnInfo(name = "armed_primary", defaultValue = "0") val armedPrimary: Int,
    @ColumnInfo(name = "recovery_slot_a_at") val recoverySlotAAt: Long?,
    @ColumnInfo(name = "recovery_slot_a_state", defaultValue = "'CONSUMED'")
    val recoverySlotAState: String,
    @ColumnInfo(name = "recovery_slot_a_token", defaultValue = "0") val recoverySlotAToken: Long,
    @ColumnInfo(name = "recovery_slot_b_at") val recoverySlotBAt: Long?,
    @ColumnInfo(name = "recovery_slot_b_state", defaultValue = "'CONSUMED'")
    val recoverySlotBState: String,
    @ColumnInfo(name = "recovery_slot_b_token", defaultValue = "0") val recoverySlotBToken: Long,
)

@Entity(
    tableName = "wake_recovery_anchor",
    primaryKeys = ["event_key", "anchor_kind"],
    foreignKeys =
        [
            ForeignKey(
                entity = WakeEventDispatchEntity::class,
                parentColumns = ["event_key"],
                childColumns = ["event_key"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["state", "trigger_epoch_ms"], name = "idx_anchor_due")],
)
data class WakeRecoveryAnchorEntity(
    @ColumnInfo(name = "event_key") val eventKey: String,
    @ColumnInfo(name = "anchor_kind") val anchorKind: String,
    @ColumnInfo(name = "trigger_epoch_ms") val triggerEpochMs: Long,
    val state: String,
    @ColumnInfo(name = "pending_intent_identity") val pendingIntentIdentity: String,
)

@Entity(
    tableName = "schedule_outbox",
    foreignKeys =
        [
            ForeignKey(
                entity = WakeEventDispatchEntity::class,
                parentColumns = ["event_key"],
                childColumns = ["event_key"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["state", "not_before_epoch_ms"], name = "idx_outbox_pending")],
)
data class ScheduleOutboxEntity(
    @PrimaryKey val id: String,
    val generation: Long,
    val command: String,
    @ColumnInfo(name = "event_key") val eventKey: String?,
    val state: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Long,
    @ColumnInfo(name = "not_before_epoch_ms") val notBeforeEpochMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

@Entity(
    tableName = "track_lease",
    primaryKeys = ["snapshot_id", "track_id"],
    foreignKeys =
        [
            ForeignKey(
                entity = WakeRunSnapshotEntity::class,
                parentColumns = ["id"],
                childColumns = ["snapshot_id"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = ImportedTrackEntity::class,
                parentColumns = ["id"],
                childColumns = ["track_id"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index(value = ["track_id"], name = "idx_track_lease_track")],
)
data class TrackLeaseEntity(
    @ColumnInfo(name = "snapshot_id") val snapshotId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "acquired_at") val acquiredAt: Long,
)

@Entity(
    tableName = "schedule_occurrence_claim",
    indices = [Index(value = ["goal_epoch_ms"], name = "idx_occurrence_goal")],
)
data class ScheduleOccurrenceClaimEntity(
    @PrimaryKey @ColumnInfo(name = "canonical_occurrence_key") val canonicalOccurrenceKey: String,
    @ColumnInfo(name = "legacy_alarm_id") val legacyAlarmId: Long?,
    @ColumnInfo(name = "goal_epoch_ms") val goalEpochMs: Long,
    val owner: String,
    val state: String,
    @ColumnInfo(name = "fence_token", defaultValue = "0") val fenceToken: Long,
    @ColumnInfo(name = "claimed_by") val claimedBy: String?,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long?,
)

@Entity(tableName = "legacy_migration_manifest", primaryKeys = ["legacy_alarm_id", "goal_epoch_ms"])
data class LegacyMigrationManifestEntity(
    @ColumnInfo(name = "legacy_alarm_id") val legacyAlarmId: Long,
    @ColumnInfo(name = "goal_epoch_ms") val goalEpochMs: Long,
    @ColumnInfo(name = "pending_intent_identity") val pendingIntentIdentity: String,
    @ColumnInfo(name = "proposed_disposition") val proposedDisposition: String,
    @ColumnInfo(name = "user_confirmed", defaultValue = "0") val userConfirmed: Int,
    @ColumnInfo(name = "terminal_at") val terminalAt: Long?,
)

@Entity(tableName = "legacy_coordinator_state")
data class LegacyCoordinatorStateEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "token_generation", defaultValue = "0") val tokenGeneration: Long,
    @ColumnInfo(name = "scheduled_goal_epoch_ms") val scheduledGoalEpochMs: Long?,
    @ColumnInfo(name = "pending_intent_identity") val pendingIntentIdentity: String?,
    val state: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "legacy_coordinator_member",
    primaryKeys = ["token_generation", "legacy_alarm_id", "goal_epoch_ms"],
    indices =
        [
            Index(
                value = ["token_generation", "goal_epoch_ms"],
                name = "idx_legacy_member_goal",
            )
        ],
)
data class LegacyCoordinatorMemberEntity(
    @ColumnInfo(name = "token_generation") val tokenGeneration: Long,
    @ColumnInfo(name = "legacy_alarm_id") val legacyAlarmId: Long,
    @ColumnInfo(name = "goal_epoch_ms") val goalEpochMs: Long,
)

@Entity(tableName = "migration_state")
data class MigrationStateEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "schedule_owner") val scheduleOwner: String,
    @ColumnInfo(name = "active_generation") val activeGeneration: Long?,
    @ColumnInfo(name = "bootstrap_version", defaultValue = "0") val bootstrapVersion: Long,
    @ColumnInfo(name = "rollback_allowed_until_version", defaultValue = "16")
    val rollbackAllowedUntilVersion: Long,
    @ColumnInfo(name = "handoff_fence_occurrence_id") val handoffFenceOccurrenceId: String?,
    @ColumnInfo(name = "install_epoch") val installEpoch: String,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String?,
    @ColumnInfo(name = "target_storage_key") val targetStorageKey: String?,
    @ColumnInfo(name = "bootstrap_phase") val bootstrapPhase: String?,
    @ColumnInfo(name = "attempt_token") val attemptToken: String?,
)
