/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.security.MessageDigest

private const val LEGACY_IMPORTED_TRACK_ID_PREFIX = "legacy-track-v1:"

internal fun legacyImportedTrackIdentityPayload(
    installEpoch: String,
    sourceFingerprint: String,
    attemptToken: String,
    targetStorageKey: String,
): ByteArray =
    CanonicalEncoder.record("legacy-imported-track-id", 1) {
        string("install-epoch", installEpoch)
        string("source-fingerprint", sourceFingerprint)
        string("attempt-token", attemptToken)
        string("target-storage-key", targetStorageKey)
    }

internal fun legacyImportedTrackId(
    installEpoch: String,
    sourceFingerprint: String,
    attemptToken: String,
    targetStorageKey: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(
        legacyImportedTrackIdentityPayload(
            installEpoch,
            sourceFingerprint,
            attemptToken,
            targetStorageKey,
        )
    )
    return LEGACY_IMPORTED_TRACK_ID_PREFIX + digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class LegacyAudioBootstrapCommitResult(val track: ImportedTrackEntity)

internal enum class LegacyAudioCommitFaultPoint {
    BEFORE_INSERT,
    AFTER_INSERT_BEFORE_CAS,
    AFTER_CAS_BEFORE_RETURN,
    AFTER_TRANSACTION_COMMIT_BEFORE_RETURN,
}

internal fun interface LegacyAudioCommitFaultInjector {
    fun hit(point: LegacyAudioCommitFaultPoint)

    companion object {
        val NONE = LegacyAudioCommitFaultInjector {}
    }
}

/** Atomic Room boundary from durable VALIDATED evidence to an available imported track. */
internal class RoomLegacyAudioBootstrapCommitter(
    private val database: AlarmDatabase,
    private val faults: LegacyAudioCommitFaultInjector = LegacyAudioCommitFaultInjector.NONE,
) {
    fun commit(
        descriptor: LegacyAudioBootstrapDescriptor,
        result: LegacyAudioBootstrapResult,
        addedAt: Long,
    ): LegacyAudioBootstrapCommitResult {
        descriptor.requireCurrentSource()
        validateDescriptor(descriptor)
        validateResult(descriptor, result, addedAt)
        val expected = expectedTrack(descriptor, result, addedAt)
        val committed =
            database.runInTransaction<ImportedTrackEntity> {
                val dao = database.legacyBootstrapDao()
                val state = loadExact(dao, descriptor)
                check(
                    state.bootstrapPhase == BootstrapPhase.VALIDATED.name ||
                        state.bootstrapPhase == BootstrapPhase.COMMITTED.name
                ) {
                    "Legacy audio commit requires VALIDATED or COMMITTED phase"
                }
                descriptor.requireCurrentSource()
                if (state.bootstrapPhase == BootstrapPhase.COMMITTED.name) {
                    return@runInTransaction requireExactCommittedTrack(dao, expected)
                }

                faults.hit(LegacyAudioCommitFaultPoint.BEFORE_INSERT)
                val inserted = dao.insertImportedTrack(expected) != -1L
                val stored =
                    if (inserted) expected
                    else
                        checkNotNull(dao.importedTrackById(expected.id)) {
                            "Imported track insert conflicted with a different identity"
                        }
                check(trackExactlyMatchesValidatedInsert(stored, expected)) {
                    "Imported track identity conflicts with validated legacy audio"
                }
                faults.hit(LegacyAudioCommitFaultPoint.AFTER_INSERT_BEFORE_CAS)
                val changed =
                    dao.compareAndSetAudioBootstrapPhase(
                        descriptor.installEpoch,
                        descriptor.sourceFingerprint,
                        descriptor.targetStorageKey,
                        descriptor.attemptToken,
                        BootstrapPhase.VALIDATED.name,
                        BootstrapPhase.COMMITTED.name,
                    )
                if (changed != 1) {
                    val reloaded = loadExact(dao, descriptor)
                    check(reloaded.bootstrapPhase == BootstrapPhase.COMMITTED.name) {
                        "Failed to commit legacy audio migration state"
                    }
                    requireExactCommittedTrack(dao, expected)
                } else {
                    faults.hit(LegacyAudioCommitFaultPoint.AFTER_CAS_BEFORE_RETURN)
                    stored
                }
            }
        faults.hit(LegacyAudioCommitFaultPoint.AFTER_TRANSACTION_COMMIT_BEFORE_RETURN)
        return LegacyAudioBootstrapCommitResult(committed)
    }

    private fun loadExact(
        dao: LegacyBootstrapDao,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): MigrationStateEntity =
        checkNotNull(
            dao.exactLegacyAudioBootstrapState(
                descriptor.installEpoch,
                descriptor.sourceFingerprint,
                descriptor.targetStorageKey,
                descriptor.attemptToken,
            )
        ) {
            "No current LEGACY bootstrap evidence matches descriptor identity"
        }

    private fun requireExactCommittedTrack(
        dao: LegacyBootstrapDao,
        expected: ImportedTrackEntity,
    ): ImportedTrackEntity {
        val stored =
            checkNotNull(dao.importedTrackById(expected.id)) {
                "COMMITTED legacy audio is missing its imported track"
            }
        check(trackMatchesCommittedRetryIgnoringCallerAddedAt(stored, expected)) {
            "COMMITTED legacy audio imported track does not match validated evidence"
        }
        return stored
    }
}

private fun validateDescriptor(descriptor: LegacyAudioBootstrapDescriptor) {
    validateEvidence(
        LegacyAudioBootstrapEvidence(
            "LEGACY",
            descriptor.installEpoch,
            descriptor.sourceFingerprint,
            descriptor.attemptToken,
            descriptor.targetStorageKey,
            descriptor.phase.name,
        )
    )
}

private fun validateResult(
    descriptor: LegacyAudioBootstrapDescriptor,
    result: LegacyAudioBootstrapResult,
    addedAt: Long,
) {
    val copy = result.copyEvidence
    val metadata = result.metadata
    require(copy.storageKey == descriptor.targetStorageKey) { "Copy evidence storage key conflict" }
    require(copy.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid copy SHA-256" }
    require(copy.sizeBytes in 1..MAX_LEGACY_AUDIO_BYTES) { "Invalid legacy audio size" }
    require(metadata.title.isNotBlank()) { "Audio title must not be blank" }
    requireBoundedUtf8("Audio title", metadata.title, MAX_AUDIO_METADATA_UTF8_BYTES)
    metadata.artist?.let {
        requireBoundedUtf8("Audio artist", it, MAX_AUDIO_METADATA_UTF8_BYTES)
    }
    require(metadata.durationMillis in 1..MAX_AUDIO_DURATION_MILLIS) {
        "Invalid audio duration"
    }
    require(metadata.mime.isNotBlank()) { "Audio MIME type must not be blank" }
    requireBoundedUtf8("Audio MIME type", metadata.mime, 256)
    require(addedAt >= 0) { "Imported track addedAt must not be negative" }
}

private fun expectedTrack(
    descriptor: LegacyAudioBootstrapDescriptor,
    result: LegacyAudioBootstrapResult,
    addedAt: Long,
) =
    ImportedTrackEntity(
        id =
            legacyImportedTrackId(
                descriptor.installEpoch,
                descriptor.sourceFingerprint,
                descriptor.attemptToken,
                descriptor.targetStorageKey,
            ),
        storageKey = result.copyEvidence.storageKey,
        title = result.metadata.title,
        artist = result.metadata.artist,
        durationMs = result.metadata.durationMillis,
        mimeType = result.metadata.mime,
        contentHash = result.copyEvidence.sha256,
        lifecycleState = "AVAILABLE",
        availability = "AVAILABLE",
        deletionToken = null,
        refCountCache = 0,
        addedAt = addedAt,
    )

/** A VALIDATED insert conflict must reproduce the complete row, including caller addedAt. */
private fun trackExactlyMatchesValidatedInsert(
    stored: ImportedTrackEntity,
    expected: ImportedTrackEntity,
): Boolean = stored == expected

/** Caller clocks are not part of COMMITTED retry identity; the committed row owns addedAt. */
private fun trackMatchesCommittedRetryIgnoringCallerAddedAt(
    stored: ImportedTrackEntity,
    expected: ImportedTrackEntity,
): Boolean = stored.copy(addedAt = expected.addedAt) == expected
