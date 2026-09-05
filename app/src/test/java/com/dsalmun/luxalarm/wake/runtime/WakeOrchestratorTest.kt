/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class WakeOrchestratorTest {
    @Test
    fun identicalClaimAcceptedAfterReleaseHasFreshFenceAndOriginalCannotActOnReplacement() {
        val claims = InMemoryWakeDispatchClaimPort()
        val identity = startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "same")
        val original = assertNotNull(claims.acknowledgeAndClaim(identity, 10L))
        assertTrue(claims.releaseIfCurrent(original, 11L))

        val replacement = assertNotNull(claims.acknowledgeAndClaim(identity, 12L))

        assertTrue(original.claimGeneration != replacement.claimGeneration)
        assertTrue(original != replacement)
        var staleStartRan = false
        var staleStopRan = false
        assertFalse(
            claims.startEffectsIfCurrent(
                original,
                13L,
                startEffects = { staleStartRan = true },
                rollbackEffects = {},
            )
        )
        assertFalse(claims.stopEffectsAndReleaseIfCurrent(original) { staleStopRan = true })
        assertFalse(claims.releaseIfCurrent(original, 13L))
        assertFalse(staleStartRan)
        assertFalse(staleStopRan)
        assertTrue(
            claims.startEffectsIfCurrent(
                replacement,
                13L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(replacement) {})
    }

    @Test
    fun activeClaimRejectsLowerExpectedExecutionEpoch() {
        val claims = InMemoryWakeDispatchClaimPort()
        val current =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 4L, serviceLeaseOwner = "current"),
                    10L,
                )
            )
        assertTrue(
            claims.startEffectsIfCurrent(
                current,
                11L,
                startEffects = {},
                rollbackEffects = {},
            )
        )

        assertEquals(
            null,
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 3L, serviceLeaseOwner = "stale"),
                12L,
            ),
        )
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(current) {})
    }

    @Test
    fun failedEffectStartRejectsReplacementUntilRollbackCompletes() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        val startEntered = CountDownLatch(1)
        val allowStartFailure = CountDownLatch(1)
        val rollbackEntered = CountDownLatch(1)
        val allowRollback = CountDownLatch(1)
        val startFailure = IllegalStateException("partial effect activation")
        val thrown = AtomicReference<Throwable?>()
        val effectsActive = AtomicBoolean(false)

        val starter =
            thread(start = true, name = "failing-effect-starter") {
                thrown.set(
                    runCatching {
                            claims.startEffectsIfCurrent(
                                first,
                                11L,
                                startEffects = {
                                    effectsActive.set(true)
                                    startEntered.countDown()
                                    assertTrue(allowStartFailure.await(5, TimeUnit.SECONDS))
                                    throw startFailure
                                },
                                rollbackEffects = {
                                    rollbackEntered.countDown()
                                    assertTrue(allowRollback.await(5, TimeUnit.SECONDS))
                                    effectsActive.set(false)
                                },
                            )
                        }
                        .exceptionOrNull()
                )
            }
        assertTrue(startEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            null,
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                12L,
            ),
        )

        allowStartFailure.countDown()
        assertTrue(rollbackEntered.await(5, TimeUnit.SECONDS))
        assertEquals(
            null,
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                13L,
            ),
        )
        assertTrue(effectsActive.get())

        allowRollback.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertSame(startFailure, thrown.get())
        assertFalse(effectsActive.get())
        assertNotNull(
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                14L,
            )
        )
    }

    @Test
    fun effectStartPhaseRejectsReplacementUntilCallbackCompletes() {
        val claims = InMemoryWakeDispatchClaimPort()
        val firstIdentity =
            startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first")
        val first = assertNotNull(claims.acknowledgeAndClaim(firstIdentity, 10L))
        val callbackEntered = CountDownLatch(1)
        val allowCallbackToComplete = CountDownLatch(1)
        val firstStarted = AtomicBoolean(false)

        val starter =
            thread(start = true, name = "effect-starter") {
                firstStarted.set(
                    claims.startEffectsIfCurrent(
                        first,
                        11L,
                        startEffects = {
                            callbackEntered.countDown()
                            assertTrue(allowCallbackToComplete.await(5, TimeUnit.SECONDS))
                        },
                        rollbackEffects = {},
                    )
                )
            }
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        val secondIdentity =
            startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second")
        assertEquals(null, claims.acknowledgeAndClaim(secondIdentity, 12L))

        allowCallbackToComplete.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertTrue(firstStarted.get())
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(first) {})

        val second = assertNotNull(claims.acknowledgeAndClaim(secondIdentity, 13L))
        assertEquals(first.claimGeneration + 1L, second.claimGeneration)
        assertTrue(
            claims.startEffectsIfCurrent(
                second,
                15L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
    }

    @Test
    fun activeClaimRejectsSameEpochAndRemainsStoppableUntilReplacementClaim() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        assertTrue(
            claims.startEffectsIfCurrent(
                first,
                11L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
        val secondIdentity =
            startupIdentity(
                expectedExecutionEpoch = first.executionEpoch,
                serviceLeaseOwner = "second",
            )

        assertEquals(null, claims.acknowledgeAndClaim(secondIdentity, 12L))
        assertFalse(claims.releaseIfCurrent(first, 12L))
        assertEquals(null, claims.acknowledgeAndClaim(secondIdentity, 12L))
        var firstStopRan = false
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(first) { firstStopRan = true })
        assertTrue(firstStopRan)

        val second = assertNotNull(claims.acknowledgeAndClaim(secondIdentity, 13L))
        assertEquals(first.claimGeneration + 1L, second.claimGeneration)
    }

    @Test
    fun activeClaimRejectsHigherEpochBeforeReplacementEffectsCanStart() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        assertTrue(
            claims.startEffectsIfCurrent(
                first,
                11L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
        var replacementEffectsStarted = false

        val replacement =
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 2L, serviceLeaseOwner = "second"),
                12L,
            )
        replacement?.let {
            claims.startEffectsIfCurrent(
                it,
                13L,
                startEffects = {
                    replacementEffectsStarted = true
                    throw IllegalStateException("replacement startup failure")
                },
                rollbackEffects = {},
            )
        }

        assertEquals(null, replacement)
        assertFalse(replacementEffectsStarted)
        var firstStopRan = false
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(first) { firstStopRan = true })
        assertTrue(firstStopRan)
    }

    @Test
    fun startCallbackCannotReentrantlyReplaceItsClaim() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        var replacement: WakeExecutionOwnership? = first

        assertTrue(
            claims.startEffectsIfCurrent(
                first,
                11L,
                startEffects = {
                    replacement =
                        claims.acknowledgeAndClaim(
                            startupIdentity(
                                expectedExecutionEpoch = 1L,
                                serviceLeaseOwner = "second",
                            ),
                            12L,
                        )
                },
                rollbackEffects = {},
            )
        )

        assertEquals(null, replacement)
        var originalStopRan = false
        assertTrue(claims.stopEffectsAndReleaseIfCurrent(first) { originalStopRan = true })
        assertTrue(originalStopRan)
    }

    @Test
    fun stopCallbackCannotReentrantlyReplaceItsClaim() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        assertTrue(
            claims.startEffectsIfCurrent(
                first,
                11L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
        var replacement: WakeExecutionOwnership? = first

        assertTrue(
            claims.stopEffectsAndReleaseIfCurrent(first) {
                replacement =
                    claims.acknowledgeAndClaim(
                        startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                        12L,
                    )
            }
        )

        assertEquals(null, replacement)
        assertNotNull(
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                13L,
            )
        )
    }

    @Test
    fun throwingStopCallbackClearsOldClaimOnlyAfterCallbackAndPreservesThrowable() {
        val claims = InMemoryWakeDispatchClaimPort()
        val first =
            assertNotNull(
                claims.acknowledgeAndClaim(
                    startupIdentity(expectedExecutionEpoch = 0L, serviceLeaseOwner = "first"),
                    10L,
                )
            )
        assertTrue(
            claims.startEffectsIfCurrent(
                first,
                11L,
                startEffects = {},
                rollbackEffects = {},
            )
        )
        val stopFailure = IllegalStateException("effect cleanup")
        var replacementDuringStop: WakeExecutionOwnership? = first

        val thrown =
            runCatching {
                    claims.stopEffectsAndReleaseIfCurrent(first) {
                        replacementDuringStop =
                            claims.acknowledgeAndClaim(
                                startupIdentity(
                                    expectedExecutionEpoch = 1L,
                                    serviceLeaseOwner = "second",
                                ),
                                12L,
                            )
                        throw stopFailure
                    }
                }
                .exceptionOrNull()

        assertSame(stopFailure, thrown)
        assertEquals(null, replacementDuringStop)
        assertNotNull(
            claims.acknowledgeAndClaim(
                startupIdentity(expectedExecutionEpoch = 1L, serviceLeaseOwner = "second"),
                13L,
            )
        )
    }

    @Test
    fun foregroundReturnOrCleanAdapterSelfCleansPartialActivationFailure() {
        var active = false
        val primary = IllegalStateException("foreground activation")
        val cleanupFailure = IllegalArgumentException("foreground cleanup")
        val activation =
            object : WakeForegroundActivationPort {
                override fun register(identity: WakeStartupIdentity): WakeForegroundHandle =
                    object : WakeForegroundHandle {}

                override fun activate(handle: WakeForegroundHandle) {
                    active = true
                    throw primary
                }

                override fun selfStop(handle: WakeForegroundHandle) {
                    active = false
                    throw cleanupFailure
                }
            }
        val foreground = ReturnOrCleanWakeForegroundPort(activation)

        val thrown =
            runCatching { foreground.enter(startupIdentity(0L, "owner")) }.exceptionOrNull()

        assertSame(primary, thrown)
        assertEquals(listOf(cleanupFailure), thrown!!.suppressed.toList())
        assertFalse(active)
    }

    @Test
    fun wakeLockReturnOrCleanAdapterSelfCleansPartialActivationFailure() {
        var active = false
        val primary = IllegalStateException("wake lock activation")
        val activation =
            object : WakeLockActivationPort {
                override fun register(serviceLeaseOwner: String): WakeLockHandle =
                    object : WakeLockHandle {}

                override fun activate(handle: WakeLockHandle) {
                    active = true
                    throw primary
                }

                override fun release(handle: WakeLockHandle) {
                    active = false
                }
            }
        val wakeLocks = ReturnOrCleanWakeLockPort(activation)

        val thrown = runCatching { wakeLocks.acquire("owner") }.exceptionOrNull()

        assertSame(primary, thrown)
        assertFalse(active)
    }

    @Test
    fun claimPortMakesEffectStartRaceUnrepresentable() {
        val methodNames = WakeDispatchClaimPort::class.java.declaredMethods.map { it.name }.toSet()

        assertFalse("isCurrent" in methodNames)
        assertTrue("startEffectsIfCurrent" in methodNames)
        assertTrue("stopEffectsAndReleaseIfCurrent" in methodNames)
    }

    @Test
    fun startupOrdersForegroundMirrorWakeLockAtomicClaimAndEffects() {
        val ports = TracePorts()
        val orchestrator = ports.orchestrator()

        val result = orchestrator.start(ports.identity)

        assertIs<WakeStartupResult.Started>(result)
        assertEquals(
            listOf(
                "foreground",
                "mirror",
                "wake-lock",
                "clock",
                "claim",
                "fenced-start",
                "effects",
            ),
            ports.trace,
        )
        assertEquals(ports.claimedOwnership, result.session.ownership)
        assertEquals(1_234L, ports.claimedAt)
    }

    @Test
    fun invalidOrMissingMirrorSelfStopsBeforeWakeLockOrClaim() {
        val ports = TracePorts().apply { validatedMirror = null }

        val result = ports.orchestrator().start(ports.identity)

        assertIs<WakeStartupResult.InvalidMirror>(result)
        assertEquals(listOf("foreground", "mirror", "self-stop"), ports.trace)
    }

    @Test
    fun mismatchedMirrorIdentitySelfStopsBeforeWakeLockOrClaim() {
        val ports = TracePorts().apply { validatedMirror = WakeValidatedMirror("other-snapshot") }

        val result = ports.orchestrator().start(ports.identity)

        assertIs<WakeStartupResult.InvalidMirror>(result)
        assertEquals(listOf("foreground", "mirror", "self-stop"), ports.trace)
    }

    @Test
    fun missingRunIdentityCannotCreateDefaultOrTouchCapabilities() {
        val ports = TracePorts()

        val result = ports.orchestrator().start(null)

        assertIs<WakeStartupResult.MissingRunIdentity>(result)
        assertEquals(emptyList(), ports.trace)
    }

    @Test
    fun malformedClaimCannotIssueEffectsAndIsReleasedWithoutLeakingResources() {
        val staleClaims =
            listOf(
                WakeExecutionOwnership("snapshot-1", "event-1", 7L, "service-owner", 1L),
                WakeExecutionOwnership(
                    "snapshot-1",
                    "event-1",
                    8L,
                    "other-service-owner",
                    1L,
                ),
            )

        staleClaims.forEach { staleClaim ->
            val ports = TracePorts().apply { claimedOwnership = staleClaim }

            val result = ports.orchestrator().start(ports.identity)

            assertIs<WakeStartupResult.StaleClaim>(result)
            assertEquals(
                listOf(
                    "foreground",
                    "mirror",
                    "wake-lock",
                    "clock",
                    "claim",
                    "release-claim",
                    "wake-unlock",
                    "self-stop",
                ),
                ports.trace,
            )
        }
    }

    @Test
    fun stopDoesNotConsultFallibleClockBeforeFencedShutdown() {
        val ports = TracePorts()
        val started =
            assertIs<WakeStartupResult.Started>(ports.orchestrator().start(ports.identity))
        ports.trace.clear()
        ports.failures["clock"] = IllegalStateException("clock unavailable")

        val result = ports.orchestrator().stop(started.session)

        assertIs<WakeStopResult.Stopped>(result)
        assertEquals(
            listOf("fenced-stop", "stop-effects", "release-claim", "wake-unlock", "self-stop"),
            ports.trace,
        )
        assertFalse(ports.foregroundActive)
        assertFalse(ports.wakeLockActive)
        assertFalse(ports.claimActive)
        assertFalse(ports.effectsActive)
    }

    @Test
    fun currentOwnerStopsEffectsWhileFencedThenReleasesClaimAndResources() {
        val ports = TracePorts()
        val started =
            assertIs<WakeStartupResult.Started>(ports.orchestrator().start(ports.identity))
        ports.trace.clear()

        val result = ports.orchestrator().stop(started.session)

        assertIs<WakeStopResult.Stopped>(result)
        assertEquals(
            listOf(
                "fenced-stop",
                "stop-effects",
                "release-claim",
                "wake-unlock",
                "self-stop",
            ),
            ports.trace,
        )
    }

    @Test
    fun staleSessionCannotStopEffectsOrReleaseAnotherOwnerClaim() {
        val ports = TracePorts()
        val started =
            assertIs<WakeStartupResult.Started>(ports.orchestrator().start(ports.identity))
        ports.trace.clear()
        ports.isCurrentOwnership = false

        val result = ports.orchestrator().stop(started.session)

        assertIs<WakeStopResult.StaleOwnership>(result)
        assertEquals(listOf("fenced-stop", "wake-unlock", "self-stop"), ports.trace)
    }

    @Test
    fun rejectedClaimSelfStopsWithoutTouchingEffectsOrClaimOwnership() {
        val ports = TracePorts().apply { claimedOwnership = null }

        val result = ports.orchestrator().start(ports.identity)

        assertIs<WakeStartupResult.ClaimRejected>(result)
        assertEquals(
            listOf(
                "foreground",
                "mirror",
                "wake-lock",
                "clock",
                "claim",
                "wake-unlock",
                "self-stop",
            ),
            ports.trace,
        )
    }

    @Test
    fun startupFailureRetainsPrimaryAndSuppressesEveryReverseCleanupFailure() {
        val ports = TracePorts()
        val primary = IllegalStateException("effect-start")
        val stopFailure = IllegalArgumentException("effect-stop")
        val unlockFailure = IllegalArgumentException("wake-unlock")
        val foregroundFailure = IllegalArgumentException("self-stop")
        ports.failures +=
            mapOf(
                "effects" to primary,
                "stop-effects" to stopFailure,
                "wake-unlock" to unlockFailure,
                "self-stop" to foregroundFailure,
            )

        val thrown = runCatching { ports.orchestrator().start(ports.identity) }.exceptionOrNull()

        assertSame(primary, thrown)
        assertEquals(
            listOf(stopFailure, unlockFailure, foregroundFailure),
            thrown!!.suppressed.toList(),
        )
        assertEquals(
            listOf(
                "foreground",
                "mirror",
                "wake-lock",
                "clock",
                "claim",
                "fenced-start",
                "effects",
                "fenced-stop",
                "stop-effects",
                "release-claim",
                "wake-unlock",
                "self-stop",
            ),
            ports.trace,
        )
        assertFalse(ports.foregroundActive)
        assertFalse(ports.wakeLockActive)
        assertFalse(ports.claimActive)
        assertFalse(ports.effectsActive)
    }

    @Test
    fun startupExceptionsAtEachPreEffectBoundaryCleanOnlyAcquiredResourcesInReverse() {
        listOf("mirror", "wake-lock", "clock", "claim", "fenced-start").forEach { boundary ->
            val ports = TracePorts()
            val primary = IllegalStateException(boundary)
            ports.failures[boundary] = primary

            val thrown =
                runCatching { ports.orchestrator().start(ports.identity) }.exceptionOrNull()

            assertSame(primary, thrown, boundary)
            assertFalse(ports.foregroundActive, boundary)
            assertFalse(ports.wakeLockActive, boundary)
            assertFalse(ports.claimActive, boundary)
            assertFalse(ports.effectsActive, boundary)
        }
    }

    @Test
    fun stopAttemptsFencedShutdownReleaseAndAllResourcesWithSuppressedAggregation() {
        val ports = TracePorts()
        val started =
            assertIs<WakeStartupResult.Started>(ports.orchestrator().start(ports.identity))
        ports.trace.clear()
        val primary = IllegalStateException("effect-stop")
        val releaseFailure = IllegalArgumentException("release-claim")
        val unlockFailure = IllegalArgumentException("wake-unlock")
        val foregroundFailure = IllegalArgumentException("self-stop")
        ports.failures +=
            mapOf(
                "stop-effects" to primary,
                "release-claim" to releaseFailure,
                "wake-unlock" to unlockFailure,
                "self-stop" to foregroundFailure,
            )

        val thrown = runCatching { ports.orchestrator().stop(started.session) }.exceptionOrNull()

        assertSame(primary, thrown)
        assertEquals(
            listOf(releaseFailure, unlockFailure, foregroundFailure),
            thrown!!.suppressed.toList(),
        )
        assertEquals(
            listOf(
                "fenced-stop",
                "stop-effects",
                "release-claim",
                "wake-unlock",
                "self-stop",
            ),
            ports.trace,
        )
        assertFalse(ports.foregroundActive)
        assertFalse(ports.wakeLockActive)
        assertFalse(ports.claimActive)
        assertFalse(ports.effectsActive)
    }

    @Test
    fun concurrentDuplicateStopWaitsForWinnerAndCleansEveryHandleExactlyOnce() {
        val ports = TracePorts()
        val orchestrator = ports.orchestrator()
        val session =
            assertIs<WakeStartupResult.Started>(orchestrator.start(ports.identity)).session
        ports.trace.clear()
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        ports.beforeEffectStop = {
            stopEntered.countDown()
            assertTrue(allowStop.await(5, TimeUnit.SECONDS))
        }
        val firstResult = AtomicReference<WakeStopResult?>()
        val secondResult = AtomicReference<WakeStopResult?>()
        val secondArrived = CountDownLatch(1)

        val first = thread(name = "first-stop") { firstResult.set(orchestrator.stop(session)) }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
        val second =
            thread(name = "duplicate-stop") {
                secondArrived.countDown()
                secondResult.set(orchestrator.stop(session))
            }
        assertTrue(secondArrived.await(5, TimeUnit.SECONDS))
        awaitStopGateWait(second)

        assertTrue(second.isAlive)
        assertEquals(0, ports.wakeUnlockCount.get())
        assertEquals(0, ports.foregroundStopCount.get())
        allowStop.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertIs<WakeStopResult.Stopped>(firstResult.get())
        assertIs<WakeStopResult.Stopped>(secondResult.get())
        assertEquals(1, ports.effectStopCount.get())
        assertEquals(1, ports.wakeUnlockCount.get())
        assertEquals(1, ports.foregroundStopCount.get())
    }

    @Test
    fun concurrentDuplicateStopRethrowsSameCompletedFailureWithoutRepeatingCleanup() {
        val ports = TracePorts()
        val orchestrator = ports.orchestrator()
        val session =
            assertIs<WakeStartupResult.Started>(orchestrator.start(ports.identity)).session
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val failure = IllegalStateException("stop failed")
        ports.failures["stop-effects"] = failure
        ports.beforeEffectStop = {
            stopEntered.countDown()
            assertTrue(allowStop.await(5, TimeUnit.SECONDS))
        }
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()

        val first =
            thread(name = "first-failing-stop") {
                firstFailure.set(runCatching { orchestrator.stop(session) }.exceptionOrNull())
            }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
        val second =
            thread(name = "duplicate-failing-stop") {
                secondFailure.set(runCatching { orchestrator.stop(session) }.exceptionOrNull())
            }
        awaitStopGateWait(second)
        allowStop.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertSame(failure, firstFailure.get())
        assertSame(failure, secondFailure.get())
        assertEquals(1, ports.effectStopCount.get())
        assertEquals(1, ports.wakeUnlockCount.get())
        assertEquals(1, ports.foregroundStopCount.get())
    }

    @Test
    fun reentrantStopFromEffectCallbackDoesNotDeadlockOrTouchHandles() {
        val ports = TracePorts()
        val orchestrator = ports.orchestrator()
        val session =
            assertIs<WakeStartupResult.Started>(orchestrator.start(ports.identity)).session
        var nested: WakeStopResult? = null
        ports.beforeEffectStop = { nested = orchestrator.stop(session) }

        val result = orchestrator.stop(session)

        assertIs<WakeStopResult.Stopped>(result)
        assertIs<WakeStopResult.ReentrantStop>(nested)
        assertEquals(1, ports.effectStopCount.get())
        assertEquals(1, ports.wakeUnlockCount.get())
        assertEquals(1, ports.foregroundStopCount.get())
    }

    private fun awaitStopGateWait(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (System.nanoTime() < deadline) {
            val waitingInsideStopGate =
                thread.state == Thread.State.WAITING &&
                    thread.stackTrace.any {
                        it.className == WakeRuntimeSession::class.java.name &&
                            it.methodName == "awaitStopCompletionUninterruptibly"
                    }
            if (waitingInsideStopGate) return
            check(thread.isAlive) { "Duplicate stop completed before the winner was released" }
            Thread.yield()
        }
        throw AssertionError("Duplicate stop did not enter the session stop-gate wait")
    }

    private fun startupIdentity(
        expectedExecutionEpoch: Long,
        serviceLeaseOwner: String,
    ) =
        WakeStartupIdentity(
            snapshotId = "snapshot-1",
            eventKey = "event-1",
            expectedExecutionEpoch = expectedExecutionEpoch,
            dispatchLeaseOwner = "dispatch-$serviceLeaseOwner",
            serviceLeaseOwner = serviceLeaseOwner,
        )

    private class TracePorts :
        WakeClockPort,
        WakeForegroundPort,
        WakeSnapshotMirrorPort,
        WakeLockPort,
        WakeDispatchClaimPort,
        WakeEffectPort {
        val trace = mutableListOf<String>()
        val identity =
            WakeStartupIdentity(
                snapshotId = "snapshot-1",
                eventKey = "event-1",
                expectedExecutionEpoch = 7L,
                dispatchLeaseOwner = "dispatch-owner",
                serviceLeaseOwner = "service-owner",
            )
        val mirror = WakeValidatedMirror("snapshot-1")
        var validatedMirror: WakeValidatedMirror? = mirror
        var claimedOwnership: WakeExecutionOwnership? =
            WakeExecutionOwnership(
                snapshotId = "snapshot-1",
                eventKey = "event-1",
                executionEpoch = 8L,
                serviceLeaseOwner = "service-owner",
                claimGeneration = 1L,
            )
        var claimedAt: Long? = null
        var isCurrentOwnership = true
        val failures = mutableMapOf<String, Throwable>()
        var foregroundActive = false
        var wakeLockActive = false
        var claimActive = false
        var effectsActive = false
        var beforeEffectStop: (() -> Unit)? = null
        val effectStopCount = AtomicInteger()
        val wakeUnlockCount = AtomicInteger()
        val foregroundStopCount = AtomicInteger()

        private fun failAt(boundary: String) {
            failures[boundary]?.let { throw it }
        }

        fun orchestrator() = WakeOrchestrator(this, this, this, this, this, this)

        override fun nowEpochMillis(): Long {
            trace += "clock"
            failAt("clock")
            return 1_234L
        }

        override fun enter(identity: WakeStartupIdentity): WakeForegroundHandle {
            trace += "foreground"
            failAt("foreground")
            foregroundActive = true
            return object : WakeForegroundHandle {}
        }

        override fun selfStop(handle: WakeForegroundHandle) {
            trace += "self-stop"
            foregroundStopCount.incrementAndGet()
            foregroundActive = false
            failAt("self-stop")
        }

        override fun validate(identity: WakeStartupIdentity): WakeValidatedMirror? {
            trace += "mirror"
            failAt("mirror")
            return validatedMirror
        }

        override fun acquire(serviceLeaseOwner: String): WakeLockHandle {
            trace += "wake-lock"
            failAt("wake-lock")
            wakeLockActive = true
            return object : WakeLockHandle {}
        }

        override fun release(handle: WakeLockHandle) {
            trace += "wake-unlock"
            wakeUnlockCount.incrementAndGet()
            wakeLockActive = false
            failAt("wake-unlock")
        }

        override fun acknowledgeAndClaim(
            identity: WakeStartupIdentity,
            nowEpochMillis: Long,
        ): WakeExecutionOwnership? {
            trace += "claim"
            failAt("claim")
            claimedAt = nowEpochMillis
            claimActive = claimedOwnership != null
            return claimedOwnership
        }

        override fun startEffectsIfCurrent(
            ownership: WakeExecutionOwnership,
            nowEpochMillis: Long,
            startEffects: () -> Unit,
            rollbackEffects: () -> Unit,
        ): Boolean {
            trace += "fenced-start"
            val current = isCurrentOwnership && ownership == claimedOwnership
            try {
                failAt("fenced-start")
                if (current) startEffects()
            } catch (primary: Throwable) {
                if (current) {
                    try {
                        trace += "fenced-stop"
                        rollbackEffects()
                    } catch (cleanupFailure: Throwable) {
                        if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
                    }
                    try {
                        trace += "release-claim"
                        claimActive = false
                        failAt("release-claim")
                    } catch (cleanupFailure: Throwable) {
                        if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
                    }
                }
                throw primary
            }
            return current
        }

        override fun releaseIfCurrent(
            ownership: WakeExecutionOwnership,
            nowEpochMillis: Long,
        ): Boolean {
            trace += "release-claim"
            val current = isCurrentOwnership && ownership == claimedOwnership
            if (current) claimActive = false
            failAt("release-claim")
            return current
        }

        override fun stopEffectsAndReleaseIfCurrent(
            ownership: WakeExecutionOwnership,
            stopEffects: () -> Unit,
        ): Boolean {
            trace += "fenced-stop"
            val current = isCurrentOwnership && ownership == claimedOwnership
            if (current) {
                var failure: Throwable? = null
                try {
                    stopEffects()
                } catch (thrown: Throwable) {
                    failure = thrown
                }
                try {
                    trace += "release-claim"
                    claimActive = false
                    failAt("release-claim")
                } catch (thrown: Throwable) {
                    if (failure == null) failure = thrown else failure.addSuppressed(thrown)
                }
                failure?.let { throw it }
            }
            return current
        }

        override fun start(
            mirror: WakeValidatedMirror,
            ownership: WakeExecutionOwnership,
        ) {
            trace += "effects"
            effectsActive = true
            failAt("effects")
        }

        override fun stop(ownership: WakeExecutionOwnership) {
            trace += "stop-effects"
            effectStopCount.incrementAndGet()
            beforeEffectStop?.invoke()
            effectsActive = false
            failAt("stop-effects")
        }
    }
}
