/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LegacyAudioBootstrapIntentOwnershipTest {
    @Test
    fun exactIntentCreateNewRaceIsAdoptedOnlyAfterDirectoryForce() {
        val filesDir = createTempDirectory("legacy-intent-exact-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source)
        var crashed = false
        assertFailsWith<InjectedCrash> {
            legacyAudioTestReconciler(
                    filesDir,
                    State(),
                    decoder(),
                    LegacyAudioBootstrapFaultInjector { point ->
                        if (
                            !crashed && point == LegacyAudioBootstrapFaultPoint.AFTER_EVIDENCE_OPEN
                        ) {
                            crashed = true
                            throw InjectedCrash()
                        }
                    },
                )
                .reconcile(descriptor)
        }
        val attempt = requireNotNull(File(filesDir, descriptor.targetStorageKey).parentFile)
        val intent = File(attempt, "intent.evidence")
        val exactBytes = intent.readBytes()
        assertTrue(intent.delete())
        val state = State()
        var racedCreateNew = false
        val capability = LegacyAudioCapabilityFactory { root, current ->
            val acquired = AnchoredTestCapabilityFactory().acquire(root, current)
            object : LegacyAudioAttemptCapability by acquired {
                override fun createNew(name: String): FileChannel {
                    if (name == "intent.evidence") {
                        racedCreateNew = true
                        intent.writeBytes(exactBytes)
                    }
                    return acquired.createNew(name)
                }

                override fun forceDirectory(durability: LegacyAudioDurabilityPort) {
                    throw IOException("intent race adoption force failure")
                }
            }
        }

        assertFailsWith<IOException> {
            legacyAudioTestReconciler(filesDir, state, decoder(), capabilities = capability)
                .reconcile(descriptor)
        }

        assertTrue(racedCreateNew)
        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertContentEquals(exactBytes, intent.readBytes())
        assertEquals(setOf("intent.evidence"), attempt.list()!!.toSet())
    }

    @Test
    fun malformedIntentCreateNewRaceIsPreservedAndFailsClosed() {
        val filesDir = createTempDirectory("legacy-intent-malformed-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source)
        val attempt = requireNotNull(File(filesDir, descriptor.targetStorageKey).parentFile)
        val intent = File(attempt, "intent.evidence")
        val malformed = "concurrent malformed intent".toByteArray()
        val state = State()
        val capability = LegacyAudioCapabilityFactory { root, current ->
            val acquired = AnchoredTestCapabilityFactory().acquire(root, current)
            object : LegacyAudioAttemptCapability by acquired {
                override fun createNew(name: String): FileChannel {
                    if (name == "intent.evidence") intent.writeBytes(malformed)
                    return acquired.createNew(name)
                }
            }
        }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder(), capabilities = capability)
                .reconcile(descriptor)
        }

        assertEquals(BootstrapPhase.DISCOVERED, state.phase)
        assertContentEquals(malformed, intent.readBytes())
        assertEquals(setOf("intent.evidence"), attempt.list()!!.toSet())
    }

    @Test
    fun existingCopyWithoutIntentIsPreservedAndFailsClosed() {
        val filesDir = createTempDirectory("legacy-intent-missing-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source)
        val attempt =
            requireNotNull(File(filesDir, descriptor.targetStorageKey).parentFile).apply {
                mkdirs()
            }
        val copy = File(attempt, "legacy-audio").apply { writeText("foreign partial") }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, State(), decoder()).reconcile(descriptor)
        }

        assertEquals("foreign partial", copy.readText())
        assertFalse(File(attempt, "intent.evidence").exists())
    }

    @Test
    fun malformedIntentAndExistingCopyArePreservedAndFailClosed() {
        val filesDir = createTempDirectory("legacy-intent-malformed-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeText("approved") }
        val descriptor = descriptor(source)
        val attempt =
            requireNotNull(File(filesDir, descriptor.targetStorageKey).parentFile).apply {
                mkdirs()
            }
        val intent = File(attempt, "intent.evidence").apply { writeText("not an intent") }
        val copy = File(attempt, "legacy-audio").apply { writeText("foreign partial") }

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, State(), decoder()).reconcile(descriptor)
        }

        assertEquals("not an intent", intent.readText())
        assertEquals("foreign partial", copy.readText())
    }

    @Test
    fun validIntentAllowsDeterministicPartialCopyRewrite() {
        val filesDir = createTempDirectory("legacy-intent-valid-partial-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeBytes(ByteArray(96 * 1024) { 7 }) }
        val descriptor = descriptor(source)
        val state = State()
        var injected = false
        assertFailsWith<InjectedCrash> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    decoder(),
                    LegacyAudioBootstrapFaultInjector { point ->
                        if (
                            !injected && point == LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY
                        ) {
                            injected = true
                            throw InjectedCrash()
                        }
                    },
                )
                .reconcile(descriptor)
        }

        legacyAudioTestReconciler(filesDir, state, decoder()).reconcile(descriptor)

        assertEquals(
            source.readBytes().size.toLong(),
            File(filesDir, descriptor.targetStorageKey).length(),
        )
        assertEquals(BootstrapPhase.VALIDATED, state.phase)
    }

    @Test
    fun replacementBetweenRewritePrecheckAndOpenValidationIsNotTruncated() {
        val filesDir = createTempDirectory("legacy-intent-rewrite-race-").toFile()
        val source = File(filesDir, "source.mp3").apply { writeBytes(ByteArray(96 * 1024) { 3 }) }
        val descriptor = descriptor(source)
        val state = State()
        assertFailsWith<InjectedCrash> {
            legacyAudioTestReconciler(
                    filesDir,
                    state,
                    decoder(),
                    LegacyAudioBootstrapFaultInjector { point ->
                        if (point == LegacyAudioBootstrapFaultPoint.AFTER_PARTIAL_COPY)
                            throw InjectedCrash()
                    },
                )
                .reconcile(descriptor)
        }
        val replacementBytes = "replacement must survive".toByteArray()
        val replacement = File(filesDir, "replacement").apply { writeBytes(replacementBytes) }
        val capability =
            AnchoredTestCapabilityFactory(
                afterRewriteOpen = { slot ->
                    java.nio.file.Files.move(
                        replacement.toPath(),
                        slot,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            )

        assertFailsWith<IllegalArgumentException> {
            legacyAudioTestReconciler(filesDir, state, decoder(), capabilities = capability)
                .reconcile(descriptor)
        }

        assertEquals(
            "replacement must survive",
            File(filesDir, descriptor.targetStorageKey).readText(),
        )
    }

    private class InjectedCrash : RuntimeException()

    private fun descriptor(source: File): LegacyAudioBootstrapDescriptor {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("install-A", fingerprint)
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                "install-A",
                fingerprint,
                token,
                "bootstrap/$token/legacy-audio",
                BootstrapPhase.DISCOVERED.name,
            ),
            source.path,
        ) {
            LegacyAudioSourceSnapshot(source.path, fingerprint)
        }
    }

    private fun decoder() = LegacyAudioDecoder {
        AudioValidationMetadata("title", null, 1, "audio/mpeg")
    }

    private class State : LegacyAudioBootstrapStatePort {
        var phase = BootstrapPhase.DISCOVERED

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
