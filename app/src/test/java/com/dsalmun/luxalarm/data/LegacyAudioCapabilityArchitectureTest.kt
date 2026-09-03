/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LegacyAudioCapabilityArchitectureTest {
    @Test
    fun parentPathSwapAfterCapabilityAcquisitionCannotWriteOutsideSentinel() {
        val filesDir = createTempDirectory("legacy-cap-parent-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved bytes") }
        val descriptor = descriptor(source)
        val attempt = File(filesDir, descriptor.targetStorageKey).parentFile.apply { mkdirs() }
        val outside = createTempDirectory("legacy-cap-outside-").toFile()
        val sentinel = File(outside, "legacy-audio").apply { writeText("outside sentinel") }
        val capabilities = AnchoredTestCapabilityFactory { captured ->
            val parked = captured.resolveSibling("parked-token")
            Files.move(captured, parked)
            Files.createSymbolicLink(captured, outside.toPath())
            parked
        }

        val result =
            LegacyAudioBootstrapReconciler(
                    filesDir,
                    FakeStatePort(),
                    LegacyAudioDecoder { AudioValidationMetadata("title", null, 1, "audio/mpeg") },
                    capabilities = capabilities,
                )
                .reconcile(descriptor)

        assertEquals("outside sentinel", sentinel.readText())
        assertContentEquals(
            source.readBytes(),
            File(File(attempt.parentFile, "parked-token"), "legacy-audio").readBytes(),
        )
        assertEquals(source.length(), result.copyEvidence.sizeBytes)
    }

    @Test
    fun capabilityUnavailableFailsBeforeAnyFilesystemMutation() {
        val filesDir = createTempDirectory("legacy-cap-unavailable-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved bytes") }
        val before = source.readBytes()
        val capabilities = LegacyAudioCapabilityFactory { _, _ ->
            throw UnsupportedOperationException("secure directory capability unavailable")
        }

        assertFailsWith<UnsupportedOperationException> {
            LegacyAudioBootstrapReconciler(
                    filesDir,
                    FakeStatePort(),
                    LegacyAudioDecoder { AudioValidationMetadata("title", null, 1, "audio/mpeg") },
                    capabilities = capabilities,
                )
                .reconcile(descriptor(source))
        }

        assertContentEquals(before, source.readBytes())
        assertFalse(File(filesDir, "bootstrap").exists())
        assertEquals(setOf("source.mp3"), filesDir.list()!!.toSet())
    }

    @Test
    fun sourcePathSwapAfterWorkSlotOpenCannotPublishAttackerInode() {
        val filesDir = createTempDirectory("legacy-cap-source-swap-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("canonical source") }
        val attacker = File(filesDir, "attacker.mp3").apply { writeText("attacker bytes") }
        val descriptor = descriptor(source)
        val state = FakeStatePort()
        var swapped = false
        val faults = LegacyAudioBootstrapFaultInjector { point ->
            if (!swapped && point == LegacyAudioBootstrapFaultPoint.AFTER_TEMP_CREATE) {
                swapped = true
                Files.move(
                    attacker.toPath(),
                    source.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            LegacyAudioBootstrapReconciler(
                    filesDir,
                    state,
                    LegacyAudioDecoder {
                        AudioValidationMetadata("title", null, 1, "audio/mpeg")
                    },
                    faults = faults,
                    capabilities = AnchoredTestCapabilityFactory(),
                )
                .reconcile(descriptor)
        }

        val attempt = File(filesDir, descriptor.targetStorageKey).parentFile
        assertTrue(swapped)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertFalse(File(attempt, "copy.evidence").exists())
        assertEquals("attacker bytes", source.readText())
    }

    private fun descriptor(source: File): LegacyAudioBootstrapDescriptor {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("install-A", fingerprint)
        val evidence =
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                "install-A",
                fingerprint,
                token,
                "bootstrap/$token/legacy-audio",
                BootstrapPhase.DISCOVERED.name,
            )
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(evidence, source.path) {
            LegacyAudioSourceSnapshot(source.path, fingerprint)
        }
    }

    private class FakeStatePort : LegacyAudioBootstrapStatePort {
        var phase = BootstrapPhase.DISCOVERED
            private set

        override fun loadCurrent(descriptor: LegacyAudioBootstrapDescriptor) =
            LegacyAudioBootstrapEvidence(
                "LEGACY",
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
            if (phase != expected) return PhaseCasOutcome.REJECTED
            phase = next
            return PhaseCasOutcome.ADVANCED
        }
    }
}
