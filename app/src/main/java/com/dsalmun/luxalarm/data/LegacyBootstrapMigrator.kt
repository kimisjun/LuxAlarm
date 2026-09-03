/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import com.dsalmun.luxalarm.WakeDismissal
import com.dsalmun.luxalarm.WakeProfile
import com.dsalmun.luxalarm.WakeRamp
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Calendar

internal const val MAX_ACTIVE_LEGACY_ALARMS = 64
internal const val MAX_LEGACY_LOCAL_STRING_UTF8_BYTES = 4 * 1024
internal const val MAX_INSTALL_EPOCH_UTF8_BYTES = 256

/** Immutable, read-only view of one legacy alarm. */
internal data class LegacyAlarmSnapshot(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isActive: Boolean,
    val repeatDays: Set<Int>,
    val ringtoneUri: String?,
    val volume: Float,
    val vibrationEnabled: Boolean,
    val skippedOccurrenceDay: Long?,
)

internal fun interface LegacyAlarmSource {
    fun readAlarms(): List<LegacyAlarmSnapshot>
}

internal fun interface LegacyWakeSettingsSource {
    fun readSettings(): LegacyWakeSettingsSnapshot
}

internal data class LegacyWakeSettingsSnapshot(
    val requiredLuxLevel: Float?,
    val rampMinutes: Int?,
    val startVolume: Float?,
    val maxVolume: Float?,
    val dismissal: String?,
    val importedAudioPath: String?,
)

internal enum class LegacyDisposition {
    SELECT_AS_WAKE,
    KEEP_UNTIL_TERMINAL,
    DISABLE_AFTER_CONFIRM,
}

internal data class LegacyWakeProfileProposal(
    val profile: WakeProfile,
    val requiredLuxLevel: Float,
    val fallbackFields: Set<String>,
)

internal data class LegacyDiscoveryPersistence(
    val rows: List<LegacyMigrationManifestEntity>,
    val sourceFingerprint: String,
    val attemptToken: String,
    val targetStorageKey: String?,
    val sourceFingerprintSeed: String,
)

internal interface LegacyDiscoveryStore {
    /** First discovery gate: proves the already-built Room store can read valid migration state. */
    fun requireReady(): LegacyDiscoveryReadiness

    fun persistDiscovery(
        discovery: LegacyDiscoveryPersistence,
        revalidate: () -> LegacyDiscoveryPersistence,
    )
}

internal data class LegacyDiscoveryReadiness(val installEpoch: String)

internal data class LegacyDiscoveryResult(
    val rows: List<LegacyMigrationManifestEntity>,
    val wakeProfileProposal: LegacyWakeProfileProposal,
    val sourceFingerprint: String,
    val attemptToken: String,
    val targetStorageKey: String?,
)

/**
 * Runs only after Room is open; discovery never schedules, activates, copies, or mutates legacy
 * data.
 */
internal class LegacyBootstrapMigrator(
    private val legacySource: LegacyAlarmSource,
    private val settingsSource: LegacyWakeSettingsSource,
    private val store: LegacyDiscoveryStore,
    private val nowMillis: () -> Long,
    private val zoneId: ZoneId,
) {
    private data class Snapshot(
        val persistence: LegacyDiscoveryPersistence,
        val wakeProfileProposal: LegacyWakeProfileProposal,
    )

    fun discover(proposal: Map<Long, LegacyDisposition>): LegacyDiscoveryResult {
        val readiness = store.requireReady()
        requireBoundedUtf8("install epoch", readiness.installEpoch, MAX_INSTALL_EPOCH_UTF8_BYTES)
        require(readiness.installEpoch.isNotBlank()) { "Install epoch must not be blank" }
        val discoveryNow = nowMillis()
        val snapshot = snapshot(readiness, proposal, discoveryNow)
        val persistence = snapshot.persistence
        store.persistDiscovery(persistence) {
            // Discovery is read-only; confirmation reruns discovery and revalidates these sources.
            // This check narrows, but cannot make atomic, the cross-store SharedPreferences window.
            val revalidated = snapshot(readiness, proposal, discoveryNow)
            check(revalidated == snapshot) { "Legacy discovery sources changed before persistence" }
            revalidated.persistence
        }
        return LegacyDiscoveryResult(
            persistence.rows,
            snapshot.wakeProfileProposal,
            persistence.sourceFingerprint,
            persistence.attemptToken,
            persistence.targetStorageKey,
        )
    }

    private fun snapshot(
        readiness: LegacyDiscoveryReadiness,
        proposal: Map<Long, LegacyDisposition>,
        discoveryNow: Long,
    ): Snapshot {
        val settings = settingsSource.readSettings()
        val activeAlarms = ArrayList<LegacyAlarmSnapshot>(MAX_ACTIVE_LEGACY_ALARMS)
        legacySource.readAlarms().forEach { alarm ->
            if (!alarm.isActive) return@forEach
            require(activeAlarms.size < MAX_ACTIVE_LEGACY_ALARMS) {
                "Too many active legacy alarms"
            }
            validateAlarm(alarm)
            activeAlarms += alarm
        }
        require(activeAlarms.map { it.id }.distinct().size == activeAlarms.size) {
            "Active legacy alarm ids must be unique"
        }
        val candidates = activeAlarms.map { alarm ->
            alarm to
                nextTrigger(
                    alarm.hour,
                    alarm.minute,
                    alarm.repeatDays,
                    discoveryNow,
                    zoneId,
                    alarm.skippedOccurrenceDay,
                )
        }
        validateProposal(candidates.map { it.first.id.toLong() }.toSet(), proposal)
        val rows =
            candidates
                .map { (alarm, goal) ->
                    LegacyMigrationManifestEntity(
                        legacyAlarmId = alarm.id.toLong(),
                        goalEpochMs = goal,
                        pendingIntentIdentity = legacyPendingIntentIdentity(),
                        proposedDisposition = proposal.getValue(alarm.id.toLong()).name,
                        userConfirmed = 0,
                        terminalAt = null,
                    )
                }
                .sortedWith(compareBy({ it.goalEpochMs }, { it.legacyAlarmId }))
        val wakeProfileProposal = profileProposal(settings)
        val fingerprintSeed = fingerprintSeed(settings, wakeProfileProposal, candidates, proposal)
        val fingerprint = legacyDiscoverySourceFingerprint(fingerprintSeed, rows)
        val token = legacyDiscoveryAttemptToken(readiness.installEpoch, fingerprint)
        val target =
            wakeProfileProposal.profile.importedAudioPath?.let {
                "bootstrap/$token/legacy-audio"
            }
        return Snapshot(
            LegacyDiscoveryPersistence(rows, fingerprint, token, target, fingerprintSeed),
            wakeProfileProposal,
        )
    }

    private fun validateProposal(ids: Set<Long>, proposal: Map<Long, LegacyDisposition>) {
        require(proposal.keys == ids) { "Proposal must assign every and only active legacy alarm" }
        val selected = proposal.values.count { it == LegacyDisposition.SELECT_AS_WAKE }
        require((ids.isEmpty() && selected == 0) || (ids.isNotEmpty() && selected == 1)) {
            "Proposal must select exactly one active legacy alarm"
        }
    }

    private fun validateAlarm(alarm: LegacyAlarmSnapshot) {
        require(alarm.id > 0) { "Legacy alarm id must be positive" }
        require(alarm.hour in 0..23 && alarm.minute in 0..59) {
            "Legacy alarm wall time is invalid"
        }
        require(alarm.repeatDays.size <= 7) { "Legacy alarm has too many repeat days" }
        require(alarm.repeatDays.all { it in Calendar.SUNDAY..Calendar.SATURDAY }) {
            "Legacy alarm repeat day is invalid"
        }
        require(alarm.volume.isFinite()) { "Legacy alarm volume must be finite" }
        alarm.ringtoneUri?.let {
            requireBoundedUtf8("legacy ringtone URI", it, MAX_LEGACY_LOCAL_STRING_UTF8_BYTES)
        }
    }

    private fun fingerprintSeed(
        rawSettings: LegacyWakeSettingsSnapshot,
        settings: LegacyWakeProfileProposal,
        candidates: List<Pair<LegacyAlarmSnapshot, Long>>,
        proposal: Map<Long, LegacyDisposition>,
    ): String =
        CanonicalEncoder.record("legacy-discovery-source", 1) {
                encodedRecord(
                    "settings",
                    CanonicalEncoder.record("legacy-wake-settings", 1) {
                        float("requiredLuxLevel", settings.requiredLuxLevel)
                        int("rampMinutes", settings.profile.rampMinutes)
                        float("startVolume", settings.profile.startVolume)
                        float("maxVolume", settings.profile.maxVolume)
                        enum("dismissal", settings.profile.dismissal)
                        nullableString("importedAudioPath", settings.profile.importedAudioPath)
                        nullableString(
                            "legacyDismissalSource",
                            rawSettings.dismissal?.let { raw ->
                                WakeDismissal.entries.firstOrNull { it.name == raw }?.name
                                    ?: "INVALID"
                            },
                        )
                        sortedStrings("fallbackFields", settings.fallbackFields)
                    },
                )
                records(
                    "alarms",
                    candidates.map { (alarm, goal) ->
                        CanonicalEncoder.record("legacy-alarm-candidate", 1) {
                            int("id", alarm.id)
                            int("hour", alarm.hour)
                            int("minute", alarm.minute)
                            boolean("isActive", alarm.isActive)
                            sortedInts("repeatDays", alarm.repeatDays)
                            nullableString("ringtoneUri", alarm.ringtoneUri)
                            float("volume", alarm.volume)
                            boolean("vibrationEnabled", alarm.vibrationEnabled)
                            nullableLong("skippedOccurrenceDay", alarm.skippedOccurrenceDay)
                            long("goalEpochMs", goal)
                            enum("proposedDisposition", proposal.getValue(alarm.id.toLong()))
                            string("pendingIntentIdentity", legacyPendingIntentIdentity())
                        }
                    },
                )
            }
            .toString(StandardCharsets.UTF_8)

    private fun profileProposal(raw: LegacyWakeSettingsSnapshot): LegacyWakeProfileProposal {
        val fallbacks = linkedSetOf<String>()
        fun finiteIn(
            name: String,
            value: Float?,
            range: ClosedFloatingPointRange<Float>,
            fallback: Float,
        ): Float =
            if (value != null && value.isFinite() && value in range) value
            else fallback.also { fallbacks += name }
        val ramp =
            raw.rampMinutes?.takeIf { it in 1..60 }
                ?: WakeRamp.DEFAULT_RAMP_MINUTES.also { fallbacks += "rampMinutes" }
        val start = finiteIn("startVolume", raw.startVolume, 0f..1f, WakeRamp.DEFAULT_START_VOLUME)
        var max = finiteIn("maxVolume", raw.maxVolume, 0f..1f, WakeRamp.DEFAULT_MAX_VOLUME)
        if (max < start)
            max = WakeRamp.DEFAULT_MAX_VOLUME.coerceAtLeast(start).also { fallbacks += "maxVolume" }
        val dismissal =
            WakeDismissal.entries.firstOrNull { it.name == raw.dismissal }
                ?: WakeDismissal.DEFAULT.also { fallbacks += "dismissal" }
        val lux = finiteIn("requiredLuxLevel", raw.requiredLuxLevel, 1f..1000f, 50f)
        val importedAudioPath =
            raw.importedAudioPath?.takeIf {
                it.isNotBlank() && utf8LengthAtMost(it, MAX_LEGACY_LOCAL_STRING_UTF8_BYTES) != null
            }
        if (raw.importedAudioPath != null && importedAudioPath == null)
            fallbacks += "importedAudioPath"
        return LegacyWakeProfileProposal(
            WakeProfile(ramp, start, max, dismissal, importedAudioPath),
            lux,
            fallbacks,
        )
    }
}

internal fun legacyPendingIntentIdentity(): String = LEGACY_ALARM_PENDING_INTENT_SPEC.identity()

internal fun legacyDiscoveryAttemptTokenPayload(
    installEpoch: String,
    fingerprint: String,
): ByteArray =
    CanonicalEncoder.record("legacy-bootstrap-attempt-token", 1) {
        string("installEpoch", installEpoch)
        string("sourceFingerprint", fingerprint)
    }

internal fun legacyDiscoveryAttemptToken(installEpoch: String, fingerprint: String): String =
    sha256(legacyDiscoveryAttemptTokenPayload(installEpoch, fingerprint))

internal fun legacyDiscoverySourceFingerprint(
    seed: String,
    rows: List<LegacyMigrationManifestEntity>,
): String =
    "legacy-canonical-v1:${sha256(legacySourceFingerprintSeedPayload(seed))}:" +
        sha256(legacyManifestRowsPayload(rows))

internal fun legacySourceFingerprintSeedPayload(seed: String): ByteArray =
    CanonicalEncoder.record("legacy-source-fingerprint-seed", 1) {
        string("discoverySource", seed)
    }

internal fun legacyDiscoveryRowsMatchFingerprint(
    fingerprint: String,
    rows: List<LegacyMigrationManifestEntity>,
): Boolean {
    val parts = fingerprint.split(':')
    return parts.size == 3 &&
        parts[0] == "legacy-canonical-v1" &&
        parts[1].matches(Regex("[0-9a-f]{64}")) &&
        parts[2] == sha256(legacyManifestRowsPayload(rows))
}

internal fun legacyManifestRowsPayload(rows: List<LegacyMigrationManifestEntity>): ByteArray =
    CanonicalEncoder.record("legacy-migration-manifest", 1) {
        records(
            "rows",
            rows.map { row ->
                CanonicalEncoder.record("legacy-migration-manifest-row", 1) {
                    long("legacyAlarmId", row.legacyAlarmId)
                    long("goalEpochMs", row.goalEpochMs)
                    string("pendingIntentIdentity", row.pendingIntentIdentity)
                    enum(
                        "proposedDisposition",
                        LegacyDisposition.valueOf(row.proposedDisposition),
                    )
                    int("userConfirmed", row.userConfirmed)
                    nullableLong("terminalAt", row.terminalAt)
                }
            },
        )
    }

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
