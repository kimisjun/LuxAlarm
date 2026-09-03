/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.ZoneId

internal const val MAX_LEGACY_AUDIO_BYTES: Long = 256L * 1024L * 1024L
internal const val MAX_AUDIO_METADATA_UTF8_BYTES = 4 * 1024
/** Defensive decoder bound: legacy alarm audio may not exceed 24 hours. */
internal const val MAX_AUDIO_DURATION_MILLIS: Long = 24L * 60L * 60L * 1000L
internal const val EVIDENCE_SCHEMA_VERSION = 1

internal enum class BootstrapPhase {
    DISCOVERED,
    COPIED,
    VALIDATED,
}

internal data class LegacyAudioBootstrapEvidence(
    val scheduleOwner: String,
    val installEpoch: String,
    val sourceFingerprint: String?,
    val attemptToken: String?,
    val targetStorageKey: String?,
    val bootstrapPhase: String?,
)

internal data class LegacyAudioSourceSnapshot(val sourcePath: String, val sourceFingerprint: String)

/**
 * A descriptor can only originate from persisted 3A evidence and carries a mandatory source
 * recheck.
 */
internal class LegacyAudioBootstrapDescriptor
private constructor(
    val installEpoch: String,
    val sourceFingerprint: String,
    val attemptToken: String,
    val targetStorageKey: String,
    val sourcePath: String,
    val phase: BootstrapPhase,
    private val revalidateSource: () -> LegacyAudioSourceSnapshot,
) {
    internal fun requireCurrentSource() {
        val current = revalidateSource()
        require(current == LegacyAudioSourceSnapshot(sourcePath, sourceFingerprint)) {
            "Legacy audio source no longer matches current discovery preferences"
        }
    }

    internal class Factory(
        private val database: AlarmDatabase,
        legacySource: RoomOpenedLegacyAlarmSource,
        settingsSource: LegacyWakeSettingsSource,
        proposal: Map<Long, LegacyDisposition>,
        private val nowMillis: () -> Long,
        zoneId: ZoneId,
    ) {
        private val proposal = proposal.toMap()
        private val migrator =
            LegacyBootstrapMigrator(
                legacySource,
                settingsSource,
                RoomLegacyDiscoveryStore(database),
                nowMillis,
                zoneId,
            )

        fun create(): LegacyAudioBootstrapDescriptor {
            val discoveryNow = nowMillis()
            val verified = requireCurrentCanonicalDiscovery(discoveryNow)
            return LegacyAudioBootstrapDescriptor(
                verified.evidence.installEpoch,
                requireNotNull(verified.evidence.sourceFingerprint),
                requireNotNull(verified.evidence.attemptToken),
                requireNotNull(verified.evidence.targetStorageKey),
                verified.sourcePath,
                BootstrapPhase.valueOf(requireNotNull(verified.evidence.bootstrapPhase)),
            ) {
                val current = requireCurrentCanonicalDiscovery(nowMillis())
                LegacyAudioSourceSnapshot(current.sourcePath, current.evidence.sourceFingerprint!!)
            }
        }

        private fun requireCurrentCanonicalDiscovery(discoveryNow: Long): VerifiedDiscovery =
            database.runInTransaction<VerifiedDiscovery> {
                val dao = database.legacyBootstrapDao()
                val state =
                    checkNotNull(dao.migrationState()) { "Missing migration_state singleton" }
                        .toLegacyAudioBootstrapEvidence()
                val validated = validateEvidence(state)
                val canonical =
                    migrator.snapshot(
                        LegacyDiscoveryReadiness(validated.installEpoch),
                        proposal,
                        discoveryNow,
                    )
                val persistence = canonical.persistence
                check(dao.manifestRows() == persistence.rows) {
                    "Current Room manifest does not match canonical legacy discovery"
                }
                check(
                    validated.sourceFingerprint == persistence.sourceFingerprint &&
                        validated.attemptToken == persistence.attemptToken &&
                        validated.targetStorageKey == persistence.targetStorageKey
                ) {
                    "Current Room bootstrap identity does not match canonical legacy discovery"
                }
                val sourcePath =
                    checkNotNull(canonical.wakeProfileProposal.profile.importedAudioPath) {
                        "Canonical legacy discovery has no imported audio path"
                    }
                VerifiedDiscovery(validated, sourcePath)
            }

        private data class VerifiedDiscovery(
            val evidence: LegacyAudioBootstrapEvidence,
            val sourcePath: String,
        )
    }
}

internal typealias LegacyAudioBootstrapDescriptorFactory = LegacyAudioBootstrapDescriptor.Factory

internal data class LegacyAudioCopyEvidence(
    val storageKey: String,
    val sha256: String,
    val sizeBytes: Long,
)

internal data class AudioValidationMetadata(
    val title: String,
    val artist: String?,
    val durationMillis: Long,
    val mime: String,
)

internal data class LegacyAudioBootstrapResult(
    val copyEvidence: LegacyAudioCopyEvidence,
    val metadata: AudioValidationMetadata,
)

internal enum class PhaseCasOutcome {
    ADVANCED,
    ALREADY_AT_OR_BEYOND,
    REJECTED,
}

internal interface LegacyAudioBootstrapStatePort {
    /** Must load by exact owner + epoch + fingerprint + token + key, never by the caller phase. */
    fun loadCurrent(descriptor: LegacyAudioBootstrapDescriptor): LegacyAudioBootstrapEvidence

    fun compareAndSetPhase(
        descriptor: LegacyAudioBootstrapDescriptor,
        expected: BootstrapPhase,
        next: BootstrapPhase,
        copyEvidence: LegacyAudioCopyEvidence,
    ): PhaseCasOutcome
}

internal fun interface LegacyAudioDecoder {
    /** Reconciler-owned read-only channel; implementations must not close it. */
    fun validate(channel: FileChannel): AudioValidationMetadata
}

internal enum class LegacyAudioBootstrapFaultPoint {
    AFTER_SOURCE_OPEN,
    AFTER_EVIDENCE_OPEN,
    AFTER_DESTINATION_OPEN_FOR_DECODE,
    AFTER_TEMP_CREATE,
    AFTER_PARTIAL_COPY,
    AFTER_FSYNC,
    BEFORE_DESTINATION_PUBLISH,
    AFTER_FINAL_PARENT_REVALIDATION_BEFORE_LINK,
    AFTER_RENAME,
    BEFORE_COPY_SIDECAR_PUBLISH,
    AFTER_COPY_SIDECAR_PUBLISH,
    AFTER_COPIED_STATE_WRITE,
    AFTER_DECODE,
    BEFORE_METADATA_SIDECAR_PUBLISH,
    AFTER_METADATA_SIDECAR_PUBLISH,
    AFTER_VALIDATED_STATE_WRITE,
}

internal fun interface LegacyAudioBootstrapFaultInjector {
    fun hit(point: LegacyAudioBootstrapFaultPoint)

    companion object {
        val NONE = LegacyAudioBootstrapFaultInjector {}
    }
}

/** Test-only crash signal: models process death by deliberately bypassing in-process cleanup. */
internal class LegacyAudioBootstrapProcessDeath : Error()

internal data class LegacyAudioFileIdentity(
    val device: Long?,
    val inode: Long?,
    val fileKey: Any?,
    val size: Long,
    val modifiedMillis: Long,
)

internal class LegacyAudioOpenedRead(
    val channel: FileChannel,
    val identity: LegacyAudioFileIdentity,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    override fun close() = closeAction()
}

internal interface LegacyAudioFileIdentityPort {
    fun pathIdentity(path: Path): LegacyAudioFileIdentity

    fun openReadOnly(path: Path): LegacyAudioOpenedRead
}

internal object PlatformLegacyAudioFileIdentityPort : LegacyAudioFileIdentityPort {
    private val delegate: LegacyAudioFileIdentityPort by lazy {
        if (System.getProperty("java.vm.name").equals("Dalvik", ignoreCase = true)) {
            AndroidLegacyAudioFileIdentityPort
        } else {
            JvmLegacyAudioFileIdentityPort
        }
    }

    override fun pathIdentity(path: Path) = delegate.pathIdentity(path)

    override fun openReadOnly(path: Path) = delegate.openReadOnly(path)
}

/** Android API 28+: compare lstat(path) directly with fstat(the descriptor actually read). */
internal object AndroidLegacyAudioFileIdentityPort : LegacyAudioFileIdentityPort {
    override fun pathIdentity(path: Path): LegacyAudioFileIdentity =
        Os.lstat(path.toString()).identity()

    override fun openReadOnly(path: Path): LegacyAudioOpenedRead {
        val descriptor =
            Os.open(
                path.toString(),
                OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
                0,
            )
        return openAndroidLegacyAudioRead(
            descriptor,
            streamFactory = { raw ->
                val stream = FileInputStream(raw)
                object : LegacyAudioReadStream {
                    override val channel = stream.channel

                    override fun close() = stream.close()
                }
            },
            descriptorIdentity = { raw -> Os.fstat(raw).identity() },
            descriptorClose = Os::close,
        )
    }

    private fun android.system.StructStat.identity() =
        LegacyAudioFileIdentity(st_dev, st_ino, null, st_size, st_mtime * 1000L)
}

/** JVM-test adapter. Android production always uses descriptor-level fstat above. */
internal object JvmLegacyAudioFileIdentityPort : LegacyAudioFileIdentityPort {
    override fun pathIdentity(path: Path): LegacyAudioFileIdentity {
        val attrs =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return LegacyAudioFileIdentity(
            null,
            null,
            attrs.fileKey(),
            attrs.size(),
            attrs.lastModifiedTime().toMillis(),
        )
    }

    override fun openReadOnly(path: Path): LegacyAudioOpenedRead {
        return openJvmLegacyAudioRead(path, ::pathIdentity)
    }
}

internal interface LegacyAudioReadStream : AutoCloseable {
    val channel: FileChannel
}

internal fun openAndroidLegacyAudioRead(
    descriptor: FileDescriptor,
    streamFactory: (FileDescriptor) -> LegacyAudioReadStream,
    descriptorIdentity: (FileDescriptor) -> LegacyAudioFileIdentity,
    descriptorClose: (FileDescriptor) -> Unit,
): LegacyAudioOpenedRead {
    var stream: LegacyAudioReadStream? = null
    try {
        stream = streamFactory(descriptor)
        val retained = stream
        return LegacyAudioOpenedRead(retained.channel, descriptorIdentity(descriptor)) {
            retained.close()
        }
    } catch (failure: Throwable) {
        val transferred = stream
        if (transferred == null) {
            closeSuppressing(failure) { descriptorClose(descriptor) }
        } else {
            closeSuppressing(failure, transferred::close)
        }
        throw failure
    }
}

internal fun openJvmLegacyAudioRead(
    path: Path,
    pathIdentity: (Path) -> LegacyAudioFileIdentity,
    openChannel: (Path) -> FileChannel = { candidate ->
        FileChannel.open(candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    },
): LegacyAudioOpenedRead {
    val channel = openChannel(path)
    return try {
        LegacyAudioOpenedRead(channel, pathIdentity(path)) { channel.close() }
    } catch (failure: Throwable) {
        closeSuppressing(failure, channel::close)
        throw failure
    }
}

internal inline fun closeSuppressing(primary: Throwable, close: () -> Unit) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        primary.addSuppressed(closeFailure)
    }
}

internal interface LegacyAudioDurabilityPort {
    fun forceFile(channel: FileChannel)

    fun forceDirectory(channel: FileChannel)
}

internal class JvmLegacyAudioDurability(
    private val directoryForce: (FileChannel) -> Unit = { channel -> channel.force(true) }
) : LegacyAudioDurabilityPort {
    override fun forceFile(channel: FileChannel) = channel.force(true)

    override fun forceDirectory(channel: FileChannel) {
        try {
            directoryForce(channel)
        } catch (_: UnsupportedOperationException) {
            // Explicitly unsupported by the provider; file durability still succeeded.
        } catch (e: IOException) {
            if (!e.hasUnsupportedDirectoryErrno()) throw e
        }
    }

    private fun IOException.hasUnsupportedDirectoryErrno(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (
                current is ErrnoException &&
                    current.errno in
                        setOf(OsConstants.EINVAL, OsConstants.EISDIR, OsConstants.ENOTSUP)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
