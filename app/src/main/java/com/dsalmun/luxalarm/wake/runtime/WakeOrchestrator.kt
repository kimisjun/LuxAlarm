/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal data class WakeStartupIdentity(
    val snapshotId: String,
    val eventKey: String,
    val expectedExecutionEpoch: Long,
    val dispatchLeaseOwner: String,
    val serviceLeaseOwner: String,
) {
    init {
        require(snapshotId.isNotBlank()) { "Snapshot id must not be blank" }
        require(eventKey.isNotBlank()) { "Event key must not be blank" }
        require(expectedExecutionEpoch >= 0L) { "Execution epoch must not be negative" }
        require(dispatchLeaseOwner.isNotBlank()) { "Dispatch lease owner must not be blank" }
        require(serviceLeaseOwner.isNotBlank()) { "Service lease owner must not be blank" }
    }
}

internal data class WakeValidatedMirror(val snapshotId: String) {
    init {
        require(snapshotId.isNotBlank()) { "Snapshot id must not be blank" }
    }
}

internal data class WakeExecutionOwnership(
    val snapshotId: String,
    val eventKey: String,
    val executionEpoch: Long,
    val serviceLeaseOwner: String,
    val claimGeneration: Long,
) {
    init {
        require(snapshotId.isNotBlank()) { "Snapshot id must not be blank" }
        require(eventKey.isNotBlank()) { "Event key must not be blank" }
        require(executionEpoch >= 0L) { "Execution epoch must not be negative" }
        require(serviceLeaseOwner.isNotBlank()) { "Service lease owner must not be blank" }
        require(claimGeneration > 0L) { "Claim generation must be positive" }
    }
}

internal interface WakeForegroundHandle

internal interface WakeLockHandle

internal fun interface WakeClockPort {
    fun nowEpochMillis(): Long
}

internal interface WakeForegroundPort {
    /**
     * Returns an active handle, or throws only after any partial activation has been self-stopped.
     */
    fun enter(identity: WakeStartupIdentity): WakeForegroundHandle

    fun selfStop(handle: WakeForegroundHandle)
}

/**
 * Registration is inert; [activate] is the only operation allowed to make foreground state active.
 */
internal interface WakeForegroundActivationPort {
    fun register(identity: WakeStartupIdentity): WakeForegroundHandle

    fun activate(handle: WakeForegroundHandle)

    fun selfStop(handle: WakeForegroundHandle)
}

internal class ReturnOrCleanWakeForegroundPort(
    private val activation: WakeForegroundActivationPort
) : WakeForegroundPort {
    override fun enter(identity: WakeStartupIdentity): WakeForegroundHandle {
        val handle = activation.register(identity)
        return activateOrClean(handle, activation::activate, activation::selfStop)
    }

    override fun selfStop(handle: WakeForegroundHandle) = activation.selfStop(handle)
}

internal fun interface WakeSnapshotMirrorPort {
    fun validate(identity: WakeStartupIdentity): WakeValidatedMirror?
}

internal interface WakeLockPort {
    /**
     * Returns an acquired handle, or throws only after any partial acquisition has been released.
     */
    fun acquire(serviceLeaseOwner: String): WakeLockHandle

    fun release(handle: WakeLockHandle)
}

/** Registration is inert; [activate] is the only operation allowed to acquire the wake lock. */
internal interface WakeLockActivationPort {
    fun register(serviceLeaseOwner: String): WakeLockHandle

    fun activate(handle: WakeLockHandle)

    fun release(handle: WakeLockHandle)
}

internal class ReturnOrCleanWakeLockPort(private val activation: WakeLockActivationPort) :
    WakeLockPort {
    override fun acquire(serviceLeaseOwner: String): WakeLockHandle {
        val handle = activation.register(serviceLeaseOwner)
        return activateOrClean(handle, activation::activate, activation::release)
    }

    override fun release(handle: WakeLockHandle) = activation.release(handle)
}

private inline fun <H> activateOrClean(
    handle: H,
    activate: (H) -> Unit,
    cleanup: (H) -> Unit,
): H {
    try {
        activate(handle)
    } catch (primary: Throwable) {
        try {
            cleanup(handle)
        } catch (cleanupFailure: Throwable) {
            if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
        }
        throw primary
    }
    return handle
}

internal interface WakeDispatchClaimPort {
    /** If this throws, it must not retain a newly acquired claim that it did not return. */
    fun acknowledgeAndClaim(
        identity: WakeStartupIdentity,
        nowEpochMillis: Long,
    ): WakeExecutionOwnership?

    /**
     * Transitions the current claim to STARTING before [startEffects]. External callbacks run
     * without the ownership monitor. If startup throws, [rollbackEffects] completes while the claim
     * remains nonreplaceable; cleanup failures are suppressed onto the startup failure.
     */
    fun startEffectsIfCurrent(
        ownership: WakeExecutionOwnership,
        nowEpochMillis: Long,
        startEffects: () -> Unit,
        rollbackEffects: () -> Unit,
    ): Boolean

    /**
     * Releases an unstarted current claim. ACTIVE claims require fenced effect shutdown instead.
     */
    fun releaseIfCurrent(
        ownership: WakeExecutionOwnership,
        nowEpochMillis: Long,
    ): Boolean

    /**
     * If [ownership] is current, runs [stopEffects] while replacement claims are excluded and
     * releases the claim only after the effect shutdown has been attempted. It must attempt the
     * release even when [stopEffects] throws, preserving that throwable and suppressing a release
     * failure onto it.
     */
    fun stopEffectsAndReleaseIfCurrent(
        ownership: WakeExecutionOwnership,
        stopEffects: () -> Unit,
    ): Boolean
}

/** Pure reference adapter proving ownership atomicity without locking around external callbacks. */
internal class InMemoryWakeDispatchClaimPort : WakeDispatchClaimPort {
    private val ownershipFence = Any()
    private var currentOwnership: WakeExecutionOwnership? = null
    private var phase = Phase.IDLE
    private var lastClaimGeneration = 0L

    private enum class Phase {
        IDLE,
        CLAIMED,
        STARTING,
        ACTIVE,
        STOPPING,
        ROLLBACK,
    }

    override fun acknowledgeAndClaim(
        identity: WakeStartupIdentity,
        nowEpochMillis: Long,
    ): WakeExecutionOwnership? =
        synchronized(ownershipFence) {
            if (identity.expectedExecutionEpoch == Long.MAX_VALUE) return@synchronized null
            if (
                phase == Phase.STARTING ||
                    phase == Phase.ACTIVE ||
                    phase == Phase.STOPPING ||
                    phase == Phase.ROLLBACK
            ) {
                return@synchronized null
            }
            if (
                currentOwnership?.let {
                    identity.expectedExecutionEpoch < it.executionEpoch
                } == true
            ) {
                return@synchronized null
            }
            if (lastClaimGeneration == Long.MAX_VALUE) return@synchronized null
            val claimGeneration = lastClaimGeneration + 1L
            WakeExecutionOwnership(
                    snapshotId = identity.snapshotId,
                    eventKey = identity.eventKey,
                    executionEpoch = identity.expectedExecutionEpoch + 1L,
                    serviceLeaseOwner = identity.serviceLeaseOwner,
                    claimGeneration = claimGeneration,
                )
                .also {
                    lastClaimGeneration = claimGeneration
                    currentOwnership = it
                    phase = Phase.CLAIMED
                }
        }

    override fun startEffectsIfCurrent(
        ownership: WakeExecutionOwnership,
        nowEpochMillis: Long,
        startEffects: () -> Unit,
        rollbackEffects: () -> Unit,
    ): Boolean {
        val mayStart =
            synchronized(ownershipFence) {
                if (currentOwnership != ownership || phase != Phase.CLAIMED) {
                    false
                } else {
                    phase = Phase.STARTING
                    true
                }
            }
        if (!mayStart) return false
        try {
            startEffects()
        } catch (primary: Throwable) {
            synchronized(ownershipFence) {
                check(currentOwnership == ownership && phase == Phase.STARTING)
                phase = Phase.ROLLBACK
            }
            try {
                rollbackEffects()
            } catch (cleanupFailure: Throwable) {
                if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
            } finally {
                synchronized(ownershipFence) {
                    check(currentOwnership == ownership && phase == Phase.ROLLBACK)
                    currentOwnership = null
                    phase = Phase.IDLE
                }
            }
            throw primary
        }
        synchronized(ownershipFence) {
            check(currentOwnership == ownership && phase == Phase.STARTING)
            phase = Phase.ACTIVE
        }
        return true
    }

    override fun releaseIfCurrent(
        ownership: WakeExecutionOwnership,
        nowEpochMillis: Long,
    ): Boolean =
        synchronized(ownershipFence) {
            if (currentOwnership != ownership) return@synchronized false
            if (
                phase == Phase.STARTING ||
                    phase == Phase.ACTIVE ||
                    phase == Phase.STOPPING ||
                    phase == Phase.ROLLBACK
            ) {
                return@synchronized false
            }
            currentOwnership = null
            phase = Phase.IDLE
            true
        }

    override fun stopEffectsAndReleaseIfCurrent(
        ownership: WakeExecutionOwnership,
        stopEffects: () -> Unit,
    ): Boolean {
        val mayStop =
            synchronized(ownershipFence) {
                if (
                    currentOwnership != ownership ||
                        (phase != Phase.CLAIMED && phase != Phase.ACTIVE)
                ) {
                    false
                } else {
                    phase = Phase.STOPPING
                    true
                }
            }
        if (!mayStop) return false
        try {
            stopEffects()
        } finally {
            synchronized(ownershipFence) {
                check(currentOwnership == ownership && phase == Phase.STOPPING)
                currentOwnership = null
                phase = Phase.IDLE
            }
        }
        return true
    }
}

internal interface WakeEffectPort {
    fun start(
        mirror: WakeValidatedMirror,
        ownership: WakeExecutionOwnership,
    )

    fun stop(ownership: WakeExecutionOwnership)
}

internal class WakeRuntimeSession
internal constructor(
    val ownership: WakeExecutionOwnership,
    internal val foregroundHandle: WakeForegroundHandle,
    internal val wakeLockHandle: WakeLockHandle,
) {
    private val stopGate = AtomicReference<SessionStopGate>(SessionStopGate.Open)
    private val stopCompleted = CountDownLatch(1)

    internal fun stopOnce(action: () -> WakeStopResult): WakeStopResult {
        while (true) {
            when (val state = stopGate.get()) {
                SessionStopGate.Open -> {
                    val stopping = SessionStopGate.Stopping(Thread.currentThread())
                    if (!stopGate.compareAndSet(SessionStopGate.Open, stopping)) continue
                    val completion =
                        try {
                            SessionStopCompletion.Succeeded(action())
                        } catch (thrown: Throwable) {
                            SessionStopCompletion.Failed(thrown)
                        }
                    check(stopGate.compareAndSet(stopping, SessionStopGate.Completed(completion)))
                    stopCompleted.countDown()
                    return completion.replay()
                }
                is SessionStopGate.Stopping -> {
                    if (state.owner === Thread.currentThread()) {
                        return WakeStopResult.ReentrantStop
                    }
                    awaitStopCompletionUninterruptibly()
                }
                is SessionStopGate.Completed -> return state.completion.replay()
            }
        }
    }

    private fun awaitStopCompletionUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                stopCompleted.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private sealed interface SessionStopGate {
    data object Open : SessionStopGate

    class Stopping(val owner: Thread) : SessionStopGate

    class Completed(val completion: SessionStopCompletion) : SessionStopGate
}

private sealed interface SessionStopCompletion {
    fun replay(): WakeStopResult

    class Succeeded(private val result: WakeStopResult) : SessionStopCompletion {
        override fun replay() = result
    }

    class Failed(private val failure: Throwable) : SessionStopCompletion {
        override fun replay(): WakeStopResult = throw failure
    }
}

internal sealed interface WakeStartupResult {
    data class Started(val session: WakeRuntimeSession) : WakeStartupResult

    data object MissingRunIdentity : WakeStartupResult

    data object InvalidMirror : WakeStartupResult

    data object ClaimRejected : WakeStartupResult

    data object StaleClaim : WakeStartupResult
}

internal sealed interface WakeStopResult {
    data object Stopped : WakeStopResult

    data object StaleOwnership : WakeStopResult

    data object ReentrantStop : WakeStopResult
}

internal class WakeOrchestrator(
    private val clock: WakeClockPort,
    private val foreground: WakeForegroundPort,
    private val mirrors: WakeSnapshotMirrorPort,
    private val wakeLocks: WakeLockPort,
    private val dispatchClaims: WakeDispatchClaimPort,
    private val effects: WakeEffectPort,
) {
    fun start(identity: WakeStartupIdentity?): WakeStartupResult {
        identity ?: return WakeStartupResult.MissingRunIdentity
        val foregroundHandle = foreground.enter(identity)
        val mirror =
            try {
                mirrors.validate(identity)
            } catch (primary: Throwable) {
                throwAfterCleanup(primary, { foreground.selfStop(foregroundHandle) })
            }
        if (mirror == null || mirror.snapshotId != identity.snapshotId) {
            cleanupOrThrow({ foreground.selfStop(foregroundHandle) })
            return WakeStartupResult.InvalidMirror
        }
        val wakeLockHandle =
            try {
                wakeLocks.acquire(identity.serviceLeaseOwner)
            } catch (primary: Throwable) {
                throwAfterCleanup(primary, { foreground.selfStop(foregroundHandle) })
            }
        val now =
            try {
                clock.nowEpochMillis()
            } catch (primary: Throwable) {
                throwAfterCleanup(
                    primary,
                    { wakeLocks.release(wakeLockHandle) },
                    { foreground.selfStop(foregroundHandle) },
                )
            }
        val ownership =
            try {
                dispatchClaims.acknowledgeAndClaim(identity, now)
            } catch (primary: Throwable) {
                throwAfterCleanup(
                    primary,
                    { wakeLocks.release(wakeLockHandle) },
                    { foreground.selfStop(foregroundHandle) },
                )
            }
        if (ownership == null) {
            cleanupOrThrow(
                { wakeLocks.release(wakeLockHandle) },
                { foreground.selfStop(foregroundHandle) },
            )
            return WakeStartupResult.ClaimRejected
        }
        val expectedOwnership =
            ownership.snapshotId == identity.snapshotId &&
                ownership.eventKey == identity.eventKey &&
                identity.expectedExecutionEpoch < Long.MAX_VALUE &&
                ownership.executionEpoch == identity.expectedExecutionEpoch + 1L &&
                ownership.serviceLeaseOwner == identity.serviceLeaseOwner
        if (!expectedOwnership) {
            cleanupOrThrow(
                { dispatchClaims.releaseIfCurrent(ownership, now) },
                { wakeLocks.release(wakeLockHandle) },
                { foreground.selfStop(foregroundHandle) },
            )
            return WakeStartupResult.StaleClaim
        }
        val effectsStarted =
            try {
                dispatchClaims.startEffectsIfCurrent(
                    ownership,
                    now,
                    startEffects = { effects.start(mirror, ownership) },
                    rollbackEffects = { effects.stop(ownership) },
                )
            } catch (primary: Throwable) {
                throwAfterCleanup(
                    primary,
                    { wakeLocks.release(wakeLockHandle) },
                    { foreground.selfStop(foregroundHandle) },
                )
            }
        if (!effectsStarted) {
            cleanupOrThrow(
                { wakeLocks.release(wakeLockHandle) },
                { foreground.selfStop(foregroundHandle) },
            )
            return WakeStartupResult.StaleClaim
        }
        return WakeStartupResult.Started(
            WakeRuntimeSession(ownership, foregroundHandle, wakeLockHandle)
        )
    }

    fun stop(session: WakeRuntimeSession): WakeStopResult = session.stopOnce {
        var released = false
        var failure: Throwable? = null
        try {
            released =
                dispatchClaims.stopEffectsAndReleaseIfCurrent(session.ownership) {
                    effects.stop(session.ownership)
                }
        } catch (thrown: Throwable) {
            failure = thrown
        }
        failure =
            cleanup(
                failure,
                arrayOf(
                    { wakeLocks.release(session.wakeLockHandle) },
                    { foreground.selfStop(session.foregroundHandle) },
                ),
            )
        failure?.let { throw it }
        if (released) WakeStopResult.Stopped else WakeStopResult.StaleOwnership
    }

    private fun cleanupOrThrow(vararg cleanups: () -> Unit) {
        cleanup(null, cleanups)?.let { throw it }
    }

    private fun throwAfterCleanup(
        primary: Throwable,
        vararg cleanups: () -> Unit,
    ): Nothing = throw cleanup(primary, cleanups)!!

    private fun cleanup(
        primary: Throwable?,
        cleanups: Array<out () -> Unit>,
    ): Throwable? {
        var failure = primary
        cleanups.forEach { cleanup ->
            try {
                cleanup()
            } catch (thrown: Throwable) {
                if (failure == null) {
                    failure = thrown
                } else if (failure !== thrown) {
                    failure.addSuppressed(thrown)
                }
            }
        }
        return failure
    }
}
