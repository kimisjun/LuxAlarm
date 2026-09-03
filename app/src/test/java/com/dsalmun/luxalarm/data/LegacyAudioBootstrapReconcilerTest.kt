/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LegacyAudioBootstrapReconcilerTest {
    @Test
    fun openedDescriptorIdentityMustMatchNoFollowPathIdentity() {
        val filesDir = createTempDirectory("legacy-bootstrap-opened-identity-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
        val mismatching =
            object : LegacyAudioFileIdentityPort {
                override fun pathIdentity(path: Path) =
                    JvmLegacyAudioFileIdentityPort.pathIdentity(path)

                override fun openReadOnly(path: Path): LegacyAudioOpenedRead {
                    val actual = JvmLegacyAudioFileIdentityPort.openReadOnly(path)
                    return LegacyAudioOpenedRead(
                        actual.channel,
                        actual.identity.copy(fileKey = "different-opened-inode"),
                    ) {
                        actual.close()
                    }
                }
            }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    decoder,
                    identities = mismatching,
                )
                .reconcile(descriptor(source, BootstrapPhase.DISCOVERED))
        }
        assertTrue(state.transitions.isEmpty())
        assertEquals(0, decoder.calls)
    }

    @Test
    fun sidecarSwapToSymlinkAfterSecureOpenFailsClosedWithoutReadingOutside() {
        val filesDir = createTempDirectory("legacy-bootstrap-sidecar-open-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val metadata = AudioValidationMetadata("title", null, 1, "audio/mpeg")
        legacyAudioTestReconciler(filesDir, state, FakeDecoder(metadata)).reconcile(descriptor)
        val sidecar = File(File(filesDir, descriptor.targetStorageKey).parentFile, "copy.evidence")
        val outside =
            File(createTempDirectory("legacy-sidecar-outside-").toFile(), "secret").apply {
                writeText("outside bytes must not be parsed")
            }
        var swapped = false
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (!swapped && point == LegacyAudioBootstrapFaultPoint.AFTER_EVIDENCE_OPEN) {
                swapped = true
                val parked = sidecar.resolveSibling("copy.parked")
                Files.move(sidecar.toPath(), parked.toPath())
                Files.createSymbolicLink(sidecar.toPath(), outside.toPath())
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, FakeDecoder(metadata), faults)
                .reconcile(descriptor)
        }
        assertEquals("outside bytes must not be parsed", outside.readText())
    }

    @Test
    fun destinationReplacementAfterDecodeHandleOpenFailsBeforeMetadataOrValidatedState() {
        val filesDir = createTempDirectory("legacy-bootstrap-decode-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val destination = File(filesDir, descriptor.targetStorageKey)
        val replacement = File(filesDir, "replacement.mp3").apply { writeText("attacker audio") }
        var swapped = false
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (
                !swapped &&
                    point == LegacyAudioBootstrapFaultPoint.AFTER_DESTINATION_OPEN_FOR_DECODE
            ) {
                swapped = true
                Files.move(
                    replacement.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    faults,
                )
                .reconcile(descriptor)
        }
        assertEquals(BootstrapPhase.COPIED, state.phase)
        assertFalse(File(destination.parentFile, "metadata.evidence").exists())
        assertEquals("attacker audio", destination.readText())
    }

    @Test
    fun finalParentSwapAfterLastRevalidationContinuesInAnchoredDirectoryWithoutTouchingOutside() {
        val filesDir = createTempDirectory("legacy-bootstrap-final-parent-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val destination = File(filesDir, descriptor.targetStorageKey)
        val outside = createTempDirectory("legacy-bootstrap-final-outside-").toFile()
        val sentinel = File(outside, "legacy-audio").apply { writeText("outside sentinel") }
        var swapped = false
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (
                !swapped &&
                    point ==
                        LegacyAudioBootstrapFaultPoint.AFTER_FINAL_PARENT_REVALIDATION_BEFORE_LINK
            ) {
                swapped = true
                val parent = destination.parentFile
                Files.move(parent.toPath(), parent.toPath().resolveSibling("parked-final-parent"))
                Files.createSymbolicLink(parent.toPath(), outside.toPath())
            }
        }

        legacyAudioTestReconciler(
                filesDir,
                FakeStatePort(BootstrapPhase.DISCOVERED),
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                faults,
            )
            .reconcile(descriptor)
        assertEquals("outside sentinel", sentinel.readText())
        assertEquals(
            "approved",
            File(destination.parentFile.parentFile, "parked-final-parent/legacy-audio").readText(),
        )
    }

    @Test
    fun replacedDeterministicCopySlotIsNotDeletedDuringFailureCleanup() {
        val filesDir = createTempDirectory("legacy-bootstrap-owned-temp-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        lateinit var replacement: File
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (point == LegacyAudioBootstrapFaultPoint.AFTER_TEMP_CREATE) {
                val parent = File(filesDir, descriptor.targetStorageKey).parentFile
                val slot = File(parent, "legacy-audio")
                val parked = slot.resolveSibling("parked-owned-copy")
                Files.move(slot.toPath(), parked.toPath())
                replacement = slot.apply { writeText("not owned") }
                throw InjectedFault()
            }
        }

        assertFailsWith<InjectedFault> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    faults,
                )
                .reconcile(descriptor)
        }
        assertEquals("not owned", replacement.readText())
    }

    @Test
    fun discoveredAudioIsDurablyCopiedThenDecodedAndValidatedWithoutChangingSource() {
        val filesDir = createTempDirectory("legacy-bootstrap-").toFile()
        val source =
            File(filesDir, "legacy/source.mp3").apply {
                parentFile.mkdirs()
                writeBytes("playable legacy audio".toByteArray())
            }
        val original = source.readBytes()
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder =
            FakeDecoder(AudioValidationMetadata("Morning", "Artist", 12_345, "audio/mpeg"))

        val result = legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)

        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertEquals(listOf(BootstrapPhase.COPIED, BootstrapPhase.VALIDATED), state.transitions)
        assertContentEquals(original, source.readBytes())
        assertContentEquals(original, File(filesDir, descriptor.targetStorageKey).readBytes())
        assertEquals("Morning", result.metadata.title)
        assertTrue(state.evidence.all { !it.storageKey.startsWith(File.separator) })
        assertEquals(1, decoder.calls)
    }

    @Test
    fun everyInjectedCrashConvergesOnRerunWithoutChangingSourceOrUnrelatedFiles() {
        LegacyAudioBootstrapFaultPoint.entries.forEach { faultPoint ->
            val filesDir = createTempDirectory("legacy-bootstrap-fault-").toFile()
            val source =
                File(filesDir, "legacy/source.mp3").apply {
                    parentFile.mkdirs()
                    writeBytes(ByteArray(96 * 1024) { (it % 251).toByte() })
                }
            val sourceBefore = source.readBytes()
            val unrelated = File(filesDir, "keep-me").apply { writeText("untouched") }
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            var injected = false
            val crashing = LegacyAudioBootstrapFaultInjector { point ->
                if (!injected && point == faultPoint) {
                    injected = true
                    throw LegacyAudioBootstrapProcessDeath()
                }
            }
            val metadata = AudioValidationMetadata("Morning", null, 1, "audio/mpeg")
            val decoder = FakeDecoder(metadata)
            val firstDescriptor = descriptor(source, state.phase)
            val capabilityFactory = AnchoredTestCapabilityFactory()

            assertFailsWith<LegacyAudioBootstrapProcessDeath>(faultPoint.name) {
                legacyAudioTestReconciler(
                        filesDir,
                        state,
                        decoder,
                        crashing,
                        capabilities = capabilityFactory,
                    )
                    .reconcile(firstDescriptor)
            }
            assertTrue(injected, "fault point was not reached: $faultPoint")
            val attemptDir = File(filesDir, firstDescriptor.targetStorageKey).parentFile
            val entriesAfterCrash = attemptDir.listFiles().orEmpty()
            val uuidResidues = entriesAfterCrash.filter {
                it.name.contains(
                    Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                ) || it.name.endsWith(".copying") || it.name.endsWith(".writing")
            }
            assertTrue(uuidResidues.isEmpty(), "UUID temporary residue: $faultPoint")
            assertTrue(
                entriesAfterCrash.count { it.name == "legacy-audio" } <= 1,
                "more than one deterministic large slot: $faultPoint",
            )
            if (
                faultPoint in
                    setOf(
                        LegacyAudioBootstrapFaultPoint.AFTER_TEMP_CREATE,
                        LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY,
                        LegacyAudioBootstrapFaultPoint.AFTER_FSYNC,
                    )
            ) {
                assertEquals(
                    1,
                    entriesAfterCrash.count { it.name == "legacy-audio" },
                    "deterministic crash slot missing: $faultPoint",
                )
            }

            val result =
                legacyAudioTestReconciler(
                        filesDir,
                        state,
                        decoder,
                        capabilities = capabilityFactory,
                    )
                    .reconcile(firstDescriptor)

            assertEquals(BootstrapPhase.VALIDATED, state.phase, faultPoint.name)
            assertContentEquals(sourceBefore, source.readBytes(), faultPoint.name)
            assertEquals("untouched", unrelated.readText(), faultPoint.name)
            assertEquals(sourceBefore.size.toLong(), result.copyEvidence.sizeBytes)
            assertTrue(
                attemptDir.listFiles().orEmpty().none {
                    it.name.endsWith(".copying") || it.name.endsWith(".writing")
                },
                "legacy temporary residue after convergence: $faultPoint",
            )
        }
    }

    @Test
    fun evidenceLoaderFailsClosedForMissingCorruptOrAdvancedCombinations() {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("install-A", fingerprint)
        val valid =
            LegacyAudioBootstrapEvidence(
                scheduleOwner = "LEGACY",
                installEpoch = "install-A",
                sourceFingerprint = fingerprint,
                attemptToken = token,
                targetStorageKey = "bootstrap/$token/legacy-audio",
                bootstrapPhase = "DISCOVERED",
            )
        val corrupt =
            listOf(
                valid.copy(scheduleOwner = "WAKE"),
                valid.copy(sourceFingerprint = null),
                valid.copy(attemptToken = null),
                valid.copy(attemptToken = "0".repeat(64)),
                valid.copy(targetStorageKey = null),
                valid.copy(targetStorageKey = "/absolute"),
                valid.copy(targetStorageKey = "bootstrap/$token/../legacy-audio"),
                valid.copy(bootstrapPhase = null),
                valid.copy(bootstrapPhase = "COMMITTED"),
            )

        corrupt.forEach { evidence ->
            assertFailsWith<IllegalArgumentException>(evidence.toString()) {
                LegacyAudioBootstrapDescriptorFixtureFactory.create(evidence, "legacy/source.mp3") {
                    LegacyAudioSourceSnapshot(
                        "legacy/source.mp3",
                        evidence.sourceFingerprint.orEmpty(),
                    )
                }
            }
        }
        assertEquals(
            BootstrapPhase.DISCOVERED,
            LegacyAudioBootstrapDescriptorFixtureFactory.create(valid, "legacy/source.mp3") {
                    LegacyAudioSourceSnapshot("legacy/source.mp3", fingerprint)
                }
                .phase,
        )
    }

    @Test
    fun sourceTraversalIsRejectedEvenWhenItNormalizesInsideFilesDir() {
        val filesDir = createTempDirectory("legacy-bootstrap-path-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED, "legacy/../source.mp3")

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
        }
        assertTrue(!File(filesDir, descriptor.targetStorageKey).exists())
    }

    @Test
    fun invalidSourceKindsAndSizesFailBeforeStateOrDecode() {
        val filesDir = createTempDirectory("legacy-bootstrap-invalid-").toFile()
        val outside =
            createTempDirectory("legacy-bootstrap-outside-").resolve("outside.mp3").toFile().apply {
                writeText("audio")
            }
        val empty = File(filesDir, "empty.mp3").apply { createNewFile() }
        val directory = File(filesDir, "directory").apply { mkdirs() }
        val oversized = File(filesDir, "oversized.mp3")
        RandomAccessFile(oversized, "rw").use { it.setLength(MAX_LEGACY_AUDIO_BYTES + 1) }
        val symlink = File(filesDir, "source-link.mp3")
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())

        listOf(outside, empty, directory, oversized, symlink).forEach { invalid ->
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
            assertFailsWith<Exception>(invalid.path) {
                legacyAudioTestReconciler(filesDir, state, decoder)
                    .reconcile(descriptor(invalid, BootstrapPhase.DISCOVERED))
            }
            assertTrue(state.transitions.isEmpty(), invalid.path)
            assertEquals(0, decoder.calls, invalid.path)
        }
    }

    @Test
    fun symlinkDestinationComponentIsRejectedWithoutTouchingItsTarget() {
        val filesDir = createTempDirectory("legacy-bootstrap-destination-link-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val outside = createTempDirectory("legacy-bootstrap-link-target-").toFile()
        Files.createSymbolicLink(File(filesDir, "bootstrap").toPath(), outside.toPath())

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
        }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun mismatchingDiscoveredCopySlotWithoutIntentIsPreservedAndRejected() {
        val filesDir = createTempDirectory("legacy-bootstrap-mismatch-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("source audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val destination =
            File(filesDir, descriptor.targetStorageKey).apply {
                parentFile.mkdirs()
                writeText("untrusted existing bytes")
            }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
        }

        assertEquals("untrusted existing bytes", destination.readText())
        assertEquals(
            1,
            destination.parentFile.listFiles().orEmpty().count { it.name == "legacy-audio" },
        )
    }

    @Test
    fun targetCreatedAfterCheckBeforePublishIsNeverOverwritten() {
        val filesDir = createTempDirectory("legacy-bootstrap-publish-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val destination = File(filesDir, descriptor.targetStorageKey)
        val attacker = "concurrent owner"
        val fault = LegacyAudioBootstrapFaultInjector { point ->
            if (point == LegacyAudioBootstrapFaultPoint.BEFORE_DESTINATION_PUBLISH) {
                destination.writeText(attacker)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    fault,
                )
                .reconcile(descriptor)
        }
        assertEquals(attacker, destination.readText())
    }

    @Test
    fun copySidecarCreatedAfterCheckBeforePublishIsNeverOverwritten() {
        val filesDir = createTempDirectory("legacy-bootstrap-sidecar-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val sidecar = File(File(filesDir, descriptor.targetStorageKey).parentFile, "copy.evidence")
        val attacker = "concurrent sidecar"
        val fault = LegacyAudioBootstrapFaultInjector { point ->
            if (point == LegacyAudioBootstrapFaultPoint.BEFORE_COPY_SIDECAR_PUBLISH) {
                sidecar.writeText(attacker)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    fault,
                )
                .reconcile(descriptor)
        }
        assertEquals(attacker, sidecar.readText())
    }

    @Test
    fun sourceReplacementAfterSecureOpenIsDetectedAndReplacementIsPreserved() {
        val filesDir = createTempDirectory("legacy-bootstrap-source-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("original") }
        val replacement = File(filesDir, "replacement.mp3").apply { writeText("replacement") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val fault = LegacyAudioBootstrapFaultInjector { point ->
            if (point == LegacyAudioBootstrapFaultPoint.AFTER_SOURCE_OPEN) {
                Files.move(
                    replacement.toPath(),
                    source.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    fault,
                )
                .reconcile(descriptor)
        }
        assertEquals("replacement", source.readText())
    }

    @Test
    fun sourceMutationDuringDescriptorCopyIsDetected() {
        val filesDir = createTempDirectory("legacy-bootstrap-source-mutation-").toFile()
        val source =
            File(filesDir, "source.mp3").apply {
                writeBytes(ByteArray(96 * 1024) { 1 })
            }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        var mutated = false
        val fault = LegacyAudioBootstrapFaultInjector { point ->
            if (!mutated && point == LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY) {
                mutated = true
                RandomAccessFile(source, "rw").use {
                    it.seek(70_000)
                    it.write(2)
                }
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    fault,
                )
                .reconcile(descriptor)
        }
        assertTrue(mutated)
    }

    @Test
    fun destinationParentSymlinkSwapBeforePublishContinuesInAnchoredDirectoryWithoutTouchingOutside() {
        val filesDir = createTempDirectory("legacy-bootstrap-parent-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("original") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val destination = File(filesDir, descriptor.targetStorageKey)
        val outside = createTempDirectory("legacy-bootstrap-outside-").toFile()
        val sentinel = File(outside, "legacy-audio").apply { writeText("outside sentinel") }
        var swapped = false
        val fault = LegacyAudioBootstrapFaultInjector { point ->
            if (!swapped && point == LegacyAudioBootstrapFaultPoint.BEFORE_DESTINATION_PUBLISH) {
                swapped = true
                val parent = destination.parentFile
                Files.move(parent.toPath(), parent.toPath().resolveSibling("parked-attempt"))
                Files.createSymbolicLink(parent.toPath(), outside.toPath())
            }
        }

        legacyAudioTestReconciler(
                filesDir,
                FakeStatePort(BootstrapPhase.DISCOVERED),
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                fault,
            )
            .reconcile(descriptor)
        assertEquals("outside sentinel", sentinel.readText())
        assertEquals(
            "original",
            File(destination.parentFile.parentFile, "parked-attempt/legacy-audio").readText(),
        )
    }

    @Test
    fun fileAndGenericDirectoryFsyncFailuresDoNotAdvanceCopiedPhase() {
        listOf("file", "directory").forEach { mode ->
            val filesDir = createTempDirectory("legacy-bootstrap-fsync-$mode-").toFile()
            val source = File(filesDir, "source.mp3").apply { writeText("audio") }
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            val durability =
                object : LegacyAudioDurabilityPort {
                    override fun forceFile(channel: FileChannel) {
                        if (mode == "file") throw IOException("injected file force failure")
                        channel.force(true)
                    }

                    override fun forceDirectory(channel: FileChannel) {
                        if (mode == "directory")
                            throw IOException("injected directory force failure")
                    }
                }

            assertFailsWith<IOException>(mode) {
                legacyAudioTestReconciler(
                        filesDir,
                        state,
                        FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                        durability = durability,
                    )
                    .reconcile(descriptor(source, BootstrapPhase.DISCOVERED))
            }
            assertEquals(BootstrapPhase.DISCOVERED, state.phase, mode)
        }
    }

    @Test
    fun directoryFsyncNeverClassifiesGenericIoExceptionsByMessage() {
        listOf(
                "operation not supported",
                "not supported",
                "is a directory",
                "invalid argument",
            )
            .forEach { message ->
                val failure = IOException(message)
                val durability = JvmLegacyAudioDurability { throw failure }

                assertEquals(
                    failure,
                    assertFailsWith<IOException>(message) {
                        forceTempDirectory(durability, "legacy-fsync-message-")
                    },
                )
            }
    }

    @Test
    fun directoryFsyncAllowsExplicitUnsupportedOperationException() {
        val durability = JvmLegacyAudioDurability {
            throw UnsupportedOperationException("explicit")
        }

        forceTempDirectory(durability, "legacy-fsync-unsupported-")
    }

    @Test
    fun directoryFsyncAllowsOnlyDocumentedUnsupportedErrnos() {
        listOf(OsConstants.EINVAL, OsConstants.EISDIR, OsConstants.ENOTSUP).forEach { errno ->
            val failure = IOException("wrapper", ErrnoException("fsync", errno))
            val durability = JvmLegacyAudioDurability { throw failure }

            forceTempDirectory(durability, "legacy-fsync-errno-")
        }
    }

    @Test
    fun preexistingLegacyTemporaryIsQuarantinedAndUnrelatedSiblingIsUntouched() {
        val filesDir = createTempDirectory("legacy-bootstrap-orphan-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("source audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val destination = File(filesDir, descriptor.targetStorageKey)
        destination.parentFile.mkdirs()
        val temporary =
            File(destination.parentFile, ".legacy-audio.copying").apply { writeText("partial") }
        val unrelated = File(destination.parentFile, "another.tmp").apply { writeText("keep") }

        legacyAudioTestReconciler(
                filesDir,
                FakeStatePort(BootstrapPhase.DISCOVERED),
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
            )
            .reconcile(descriptor)

        assertEquals("partial", temporary.readText())
        assertEquals("keep", unrelated.readText())
        assertContentEquals(source.readBytes(), destination.readBytes())
    }

    @Test
    fun copiedAndValidatedEvidenceFailClosedWhenApprovedSlotIsMissingWithoutDowngrade() {
        listOf(BootstrapPhase.COPIED, BootstrapPhase.VALIDATED).forEach { initial ->
            val filesDir = createTempDirectory("legacy-bootstrap-recovery-").toFile()
            val source = File(filesDir, "source.mp3").apply { writeText("audio") }
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
            if (initial == BootstrapPhase.COPIED) {
                assertFailsWith<IllegalStateException> {
                    legacyAudioTestReconciler(
                            filesDir,
                            state,
                            LegacyAudioDecoder { throw IllegalStateException("stop after copy") },
                        )
                        .reconcile(descriptor)
                }
            } else {
                legacyAudioTestReconciler(
                        filesDir,
                        state,
                        FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    )
                    .reconcile(descriptor)
            }
            assertEquals(initial, state.phase)
            assertTrue(File(filesDir, descriptor.targetStorageKey).delete())
            state.transitions.clear()

            assertFailsWith<IllegalArgumentException>(initial.name) {
                legacyAudioTestReconciler(
                        filesDir,
                        state,
                        FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                    )
                    .reconcile(descriptor)
            }

            assertEquals(initial, state.phase)
            assertTrue(state.transitions.isEmpty())
            assertFalse(File(filesDir, descriptor.targetStorageKey).exists())
        }
    }

    @Test
    fun durationAtTwentyFourHoursIsAcceptedAndOneMillisecondMoreIsRejected() {
        fun run(durationMillis: Long): BootstrapPhase {
            val filesDir = createTempDirectory("legacy-bootstrap-duration-").toFile()
            val source = File(filesDir, "source.mp3").apply { writeText("audio") }
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(
                        AudioValidationMetadata("title", null, durationMillis, "audio/mpeg")
                    ),
                )
                .reconcile(descriptor(source, BootstrapPhase.DISCOVERED))
            return state.phase
        }

        assertEquals(BootstrapPhase.VALIDATED, run(MAX_AUDIO_DURATION_MILLIS))
        assertFailsWith<IllegalArgumentException> { run(MAX_AUDIO_DURATION_MILLIS + 1) }
    }

    @Test
    fun decoderFailureOrInvalidMetadataNeverRecordsValidated() {
        val invalidMetadata =
            listOf(
                AudioValidationMetadata("", null, 1, "audio/mpeg"),
                AudioValidationMetadata("title", null, 0, "audio/mpeg"),
                AudioValidationMetadata("title", null, MAX_AUDIO_DURATION_MILLIS + 1, "audio/mpeg"),
                AudioValidationMetadata("title", null, 1, ""),
                AudioValidationMetadata(
                    "x".repeat(MAX_AUDIO_METADATA_UTF8_BYTES + 1),
                    null,
                    1,
                    "audio/mpeg",
                ),
            )
        invalidMetadata.forEach { metadata ->
            val filesDir = createTempDirectory("legacy-bootstrap-metadata-").toFile()
            val source = File(filesDir, "source.mp3").apply { writeText("audio") }
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            assertFailsWith<IllegalArgumentException> {
                legacyAudioTestReconciler(filesDir, state, FakeDecoder(metadata))
                    .reconcile(descriptor(source, BootstrapPhase.DISCOVERED))
            }
            assertEquals(BootstrapPhase.COPIED, state.phase)
        }
        val filesDir = createTempDirectory("legacy-bootstrap-decode-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        assertFailsWith<IllegalStateException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    decoder = LegacyAudioDecoder { throw IllegalStateException("decode") },
                )
                .reconcile(descriptor(source, BootstrapPhase.DISCOVERED))
        }
        assertEquals(BootstrapPhase.COPIED, state.phase)
    }

    @Test
    fun staleDiscoveredDescriptorUsesCurrentValidatedEvidenceIdempotently() {
        val filesDir = createTempDirectory("legacy-bootstrap-stale-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val stale = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val metadata = AudioValidationMetadata("title", "artist", 1, "audio/mpeg")
        val firstDecoder = FakeDecoder(metadata)
        legacyAudioTestReconciler(filesDir, state, firstDecoder).reconcile(stale)
        val secondDecoder = FakeDecoder(AudioValidationMetadata("wrong", null, 2, "audio/wrong"))

        val rerun = legacyAudioTestReconciler(filesDir, state, secondDecoder).reconcile(stale)

        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertEquals(metadata, rerun.metadata)
        assertEquals(0, secondDecoder.calls)
    }

    @Test
    fun copiedMissingDestinationRejectsChangedSourceUsingDurableCopyEvidence() {
        val filesDir = createTempDirectory("legacy-bootstrap-changed-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
        legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
        val destination = File(filesDir, descriptor.targetStorageKey)
        assertTrue(destination.delete())
        source.writeText("changed bytes")

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
        }
        assertFalse(destination.exists())
    }

    @Test
    fun corruptOrMissingSidecarInAdvancedPhaseFailsClosed() {
        listOf("missing", "corrupt").forEach { mode ->
            val filesDir = createTempDirectory("legacy-bootstrap-sidecar-$mode-").toFile()
            val source = File(filesDir, "source.mp3").apply { writeText("audio") }
            val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
            legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
            val copySidecar =
                File(File(filesDir, descriptor.targetStorageKey).parentFile, "copy.evidence")
            if (mode == "missing") assertTrue(copySidecar.delete())
            else copySidecar.writeText("corrupt")

            assertFailsWith<IllegalArgumentException> {
                legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
            }
        }
    }

    @Test
    fun validatedMetadataEvidenceMustReferenceSameCopyDigest() {
        fun complete(
            filesDir: File,
            bytes: String,
        ): Triple<File, LegacyAudioBootstrapDescriptor, FakeStatePort> {
            val source = File(filesDir, "source.mp3").apply { writeText(bytes) }
            val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
            val state = FakeStatePort(BootstrapPhase.DISCOVERED)
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(AudioValidationMetadata("title-$bytes", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
            return Triple(source, descriptor, state)
        }
        val firstDir = createTempDirectory("legacy-bootstrap-meta-a-").toFile()
        val (_, firstDescriptor, _) = complete(firstDir, "audio A")
        val firstMetadata =
            File(File(firstDir, firstDescriptor.targetStorageKey).parentFile, "metadata.evidence")
                .readBytes()
        val secondDir = createTempDirectory("legacy-bootstrap-meta-b-").toFile()
        val (_, secondDescriptor, secondState) = complete(secondDir, "audio B")
        File(File(secondDir, secondDescriptor.targetStorageKey).parentFile, "metadata.evidence")
            .writeBytes(firstMetadata)

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    secondDir,
                    secondState,
                    FakeDecoder(AudioValidationMetadata("wrong", null, 2, "audio/wrong")),
                )
                .reconcile(secondDescriptor)
        }
    }

    @Test
    fun validCopyAndMetadataSidecarsBoundToDifferentCopyKeyFailClosed() {
        val filesDir = createTempDirectory("legacy-bootstrap-copy-key-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        legacyAudioTestReconciler(
                filesDir,
                state,
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
            )
            .reconcile(descriptor)
        state.transitions.clear()
        val attemptDir = File(filesDir, descriptor.targetStorageKey).parentFile
        listOf("copy.evidence", "metadata.evidence").forEach { name ->
            val sidecar = File(attemptDir, name)
            sidecar.writeBytes(
                rebindCopyStorageKey(sidecar.readBytes(), descriptor.targetStorageKey)
            )
        }
        val filesBefore = attemptDir.listFiles()!!.associate { it.name to it.readBytes() }
        val decoder = FakeDecoder(AudioValidationMetadata("wrong", null, 2, "audio/wrong"))

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
        }

        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(0, decoder.calls)
        assertEquals(filesBefore.keys, attemptDir.listFiles()!!.map { it.name }.toSet())
        filesBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, File(attemptDir, name).readBytes(), name)
        }
    }

    @Test
    fun sidecarDigestBindsPhaseAndAllAttemptIdentityFields() {
        val filesDir = createTempDirectory("legacy-bootstrap-bound-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        legacyAudioTestReconciler(
                filesDir,
                state,
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
            )
            .reconcile(descriptor)
        val metadataSidecar =
            File(File(filesDir, descriptor.targetStorageKey).parentFile, "metadata.evidence")
        val bytes = metadataSidecar.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("VALIDATED")
        assertTrue(at >= 0)
        bytes[at] = 'X'.code.toByte()
        metadataSidecar.writeBytes(bytes)

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(AudioValidationMetadata("wrong", null, 2, "audio/wrong")),
                )
                .reconcile(descriptor)
        }
    }

    @Test
    fun preExistingDeterministicAndArbitraryTempsAreNeverDeletedOrOverwritten() {
        val filesDir = createTempDirectory("legacy-bootstrap-temp-owner-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val parent = File(filesDir, descriptor.targetStorageKey).parentFile.apply { mkdirs() }
        val old = File(parent, ".legacy-audio.copying").apply { writeText("owned by nobody") }
        val arbitrary = File(parent, ".arbitrary.tmp").apply { writeText("also keep") }

        legacyAudioTestReconciler(
                filesDir,
                FakeStatePort(BootstrapPhase.DISCOVERED),
                FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
            )
            .reconcile(descriptor)

        assertEquals("owned by nobody", old.readText())
        assertEquals("also keep", arbitrary.readText())
    }

    @Test
    fun sourceAtOldDeterministicTempPathIsRejectedWithoutDeletingItsBytes() {
        val filesDir = createTempDirectory("legacy-bootstrap-temp-source-").toFile()
        val seed = File(filesDir, "seed.mp3").apply { writeText("seed") }
        val seedDescriptor = descriptor(seed, BootstrapPhase.DISCOVERED)
        val source =
            File(
                    File(filesDir, seedDescriptor.targetStorageKey).parentFile,
                    ".legacy-audio.copying",
                )
                .apply {
                    parentFile.mkdirs()
                    writeText("must survive")
                }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
        }
        assertEquals("must survive", source.readText())
        assertTrue(state.transitions.isEmpty())
    }

    @Test
    fun canonicalChangeAfterInitialCheckFailsBeforeFirstFilesystemEffect() {
        val filesDir = createTempDirectory("legacy-bootstrap-pre-effect-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val sourceBefore = source.readBytes()
        val unrelated = File(filesDir, "unrelated").apply { writeText("untouched") }
        var revalidations = 0
        val descriptor =
            descriptor(source, BootstrapPhase.DISCOVERED) { expected ->
                revalidations++
                if (revalidations == 1) expected
                else expected.copy(sourcePath = "changed/current-source.mp3")
            }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
        }

        assertEquals(2, revalidations)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(0, decoder.calls)
        assertContentEquals(sourceBefore, source.readBytes())
        assertEquals("untouched", unrelated.readText())
        assertEquals(setOf("source.mp3", "unrelated"), filesDir.list()!!.toSet())
    }

    @Test
    fun canonicalChangeAfterDurableCopyFailsBeforeDiscoveredToCopiedCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-pre-copied-cas-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val sourceBefore = source.readBytes()
        val unrelated = File(filesDir, "unrelated").apply { writeText("untouched") }
        var canonicalChanged = false
        val descriptor =
            descriptor(source, BootstrapPhase.DISCOVERED) { expected ->
                if (canonicalChanged) expected.copy(sourceFingerprint = "changed") else expected
            }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (point == LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH) {
                canonicalChanged = true
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder, faults).reconcile(descriptor)
        }

        val attemptDir = File(filesDir, descriptor.targetStorageKey).parentFile
        assertTrue(canonicalChanged)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(0, decoder.calls)
        assertTrue(File(attemptDir, "copy.evidence").isFile)
        assertFalse(File(attemptDir, "metadata.evidence").exists())
        assertContentEquals(sourceBefore, source.readBytes())
        assertEquals("untouched", unrelated.readText())
    }

    @Test
    fun canonicalChangeAfterDurableMetadataFailsBeforeCopiedToValidatedCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-pre-validated-cas-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val sourceBefore = source.readBytes()
        val unrelated = File(filesDir, "unrelated").apply { writeText("untouched") }
        var canonicalChanged = false
        var reachedAfterValidatedState = false
        val descriptor =
            descriptor(source, BootstrapPhase.DISCOVERED) { expected ->
                if (canonicalChanged) expected.copy(sourcePath = "different/audio.mp3")
                else expected
            }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            when (point) {
                LegacyAudioBootstrapFaultPoint.AFTER_METADATA_SIDECAR_PUBLISH ->
                    canonicalChanged = true
                LegacyAudioBootstrapFaultPoint.AFTER_VALIDATED_STATE_WRITE ->
                    reachedAfterValidatedState = true
                else -> Unit
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder, faults).reconcile(descriptor)
        }

        val attemptDir = File(filesDir, descriptor.targetStorageKey).parentFile
        assertTrue(canonicalChanged)
        assertFalse(reachedAfterValidatedState)
        assertEquals(BootstrapPhase.COPIED, state.phase)
        assertEquals(listOf(BootstrapPhase.COPIED), state.transitions)
        assertEquals(1, decoder.calls)
        assertTrue(File(attemptDir, "copy.evidence").isFile)
        assertTrue(File(attemptDir, "metadata.evidence").isFile)
        assertContentEquals(sourceBefore, source.readBytes())
        assertEquals("untouched", unrelated.readText())
    }

    @Test
    fun changedCurrentDiscoverySourceFailsBeforeAnyFilesystemMutation() {
        val filesDir = createTempDirectory("legacy-bootstrap-revalidate-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val base = descriptor(source, BootstrapPhase.DISCOVERED)
        val evidence =
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                base.installEpoch,
                base.sourceFingerprint,
                base.attemptToken,
                base.targetStorageKey,
                "DISCOVERED",
            )
        val changed =
            LegacyAudioBootstrapDescriptorFixtureFactory.create(evidence, source.path) {
                LegacyAudioSourceSnapshot(
                    source.path,
                    "legacy-canonical-v1:${"c".repeat(64)}:${"d".repeat(64)}",
                )
            }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    FakeStatePort(BootstrapPhase.DISCOVERED),
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(changed)
        }
        assertEquals(listOf("source.mp3"), filesDir.list()!!.toList())
    }

    @Test
    fun validatedRerunRejectsChangedSourceAndPreservesDurableFilesAndState() {
        val filesDir = createTempDirectory("legacy-bootstrap-durable-source-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val metadata = AudioValidationMetadata("title", null, 1, "audio/mpeg")
        legacyAudioTestReconciler(filesDir, state, FakeDecoder(metadata)).reconcile(descriptor)
        val attemptDir = File(filesDir, descriptor.targetStorageKey).parentFile
        val durableBefore = attemptDir.listFiles()!!.associate { it.name to it.readBytes() }
        state.transitions.clear()
        source.writeText("now changed")
        val decoder = FakeDecoder(AudioValidationMetadata("wrong", null, 2, "audio/wrong"))

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder).reconcile(descriptor)
        }

        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(0, decoder.calls)
        assertEquals(durableBefore.keys, attemptDir.listFiles()!!.map { it.name }.toSet())
        durableBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, File(attemptDir, name).readBytes(), name)
        }
    }

    @Test
    fun invalidCurrentOwnerOrEvidenceFailsBeforeFilesystemTouch() {
        val filesDir = createTempDirectory("legacy-bootstrap-owner-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val marker = File(filesDir, "marker").apply { writeText("untouched") }
        val state = FakeStatePort(BootstrapPhase.DISCOVERED).apply { owner = "WAKE" }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg")),
                )
                .reconcile(descriptor)
        }
        assertEquals(listOf("marker", "source.mp3").sorted(), filesDir.list()!!.sorted())
        assertEquals("untouched", marker.readText())
    }

    @Test
    fun validatedRerunIsIdempotentAndNeverWritesAnotherPhase() {
        val filesDir = createTempDirectory("legacy-bootstrap-idempotent-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        val decoder = FakeDecoder(AudioValidationMetadata("title", null, 1, "audio/mpeg"))
        val reconciler = legacyAudioTestReconciler(filesDir, state, decoder)

        reconciler.reconcile(descriptor)
        state.transitions.clear()
        reconciler.reconcile(descriptor)
        reconciler.reconcile(descriptor)

        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(1, decoder.calls)
    }

    @Test
    fun retryAfterIntentEvidenceOpenForcesDirectoryBeforeAnyMutationAndFailsClosed() {
        val filesDir = createTempDirectory("legacy-bootstrap-intent-adoption-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val state = FakeStatePort(BootstrapPhase.DISCOVERED)
        var crashed = false
        val crashAfterIntentOpen = LegacyAudioBootstrapFaultInjector { point ->
            if (!crashed && point == LegacyAudioBootstrapFaultPoint.AFTER_EVIDENCE_OPEN) {
                crashed = true
                throw LegacyAudioBootstrapProcessDeath()
            }
        }
        assertFailsWith<LegacyAudioBootstrapProcessDeath> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(TEST_METADATA),
                    crashAfterIntentOpen,
                )
                .reconcile(descriptor)
        }
        val events = mutableListOf<String>()

        assertFailsWith<IOException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(TEST_METADATA),
                    capabilities = RecordingLegacyAudioCapabilityFactory(events, 1),
                )
                .reconcile(descriptor)
        }

        assertEquals(listOf("forceDirectory:1"), events)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertTrue(state.transitions.isEmpty())

        events.clear()
        val convergedState = FakeStatePort(BootstrapPhase.DISCOVERED, events)
        legacyAudioTestReconciler(
                filesDir,
                convergedState,
                FakeDecoder(TEST_METADATA),
                capabilities = RecordingLegacyAudioCapabilityFactory(events),
            )
            .reconcile(descriptor)
        assertEquals(BootstrapPhase.VALIDATED, convergedState.phase)
        assertTrue(events.indexOf("forceDirectory:1") < events.indexOf("create:legacy-audio"))
        assertTrue(events.indexOf("forceDirectory:1") < events.indexOf("cas:DISCOVERED->COPIED"))
    }

    @Test
    fun adoptedCopyEvidenceForcesDirectoryBeforeDiscoveredToCopiedCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-copy-adoption-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val events = mutableListOf<String>()
        val state = FakeStatePort(BootstrapPhase.DISCOVERED, events)
        crashOnceAt(
            filesDir,
            state,
            descriptor,
            LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH,
        )
        events.clear()

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(TEST_METADATA),
                    capabilities =
                        RecordingLegacyAudioCapabilityFactory(
                            events,
                            2,
                            IllegalArgumentException("directory force"),
                        ),
                )
                .reconcile(descriptor)
        }

        assertEquals(listOf("forceDirectory:1", "forceDirectory:2"), events)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
    }

    @Test
    fun concurrentlyAdoptedExactCopySidecarForcesDirectoryBeforeCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-copy-race-adoption-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val seedState = FakeStatePort(BootstrapPhase.DISCOVERED)
        crashOnceAt(
            filesDir,
            seedState,
            descriptor,
            LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH,
        )
        val attemptDir = File(filesDir, descriptor.targetStorageKey).parentFile
        val sidecar = File(attemptDir, "copy.evidence")
        val exactBytes = sidecar.readBytes()
        assertTrue(sidecar.delete())

        val events = mutableListOf<String>()
        val state = FakeStatePort(BootstrapPhase.DISCOVERED, events)
        var racedCreateNew = false
        val raceFactory = LegacyAudioCapabilityFactory { root, current ->
            val acquired = AnchoredTestCapabilityFactory().acquire(root, current)
            var directoryForces = 0
            object : LegacyAudioAttemptCapability by acquired {
                override fun createNew(name: String): FileChannel {
                    if (name == "copy.evidence") {
                        racedCreateNew = true
                        sidecar.writeBytes(exactBytes)
                    }
                    return acquired.createNew(name)
                }

                override fun forceDirectory(durability: LegacyAudioDurabilityPort) {
                    directoryForces++
                    events += "forceDirectory:$directoryForces"
                    if (directoryForces == 2) throw IOException("race adoption force failure")
                    acquired.forceDirectory(durability)
                }
            }
        }
        val stableBefore =
            attemptDir.listFiles()!!.associate { it.name to it.readBytes() } +
                ("copy.evidence" to exactBytes)

        assertFailsWith<IOException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(TEST_METADATA),
                    capabilities = raceFactory,
                )
                .reconcile(descriptor)
        }
        assertTrue(racedCreateNew)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertTrue(state.transitions.isEmpty())
        assertEquals(listOf("forceDirectory:1", "forceDirectory:2"), events)
        assertEquals(stableBefore.keys, attemptDir.listFiles()!!.map { it.name }.toSet())
        stableBefore.forEach { (name, bytes) ->
            assertContentEquals(bytes, File(attemptDir, name).readBytes(), name)
        }
    }

    @Test
    fun exactCopySidecarCreateNewRaceIsForcedBeforeDiscoveredCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-copy-race-order-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val seedState = FakeStatePort(BootstrapPhase.DISCOVERED)
        crashOnceAt(
            filesDir,
            seedState,
            descriptor,
            LegacyAudioBootstrapFaultPoint.AFTER_COPY_SIDECAR_PUBLISH,
        )
        val sidecar = File(File(filesDir, descriptor.targetStorageKey).parentFile, "copy.evidence")
        val exactBytes = sidecar.readBytes()
        assertTrue(sidecar.delete())
        val events = mutableListOf<String>()
        val state = FakeStatePort(BootstrapPhase.DISCOVERED, events)
        var racedCreateNew = false
        val raceFactory = LegacyAudioCapabilityFactory { root, current ->
            val acquired = AnchoredTestCapabilityFactory().acquire(root, current)
            var directoryForces = 0
            object : LegacyAudioAttemptCapability by acquired {
                override fun createNew(name: String): FileChannel {
                    if (name == "copy.evidence") {
                        racedCreateNew = true
                        sidecar.writeBytes(exactBytes)
                    }
                    return acquired.createNew(name)
                }

                override fun forceDirectory(durability: LegacyAudioDurabilityPort) {
                    directoryForces++
                    events += "forceDirectory:$directoryForces"
                    acquired.forceDirectory(durability)
                }
            }
        }

        legacyAudioTestReconciler(
                filesDir,
                state,
                FakeDecoder(TEST_METADATA),
                capabilities = raceFactory,
            )
            .reconcile(descriptor)

        assertTrue(racedCreateNew)
        assertEquals(BootstrapPhase.VALIDATED, state.phase)
        assertTrue(events.indexOf("forceDirectory:2") < events.indexOf("cas:DISCOVERED->COPIED"))
        assertContentEquals(exactBytes, sidecar.readBytes())
    }

    @Test
    fun adoptedMetadataEvidenceForcesDirectoryBeforeCopiedToValidatedCas() {
        val filesDir = createTempDirectory("legacy-bootstrap-metadata-adoption-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source, BootstrapPhase.DISCOVERED)
        val events = mutableListOf<String>()
        val state = FakeStatePort(BootstrapPhase.DISCOVERED, events)
        crashOnceAt(
            filesDir,
            state,
            descriptor,
            LegacyAudioBootstrapFaultPoint.AFTER_METADATA_SIDECAR_PUBLISH,
        )
        assertEquals(BootstrapPhase.COPIED, state.phase)
        events.clear()

        assertFailsWith<IOException> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    FakeDecoder(TEST_METADATA),
                    capabilities = RecordingLegacyAudioCapabilityFactory(events, 3),
                )
                .reconcile(descriptor)
        }

        assertEquals(
            listOf("forceDirectory:1", "forceDirectory:2", "forceDirectory:3"),
            events,
        )
        assertEquals(BootstrapPhase.COPIED, state.phase)
    }

    @Test
    fun discoveredCopyUsesOneSourceOpenWhileAdvancedVerificationStillReopensSource() {
        fun countingPort() =
            object : LegacyAudioFileIdentityPort {
                var opens = 0

                override fun pathIdentity(path: Path) =
                    JvmLegacyAudioFileIdentityPort.pathIdentity(path)

                override fun openReadOnly(path: Path): LegacyAudioOpenedRead {
                    opens++
                    return JvmLegacyAudioFileIdentityPort.openReadOnly(path)
                }
            }

        val freshDir = createTempDirectory("legacy-bootstrap-one-open-fresh-").toFile()
        val freshSource = File(freshDir, "source.mp3").apply { writeText("fresh audio") }
        val freshPort = countingPort()
        legacyAudioTestReconciler(
                freshDir,
                FakeStatePort(BootstrapPhase.DISCOVERED),
                FakeDecoder(TEST_METADATA),
                identities = freshPort,
            )
            .reconcile(descriptor(freshSource, BootstrapPhase.DISCOVERED))
        assertEquals(1, freshPort.opens, "DISCOVERED without a copy slot")

        val partialDir = createTempDirectory("legacy-bootstrap-one-open-partial-").toFile()
        val partialSource =
            File(partialDir, "source.mp3").apply { writeBytes(ByteArray(96 * 1024) { 7 }) }
        val partialDescriptor = descriptor(partialSource, BootstrapPhase.DISCOVERED)
        val partialState = FakeStatePort(BootstrapPhase.DISCOVERED)
        crashOnceAt(
            partialDir,
            partialState,
            partialDescriptor,
            LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY,
        )
        val partialPort = countingPort()
        legacyAudioTestReconciler(
                partialDir,
                partialState,
                FakeDecoder(TEST_METADATA),
                identities = partialPort,
            )
            .reconcile(partialDescriptor)
        assertEquals(1, partialPort.opens, "DISCOVERED with a mismatching partial slot")

        val advancedDir = createTempDirectory("legacy-bootstrap-one-open-advanced-").toFile()
        val advancedSource = File(advancedDir, "source.mp3").apply { writeText("advanced audio") }
        val advancedDescriptor = descriptor(advancedSource, BootstrapPhase.DISCOVERED)
        val advancedState = FakeStatePort(BootstrapPhase.DISCOVERED)
        assertFailsWith<IllegalStateException> {
            legacyAudioTestReconciler(
                    advancedDir,
                    advancedState,
                    LegacyAudioDecoder { throw IllegalStateException("stop in COPIED") },
                )
                .reconcile(advancedDescriptor)
        }
        assertEquals(BootstrapPhase.COPIED, advancedState.phase)
        val advancedPort = countingPort()
        legacyAudioTestReconciler(
                advancedDir,
                advancedState,
                FakeDecoder(TEST_METADATA),
                identities = advancedPort,
            )
            .reconcile(advancedDescriptor)
        assertEquals(1, advancedPort.opens, "COPIED durable evidence verification")
    }

    private fun crashOnceAt(
        filesDir: File,
        state: FakeStatePort,
        descriptor: LegacyAudioBootstrapDescriptor,
        point: LegacyAudioBootstrapFaultPoint,
    ) {
        var crashed = false
        val faults = LegacyAudioBootstrapFaultInjector { visited ->
            if (!crashed && visited == point) {
                crashed = true
                throw LegacyAudioBootstrapProcessDeath()
            }
        }
        assertFailsWith<LegacyAudioBootstrapProcessDeath> {
            legacyAudioTestReconciler(filesDir, state, FakeDecoder(TEST_METADATA), faults)
                .reconcile(descriptor)
        }
        assertTrue(crashed)
    }

    private fun forceTempDirectory(durability: LegacyAudioDurabilityPort, prefix: String) {
        FileChannel.open(createTempDirectory(prefix), StandardOpenOption.READ).use {
            durability.forceDirectory(it)
        }
    }

    private fun rebindCopyStorageKey(bytes: ByteArray, storageKey: String): ByteArray {
        val result = bytes.copyOf()
        val needle = storageKey.toByteArray()
        val starts =
            (0..result.size - 32 - needle.size).filter { start ->
                needle.indices.all { offset -> result[start + offset] == needle[offset] }
            }
        assertEquals(2, starts.size)
        val copyKeyStart = starts.last()
        result[copyKeyStart + needle.lastIndex] =
            if (result[copyKeyStart + needle.lastIndex] == '0'.code.toByte()) '1'.code.toByte()
            else '0'.code.toByte()
        val payload = result.copyOf(result.size - 32)
        MessageDigest.getInstance("SHA-256").digest(payload).copyInto(result, payload.size)
        return result
    }

    private fun descriptor(
        source: File,
        phase: BootstrapPhase,
        sourcePath: String = source.path,
        revalidate: ((LegacyAudioSourceSnapshot) -> LegacyAudioSourceSnapshot)? = null,
    ): LegacyAudioBootstrapDescriptor {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("install-A", fingerprint)
        val evidence =
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                "install-A",
                fingerprint,
                token,
                "bootstrap/$token/legacy-audio",
                phase.name,
            )
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(evidence, sourcePath) {
            val expected = LegacyAudioSourceSnapshot(sourcePath, fingerprint)
            revalidate?.invoke(expected) ?: expected
        }
    }

    private class FakeStatePort(
        var phase: BootstrapPhase,
        private val events: MutableList<String>? = null,
    ) : LegacyAudioBootstrapStatePort {
        var owner: String = "LEGACY"
        val transitions = mutableListOf<BootstrapPhase>()
        val evidence = mutableListOf<LegacyAudioCopyEvidence>()

        override fun loadCurrent(
            descriptor: LegacyAudioBootstrapDescriptor
        ): LegacyAudioBootstrapEvidence =
            LegacyAudioBootstrapEvidence(
                owner,
                descriptor.installEpoch,
                descriptor.sourceFingerprint,
                descriptor.attemptToken,
                descriptor.targetStorageKey,
                phase.name,
            )

        override fun compareAndSetPhase(
            descriptor: LegacyAudioBootstrapDescriptor,
            expected: BootstrapPhase,
            next: BootstrapPhase,
            copyEvidence: LegacyAudioCopyEvidence,
        ): PhaseCasOutcome {
            events?.add("cas:${expected.name}->${next.name}")
            if (phase >= next) return PhaseCasOutcome.ALREADY_AT_OR_BEYOND
            if (phase != expected) return PhaseCasOutcome.REJECTED
            phase = next
            transitions += next
            evidence += copyEvidence
            return PhaseCasOutcome.ADVANCED
        }
    }

    private class InjectedFault : RuntimeException()

    private class FakeDecoder(private val metadata: AudioValidationMetadata) : LegacyAudioDecoder {
        var calls = 0

        override fun validate(channel: FileChannel): AudioValidationMetadata {
            calls++
            assertTrue(channel.isOpen)
            return metadata
        }
    }

    private companion object {
        val TEST_METADATA = AudioValidationMetadata("title", null, 1, "audio/mpeg")
    }
}
