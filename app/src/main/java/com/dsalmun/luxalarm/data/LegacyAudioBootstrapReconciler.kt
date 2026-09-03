/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Crash-reconciling filesystem half of legacy bootstrap; track insertion remains Task 3B2. */
internal class LegacyAudioBootstrapReconciler(
    private val filesDir: File,
    private val state: LegacyAudioBootstrapStatePort,
    private val decoder: LegacyAudioDecoder,
    private val faults: LegacyAudioBootstrapFaultInjector = LegacyAudioBootstrapFaultInjector.NONE,
    private val durability: LegacyAudioDurabilityPort = JvmLegacyAudioDurability(),
    private val identities: LegacyAudioFileIdentityPort = PlatformLegacyAudioFileIdentityPort,
    private val capabilities: LegacyAudioCapabilityFactory = PlatformLegacyAudioCapabilityFactory,
) {
    fun reconcile(descriptor: LegacyAudioBootstrapDescriptor): LegacyAudioBootstrapResult {
        var current = validateCurrent(state.loadCurrent(descriptor), descriptor)
        descriptor.requireCurrentSource()

        val root = requireRoot()
        val source = resolveSource(root, descriptor.sourcePath)
        val attemptPath = root.resolve(descriptor.targetStorageKey).normalize().parent
        require(!source.startsWith(attemptPath)) {
            "Legacy source must not alias bootstrap evidence or work namespace"
        }

        // Capability acquisition may create the token directory, so canonical discovery is
        // revalidated immediately before it. An unavailable capability fails before that mutation.
        descriptor.requireCurrentSource()
        return capabilities.acquire(filesDir, descriptor).use { attempt ->
            ensureDurableIntent(attempt, descriptor)
            rejectSourceAliases(source, attempt)
            var phase = BootstrapPhase.valueOf(requireNotNull(current.bootstrapPhase))
            var durableCopy =
                readOptionalEvidence(
                        attempt,
                        COPY_EVIDENCE_NAME,
                        descriptor,
                        BootstrapPhase.COPIED,
                        mayRewritePartial = phase == BootstrapPhase.DISCOVERED,
                    )
                    ?.copy
            var verifiedCopy = durableCopy?.let { VerifiedCopy(it, sourceVerified = false) }

            if (phase >= BootstrapPhase.COPIED) {
                requireNotNull(durableCopy) {
                    "Advanced bootstrap phase is missing durable copy evidence"
                }
            } else if (durableCopy == null) {
                val discovered = ensureDiscoveredCopy(source, attempt, descriptor.targetStorageKey)
                val evidence =
                    SidecarEvidence.from(
                        descriptor,
                        BootstrapPhase.COPIED,
                        discovered.copyEvidence,
                        null,
                    )
                writeDurableSidecar(
                    attempt,
                    COPY_EVIDENCE_NAME,
                    evidence,
                    mayRewritePartial = true,
                )
                durableCopy =
                    readSidecar(attempt, COPY_EVIDENCE_NAME, descriptor, BootstrapPhase.COPIED).copy
                require(durableCopy == discovered.copyEvidence) {
                    "Durable copy evidence changed after publication"
                }
                verifiedCopy = VerifiedCopy(durableCopy, sourceVerified = true)
            }
            val verified = requireNotNull(verifiedCopy)
            val copy = verified.copyEvidence
            ensureApprovedDestination(source, attempt, copy, verified.sourceVerified)

            if (phase == BootstrapPhase.DISCOVERED) {
                descriptor.requireCurrentSource()
                requireAdvance(
                    state.compareAndSetPhase(
                        descriptor,
                        BootstrapPhase.DISCOVERED,
                        BootstrapPhase.COPIED,
                        copy,
                    ),
                    "COPIED",
                )
                faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_COPIED_STATE_WRITE)
                current = validateCurrent(state.loadCurrent(descriptor), descriptor)
                phase = BootstrapPhase.valueOf(requireNotNull(current.bootstrapPhase))
            }

            val persistedMetadataEvidence =
                readOptionalEvidence(
                    attempt,
                    METADATA_EVIDENCE_NAME,
                    descriptor,
                    BootstrapPhase.VALIDATED,
                    mayRewritePartial = phase == BootstrapPhase.COPIED,
                )
            persistedMetadataEvidence?.let {
                require(it.copy == copy) {
                    "Durable metadata evidence references different copied bytes"
                }
            }
            var durableMetadata = persistedMetadataEvidence?.metadata
            if (phase == BootstrapPhase.VALIDATED) {
                requireNotNull(durableMetadata) {
                    "VALIDATED bootstrap phase is missing durable metadata evidence"
                }
            } else if (durableMetadata == null) {
                val metadata = decodeApprovedDestination(attempt, copy)
                faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_DECODE)
                writeDurableSidecar(
                    attempt,
                    METADATA_EVIDENCE_NAME,
                    SidecarEvidence.from(descriptor, BootstrapPhase.VALIDATED, copy, metadata),
                    mayRewritePartial = phase == BootstrapPhase.COPIED,
                )
                durableMetadata =
                    requireNotNull(
                        readSidecar(
                                attempt,
                                METADATA_EVIDENCE_NAME,
                                descriptor,
                                BootstrapPhase.VALIDATED,
                            )
                            .metadata
                    )
            }
            val metadata = requireNotNull(durableMetadata)

            if (phase == BootstrapPhase.COPIED) {
                descriptor.requireCurrentSource()
                requireAdvance(
                    state.compareAndSetPhase(
                        descriptor,
                        BootstrapPhase.COPIED,
                        BootstrapPhase.VALIDATED,
                        copy,
                    ),
                    "VALIDATED",
                )
                faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_VALIDATED_STATE_WRITE)
            }
            LegacyAudioBootstrapResult(copy, metadata)
        }
    }

    private fun requireAdvance(outcome: PhaseCasOutcome, name: String) {
        check(outcome != PhaseCasOutcome.REJECTED) {
            "Bootstrap phase rejected while recording $name"
        }
    }

    private fun validateCurrent(
        evidence: LegacyAudioBootstrapEvidence,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioBootstrapEvidence {
        val valid = validateEvidence(evidence)
        require(valid.installEpoch == descriptor.installEpoch) { "Current install epoch conflict" }
        require(valid.sourceFingerprint == descriptor.sourceFingerprint) {
            "Current fingerprint conflict"
        }
        require(valid.attemptToken == descriptor.attemptToken) { "Current attempt token conflict" }
        require(valid.targetStorageKey == descriptor.targetStorageKey) {
            "Current storage key conflict"
        }
        return valid
    }

    private fun requireRoot(): Path {
        val candidate = filesDir.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(candidate)) { "filesDir must not be a symlink" }
        val attrs =
            Files.readAttributes(
                candidate,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        require(attrs.isDirectory && !attrs.isSymbolicLink && attrs.fileKey() != null) {
            "filesDir must be a stable real directory"
        }
        return candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
    }

    private fun resolveSource(root: Path, raw: String): Path {
        val supplied = Paths.get(raw)
        require(supplied.none { it.toString() == ".." }) { "Legacy source traversal is forbidden" }
        val candidate =
            if (supplied.isAbsolute) supplied.normalize() else root.resolve(supplied).normalize()
        require(candidate.startsWith(root)) { "Legacy source is outside filesDir" }
        rejectSymlinkComponents(root, candidate)
        val real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(real.startsWith(root)) { "Legacy source is outside filesDir" }
        require(
            Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(real)
        ) {
            "Legacy source is not a regular file"
        }
        return real
    }

    private fun rejectSymlinkComponents(root: Path, candidate: Path) {
        var current = root
        root.relativize(candidate).forEach { component ->
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "Symlink path component is forbidden" }
            }
        }
    }

    private fun rejectSourceAliases(source: Path, attempt: LegacyAudioAttemptCapability) {
        val sourceIdentity = requireRegularIdentity(source)
        RESERVED_NAMES.forEach { name ->
            attempt.identity(name)?.let { reserved ->
                require(!sourceIdentity.sameFileObjectForBootstrap(reserved)) {
                    "Legacy source aliases reserved bootstrap entry $name"
                }
            }
        }
    }

    private fun ensureDurableIntent(
        attempt: LegacyAudioAttemptCapability,
        descriptor: LegacyAudioBootstrapDescriptor,
    ) {
        val expected = LegacyAudioIntentEvidence.from(descriptor)
        if (attempt.identity(INTENT_EVIDENCE_NAME) != null) {
            require(
                LegacyAudioIntentEvidenceCodec.decode(readBounded(attempt, INTENT_EVIDENCE_NAME)) ==
                    expected
            ) {
                "Intent evidence identity conflict"
            }
            attempt.forceDirectory(durability)
            return
        }
        require(
            RESERVED_NAMES.none { name ->
                name != INTENT_EVIDENCE_NAME && attempt.identity(name) != null
            }
        ) {
            "Reserved bootstrap entry exists without durable intent ownership"
        }
        val bytes = LegacyAudioIntentEvidenceCodec.encode(expected)
        val channel =
            try {
                attempt.createNew(INTENT_EVIDENCE_NAME)
            } catch (conflict: FileAlreadyExistsException) {
                require(
                    LegacyAudioIntentEvidenceCodec.decode(
                        readBounded(attempt, INTENT_EVIDENCE_NAME)
                    ) == expected
                ) {
                    "Concurrent intent evidence identity conflict"
                }
                attempt.forceDirectory(durability)
                return
            }
        channel.use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            durability.forceFile(output)
        }
        require(readBounded(attempt, INTENT_EVIDENCE_NAME).contentEquals(bytes)) {
            "Durable intent evidence failed validation"
        }
        attempt.forceDirectory(durability)
    }

    private fun digestRegularFile(path: Path, sourceObservation: Boolean = false): Digest =
        withStableRegularChannel(path, sourceObservation) { channel -> digestChannel(channel) }

    private fun digestChannel(
        channel: FileChannel,
        maxBytes: Long = MAX_LEGACY_AUDIO_BYTES,
    ): Digest {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
        while (true) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "File exceeds size limit" }
            digest.update(buffer.array(), 0, count)
        }
        require(total > 0) { "File size is invalid" }
        return Digest(hex(digest.digest()), total)
    }

    private fun <T> withStableRegularChannel(
        path: Path,
        sourceObservation: Boolean,
        block: (FileChannel) -> T,
    ): T {
        val before = requireRegularIdentity(path)
        return identities.openReadOnly(path).use { opened ->
            val channel = opened.channel
            val afterOpen = requireRegularIdentity(path)
            require(
                before == opened.identity && before == afterOpen && channel.size() == before.size
            ) {
                "Regular file identity changed while opening"
            }
            if (sourceObservation) faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_SOURCE_OPEN)
            val result = block(channel)
            require(channel.size() == before.size && requireRegularIdentity(path) == before) {
                "Regular file identity changed while reading"
            }
            result
        }
    }

    private fun requireRegularIdentity(path: Path): LegacyAudioFileIdentity {
        val attrs =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Path is not a regular file" }
        require(attrs.size() in 1..MAX_LEGACY_AUDIO_BYTES) { "Legacy audio size is invalid" }
        val identity = identities.pathIdentity(path)
        require(identity.fileKey != null || (identity.device != null && identity.inode != null)) {
            "Filesystem does not expose stable file identity"
        }
        require(identity.size == attrs.size()) { "Conflicting file identity observations" }
        return identity
    }

    private fun capabilityDigest(
        attempt: LegacyAudioAttemptCapability,
        name: String,
        maxBytes: Long = MAX_LEGACY_AUDIO_BYTES,
    ): Digest =
        withStableCapabilityChannel(attempt, name) { channel -> digestChannel(channel, maxBytes) }

    private fun <T> withStableCapabilityChannel(
        attempt: LegacyAudioAttemptCapability,
        name: String,
        block: (FileChannel) -> T,
    ): T {
        val before = requireNotNull(attempt.identity(name)) { "Missing capability entry $name" }
        require(before.fileKey != null || (before.device != null && before.inode != null)) {
            "Filesystem does not expose stable file identity"
        }
        return attempt.openRead(name).use { opened ->
            require(before.sameFileObjectForBootstrap(opened.identity)) {
                "Capability entry identity changed while opening"
            }
            val result = block(opened.channel)
            require(attempt.identity(name)?.sameFileObjectForBootstrap(before) == true) {
                "Capability entry identity changed while reading"
            }
            result
        }
    }

    private fun decodeApprovedDestination(
        attempt: LegacyAudioAttemptCapability,
        approved: LegacyAudioCopyEvidence,
    ): AudioValidationMetadata {
        val expected = Digest(approved.sha256, approved.sizeBytes)
        return withStableCapabilityChannel(attempt, COPY_SLOT_NAME) { channel ->
            require(digestChannel(channel) == expected) {
                "Decode handle mismatches approved bytes"
            }
            channel.position(0)
            faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_DESTINATION_OPEN_FOR_DECODE)
            val metadata = validateMetadata(decoder.validate(channel))
            require(digestChannel(channel) == expected) { "Decoded destination bytes changed" }
            metadata
        }
    }

    private fun ensureApprovedDestination(
        source: Path,
        attempt: LegacyAudioAttemptCapability,
        approved: LegacyAudioCopyEvidence,
        sourceVerified: Boolean,
    ) {
        val expected = Digest(approved.sha256, approved.sizeBytes)
        if (!sourceVerified) {
            require(digestRegularFile(source, sourceObservation = true) == expected) {
                "Current source no longer matches durable copy evidence"
            }
        }
        require(attempt.identity(COPY_SLOT_NAME) != null) {
            "Approved copy slot is missing"
        }
        require(capabilityDigest(attempt, COPY_SLOT_NAME) == expected) {
            "Existing copy slot mismatches durable copy evidence"
        }
    }

    private fun ensureDiscoveredCopy(
        source: Path,
        attempt: LegacyAudioAttemptCapability,
        storageKey: String,
    ): VerifiedCopy {
        val existing = attempt.identity(COPY_SLOT_NAME)
        lateinit var ownedIdentity: LegacyAudioFileIdentity
        lateinit var copied: Digest
        var rewrote = false
        val verified =
            withStableRegularChannel(source, sourceObservation = true) { input ->
                val sourceDigest = digestChannel(input)
                if (existing != null) {
                    val completed =
                        runCatching { capabilityDigest(attempt, COPY_SLOT_NAME) }.getOrNull()
                    if (completed == sourceDigest) {
                        return@withStableRegularChannel VerifiedCopy(
                            LegacyAudioCopyEvidence(storageKey, completed.sha256, completed.size),
                            sourceVerified = true,
                        )
                    }
                }

                faults.hit(LegacyAudioBootstrapFaultPoint.BEFORE_DESTINATION_PUBLISH)
                faults.hit(
                    LegacyAudioBootstrapFaultPoint.AFTER_FINAL_PARENT_REVALIDATION_BEFORE_LINK
                )
                val output =
                    if (existing == null) {
                        try {
                            attempt.createNew(COPY_SLOT_NAME)
                        } catch (conflict: FileAlreadyExistsException) {
                            throw IllegalArgumentException(
                                "Concurrent copy slot already exists",
                                conflict,
                            )
                        }
                    } else {
                        attempt.openRewrite(COPY_SLOT_NAME, existing)
                    }
                ownedIdentity =
                    requireNotNull(attempt.identity(COPY_SLOT_NAME)) {
                        "Copy slot disappeared after open"
                    }
                output.use { channel ->
                    faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_TEMP_CREATE)
                    copied = copyOpenedSource(input, channel)
                    durability.forceFile(channel)
                }
                require(digestChannel(input) == copied) {
                    "Legacy source bytes changed while copying"
                }
                rewrote = true
                VerifiedCopy(
                    LegacyAudioCopyEvidence(storageKey, copied.sha256, copied.size),
                    sourceVerified = true,
                )
            }
        if (!rewrote) return verified
        faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_FSYNC)
        require(
            attempt.identity(COPY_SLOT_NAME)?.sameFileObjectForBootstrap(ownedIdentity) == true
        ) {
            "Copy slot name changed while writing"
        }
        require(capabilityDigest(attempt, COPY_SLOT_NAME) == copied) {
            "Durable copy slot failed validation"
        }
        faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_RENAME)
        attempt.forceDirectory(durability)
        return verified
    }

    private fun copyOpenedSource(input: FileChannel, output: FileChannel): Digest {
        input.position(0)
        output.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var partialVisited = false
        val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
        while (true) {
            buffer.clear()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_LEGACY_AUDIO_BYTES) { "Legacy audio exceeds size limit" }
            digest.update(buffer.array(), 0, count)
            buffer.flip()
            while (buffer.hasRemaining()) output.write(buffer)
            if (!partialVisited) {
                partialVisited = true
                faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY)
            }
        }
        require(total > 0) { "Legacy audio size is invalid" }
        return Digest(hex(digest.digest()), total)
    }

    private fun validateMetadata(value: AudioValidationMetadata): AudioValidationMetadata {
        require(value.durationMillis in 1..MAX_AUDIO_DURATION_MILLIS) {
            "Decoded duration must be positive and at most 24 hours"
        }
        require(value.title.isNotBlank()) { "Decoded title must not be blank" }
        requireBoundedUtf8("decoded title", value.title, MAX_AUDIO_METADATA_UTF8_BYTES)
        value.artist?.let {
            requireBoundedUtf8("decoded artist", it, MAX_AUDIO_METADATA_UTF8_BYTES)
        }
        require(value.mime.isNotBlank()) { "Decoded MIME must not be blank" }
        requireBoundedUtf8("decoded MIME", value.mime, 256)
        return value
    }

    private fun readOptionalEvidence(
        attempt: LegacyAudioAttemptCapability,
        name: String,
        descriptor: LegacyAudioBootstrapDescriptor,
        phase: BootstrapPhase,
        mayRewritePartial: Boolean,
    ): SidecarEvidence? {
        if (attempt.identity(name) == null) return null
        val adopted =
            try {
                readSidecar(attempt, name, descriptor, phase)
            } catch (failure: IllegalArgumentException) {
                if (mayRewritePartial) null else throw failure
            }
        if (adopted != null) attempt.forceDirectory(durability)
        return adopted
    }

    private fun readSidecar(
        attempt: LegacyAudioAttemptCapability,
        name: String,
        descriptor: LegacyAudioBootstrapDescriptor,
        phase: BootstrapPhase,
    ): SidecarEvidence {
        val bytes = readBounded(attempt, name)
        val parsed = SidecarCodec.decode(bytes)
        require(parsed.copy.storageKey == descriptor.targetStorageKey) {
            "Evidence copy storage key conflict"
        }
        require(parsed == SidecarEvidence.from(descriptor, phase, parsed.copy, parsed.metadata)) {
            "Evidence sidecar identity or phase conflict"
        }
        return parsed
    }

    private fun writeDurableSidecar(
        attempt: LegacyAudioAttemptCapability,
        name: String,
        evidence: SidecarEvidence,
        mayRewritePartial: Boolean,
    ) {
        val bytes = SidecarCodec.encode(evidence)
        val initial = attempt.identity(name)
        if (initial != null) {
            val parsed = runCatching { SidecarCodec.decode(readBounded(attempt, name)) }.getOrNull()
            if (parsed != null) {
                require(parsed == evidence) { "Existing evidence sidecar conflicts" }
                attempt.forceDirectory(durability)
                return
            }
            require(mayRewritePartial) { "Advanced evidence sidecar is corrupt" }
        }

        faults.hit(
            if (name == COPY_EVIDENCE_NAME) {
                LegacyAudioBootstrapFaultPoint.BEFORE_COPY_SIDECAR_PUBLISH
            } else {
                LegacyAudioBootstrapFaultPoint.BEFORE_METADATA_SIDECAR_PUBLISH
            }
        )
        val channel =
            if (initial == null) {
                try {
                    attempt.createNew(name)
                } catch (conflict: FileAlreadyExistsException) {
                    val parsed = SidecarCodec.decode(readBounded(attempt, name))
                    require(parsed == evidence) { "Concurrent evidence sidecar conflicts" }
                    attempt.forceDirectory(durability)
                    return
                }
            } else attempt.openRewrite(name, initial)
        val ownedIdentity = requireNotNull(attempt.identity(name)) { "Sidecar slot disappeared" }
        channel.use { output ->
            output.position(0)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) output.write(buffer)
            durability.forceFile(output)
        }
        require(attempt.identity(name)?.sameFileObjectForBootstrap(ownedIdentity) == true) {
            "Sidecar slot name changed while writing"
        }
        require(readBounded(attempt, name).contentEquals(bytes)) {
            "Durable sidecar failed validation"
        }
        attempt.forceDirectory(durability)
        faults.hit(
            if (name == COPY_EVIDENCE_NAME) {
                LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH
            } else {
                LegacyAudioBootstrapFaultPoint.AFTER_METADATA_SIDECAR_PUBLISH
            }
        )
    }

    private fun readBounded(attempt: LegacyAudioAttemptCapability, name: String): ByteArray =
        withStableCapabilityChannel(attempt, name) { channel ->
            faults.hit(LegacyAudioBootstrapFaultPoint.AFTER_EVIDENCE_OPEN)
            require(channel.size() in 1..MAX_LEGACY_AUDIO_EVIDENCE_BYTES.toLong()) {
                "Evidence sidecar exceeds size limit"
            }
            val output = ByteArrayOutputStream(MAX_LEGACY_AUDIO_EVIDENCE_BYTES)
            val buffer = ByteArray(minOf(8 * 1024, MAX_LEGACY_AUDIO_EVIDENCE_BYTES + 1))
            var total = 0
            while (true) {
                val count = channel.read(ByteBuffer.wrap(buffer))
                if (count < 0) break
                total += count
                require(total <= MAX_LEGACY_AUDIO_EVIDENCE_BYTES) {
                    "Evidence sidecar exceeds size limit"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private data class Digest(val sha256: String, val size: Long)

    private data class VerifiedCopy(
        val copyEvidence: LegacyAudioCopyEvidence,
        val sourceVerified: Boolean,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val COPY_SLOT_NAME = "legacy-audio"
        const val INTENT_EVIDENCE_NAME = "intent.evidence"
        const val COPY_EVIDENCE_NAME = "copy.evidence"
        const val METADATA_EVIDENCE_NAME = "metadata.evidence"
        val RESERVED_NAMES =
            setOf(COPY_SLOT_NAME, INTENT_EVIDENCE_NAME, COPY_EVIDENCE_NAME, METADATA_EVIDENCE_NAME)

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

private data class SidecarEvidence(
    val schemaVersion: Int,
    val phase: BootstrapPhase,
    val installEpoch: String,
    val sourceFingerprint: String,
    val attemptToken: String,
    val targetStorageKey: String,
    val copy: LegacyAudioCopyEvidence,
    val metadata: AudioValidationMetadata?,
) {
    companion object {
        fun from(
            descriptor: LegacyAudioBootstrapDescriptor,
            phase: BootstrapPhase,
            copy: LegacyAudioCopyEvidence,
            metadata: AudioValidationMetadata?,
        ) =
            SidecarEvidence(
                EVIDENCE_SCHEMA_VERSION,
                phase,
                descriptor.installEpoch,
                descriptor.sourceFingerprint,
                descriptor.attemptToken,
                descriptor.targetStorageKey,
                copy,
                metadata,
            )
    }
}

private object SidecarCodec {
    private const val MAGIC = "GentleWake legacy audio evidence"

    fun encode(value: SidecarEvidence): ByteArray {
        val payload =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.text(MAGIC)
                        out.writeInt(value.schemaVersion)
                        out.text(value.phase.name)
                        out.text(value.installEpoch)
                        out.text(value.sourceFingerprint)
                        out.text(value.attemptToken)
                        out.text(value.targetStorageKey)
                        out.text(value.copy.storageKey)
                        out.text(value.copy.sha256)
                        out.writeLong(value.copy.sizeBytes)
                        out.writeBoolean(value.metadata != null)
                        value.metadata?.let {
                            out.text(it.title)
                            out.writeBoolean(it.artist != null)
                            it.artist?.let { artist -> out.text(artist) }
                            out.writeLong(it.durationMillis)
                            out.text(it.mime)
                        }
                    }
                }
                .toByteArray()
        require(payload.size + 32 <= MAX_LEGACY_AUDIO_EVIDENCE_BYTES) {
            "Evidence sidecar is too large"
        }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): SidecarEvidence {
        require(bytes.size in 33..MAX_LEGACY_AUDIO_EVIDENCE_BYTES) {
            "Evidence sidecar size is invalid"
        }
        val payload = bytes.copyOf(bytes.size - 32)
        val checksum = bytes.copyOfRange(bytes.size - 32, bytes.size)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(checksum)) {
            "Evidence sidecar checksum mismatch"
        }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.text(64) == MAGIC) { "Evidence sidecar magic mismatch" }
                val version = input.readInt()
                require(version == EVIDENCE_SCHEMA_VERSION) { "Unsupported evidence schema" }
                val phase = BootstrapPhase.valueOf(input.text(16))
                require(phase != BootstrapPhase.DISCOVERED) { "Invalid evidence phase" }
                val epoch = input.text(MAX_INSTALL_EPOCH_UTF8_BYTES)
                val fingerprint = input.text(160)
                val token = input.text(64)
                val key = input.text(256)
                val storageKey = input.text(256)
                val sha = input.text(64)
                val size = input.readLong()
                require(sha.matches(Regex("[0-9a-f]{64}")) && size in 1..MAX_LEGACY_AUDIO_BYTES) {
                    "Invalid durable copy evidence"
                }
                val metadata =
                    if (input.readBoolean()) {
                        val title = input.text(MAX_AUDIO_METADATA_UTF8_BYTES)
                        val artist =
                            if (input.readBoolean()) input.text(MAX_AUDIO_METADATA_UTF8_BYTES)
                            else null
                        val duration = input.readLong()
                        val mime = input.text(256)
                        val value = AudioValidationMetadata(title, artist, duration, mime)
                        require(
                            duration in 1..MAX_AUDIO_DURATION_MILLIS &&
                                title.isNotBlank() &&
                                mime.isNotBlank()
                        ) {
                            "Invalid durable metadata evidence"
                        }
                        value
                    } else null
                require(
                    (phase == BootstrapPhase.COPIED && metadata == null) ||
                        (phase == BootstrapPhase.VALIDATED && metadata != null)
                ) {
                    "Evidence phase and metadata conflict"
                }
                require(input.available() == 0) { "Trailing evidence bytes" }
                SidecarEvidence(
                    version,
                    phase,
                    epoch,
                    fingerprint,
                    token,
                    key,
                    LegacyAudioCopyEvidence(storageKey, sha, size),
                    metadata,
                )
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed evidence sidecar", e)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(max: Int): String {
        val length = readInt()
        require(length in 0..max && length <= available()) { "Evidence text length is invalid" }
        val bytes = ByteArray(length)
        readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            "Malformed evidence UTF-8"
        }
        return value
    }
}

internal fun validateEvidence(
    evidence: LegacyAudioBootstrapEvidence
): LegacyAudioBootstrapEvidence {
    require(evidence.scheduleOwner == "LEGACY") { "Audio bootstrap requires LEGACY owner" }
    requireBoundedUtf8("install epoch", evidence.installEpoch, MAX_INSTALL_EPOCH_UTF8_BYTES)
    require(evidence.installEpoch.isNotBlank()) { "Install epoch must not be blank" }
    val fingerprint = requireNotNull(evidence.sourceFingerprint) { "Missing source fingerprint" }
    require(fingerprint.matches(Regex("legacy-canonical-v1:[0-9a-f]{64}:[0-9a-f]{64}"))) {
        "Invalid source fingerprint"
    }
    val token = requireNotNull(evidence.attemptToken) { "Missing attempt token" }
    require(token.matches(Regex("[0-9a-f]{64}"))) { "Invalid attempt token" }
    require(token == legacyDiscoveryAttemptToken(evidence.installEpoch, fingerprint)) {
        "Fingerprint/attempt token conflict"
    }
    val key = requireNotNull(evidence.targetStorageKey) { "Missing target storage key" }
    require(key == "bootstrap/$token/legacy-audio") { "Invalid target storage key" }
    require(evidence.bootstrapPhase in BootstrapPhase.entries.map { it.name }) {
        "Invalid audio bootstrap phase"
    }
    return evidence
}
