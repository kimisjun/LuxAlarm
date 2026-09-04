/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal enum class WakeDispatchSourceKind {
    START_PRIMARY,
    START_DYNAMIC_A,
    START_DYNAMIC_B,
    GOAL_DYNAMIC_A,
    GOAL_DYNAMIC_B,
    GOAL_PRIMARY,
    GOAL_PLUS_1M,
    GOAL_PLUS_5M,
    GOAL_PLUS_15M,
}

internal data class WakeDispatchSource(
    val kind: WakeDispatchSourceKind,
    val canonicalPendingIntentIdentity: String,
    val receivedAt: Long,
) {
    init {
        require(receivedAt >= 0L) { "Source receipt epoch must not be negative" }
        requireCanonicalPendingIntentIdentity(canonicalPendingIntentIdentity)
    }
}

/** Opaque trust object: construction is available only through the canonical factory. */
internal interface WakeDispatchAuthorization {
    val event: WakeEventIdentity
    val eventKey: String
    val scheduleGeneration: Long
    val dispatchAttemptId: Long
    val expectedExecutionEpoch: Long
    val leaseOwner: String
    val leaseExpiresAt: Long
    val requestedAt: Long
    val source: WakeDispatchSource
}

/** Named, auditable canonical construction path for dispatch trust objects. */
internal object WakeDispatchAuthorizationFactory {
    fun canonicalSource(
        event: WakeEventIdentity,
        kind: WakeDispatchSourceKind,
        canonicalPendingIntentIdentity: String,
        receivedAt: Long,
    ): WakeDispatchSource {
        val source = WakeDispatchSource(kind, canonicalPendingIntentIdentity, receivedAt)
        require(source.matches(event)) { "PendingIntent identity does not match source kind/event" }
        return source
    }

    fun create(
        event: WakeEventIdentity,
        scheduleGeneration: Long,
        dispatchAttemptId: Long,
        expectedExecutionEpoch: Long,
        leaseExpiresAt: Long,
        source: WakeDispatchSource,
    ): WakeDispatchAuthorization {
        val eventKey = event.canonicalKey()
        require(scheduleGeneration >= 0L) { "Schedule generation must not be negative" }
        require(dispatchAttemptId > 0L) { "Dispatch attempt must be positive" }
        require(expectedExecutionEpoch >= 0L) { "Execution epoch must not be negative" }
        require(leaseExpiresAt > source.receivedAt) { "Dispatch lease must expire after request" }
        require(source.matches(event)) { "Dispatch source does not match event identity" }
        val owner =
            canonicalLeaseOwner(
                scheduleGeneration,
                eventKey,
                dispatchAttemptId,
                expectedExecutionEpoch,
                source.kind,
                source.canonicalPendingIntentIdentity,
            )
        check(owner.isValidOwnerToken())
        return object : WakeDispatchAuthorization {
            override val event = event
            override val eventKey = eventKey
            override val scheduleGeneration = scheduleGeneration
            override val dispatchAttemptId = dispatchAttemptId
            override val expectedExecutionEpoch = expectedExecutionEpoch
            override val leaseOwner = owner
            override val leaseExpiresAt = leaseExpiresAt
            override val requestedAt = source.receivedAt
            override val source = source
        }
    }

    internal fun canonicalLeaseOwner(
        scheduleGeneration: Long,
        eventKey: String,
        nextAttempt: Long,
        expectedExecutionEpoch: Long,
        sourceKind: WakeDispatchSourceKind,
        canonicalPendingIntentIdentity: String,
    ): String {
        require(scheduleGeneration >= 0L)
        require(eventKey.isNotEmpty() && eventKey.length <= MAX_WAKE_EVENT_KEY_ASCII_CHARS)
        require(eventKey.all { it.code in 0x21..0x7e })
        require(nextAttempt > 0L)
        require(expectedExecutionEpoch >= 0L)
        requireCanonicalPendingIntentIdentity(canonicalPendingIntentIdentity)
        val envelope =
            listOf(
                    "wake-dispatch-v1",
                    "scheduleGeneration=$scheduleGeneration",
                    "eventKey=$eventKey",
                    "nextAttempt=$nextAttempt",
                    "expectedExecutionEpoch=$expectedExecutionEpoch",
                    "sourceKind=${sourceKind.name}",
                    "pendingIntentIdentity=$canonicalPendingIntentIdentity",
                )
                .joinToString("\u0000", postfix = "\u0000")
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(envelope.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(81) {
            append("wake-dispatch-v1-")
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
    }
}

private fun WakeDispatchSource.matches(event: WakeEventIdentity): Boolean {
    val parsed = WakePendingIntentData.parse(canonicalPendingIntentIdentity) ?: return false
    if (parsed.event != event) return false
    return parsed.match(
        onPrimary = { parsedEvent, identity ->
            parsedEvent == event &&
                identity == canonicalPendingIntentIdentity &&
                kind == WakeDispatchSourceKind.START_PRIMARY &&
                event.kind == WakeEventKind.START
        },
        onDynamic = { parsedEvent, arrival ->
            arrival.match(
                onPrimary = { false },
                onRecovery = onRecovery@{ arrivalEvent, slot, token, trigger ->
                        val expectedKind =
                            when (event.kind to slot) {
                                WakeEventKind.START to WakeRecoverySlotId.A ->
                                    WakeDispatchSourceKind.START_DYNAMIC_A
                                WakeEventKind.START to WakeRecoverySlotId.B ->
                                    WakeDispatchSourceKind.START_DYNAMIC_B
                                WakeEventKind.GOAL to WakeRecoverySlotId.A ->
                                    WakeDispatchSourceKind.GOAL_DYNAMIC_A
                                WakeEventKind.GOAL to WakeRecoverySlotId.B ->
                                    WakeDispatchSourceKind.GOAL_DYNAMIC_B
                                else -> return@onRecovery false
                            }
                        parsedEvent == event &&
                            arrivalEvent == event &&
                            kind == expectedKind &&
                            WakePendingIntentData.dynamic(event, slot, token, trigger) ==
                                canonicalPendingIntentIdentity
                    },
            )
        },
        onAnchor = { verified ->
            val expectedKind =
                when (verified.kind) {
                    WakeRecoveryAnchorKind.GOAL_PRIMARY -> WakeDispatchSourceKind.GOAL_PRIMARY
                    WakeRecoveryAnchorKind.GOAL_PLUS_1M -> WakeDispatchSourceKind.GOAL_PLUS_1M
                    WakeRecoveryAnchorKind.GOAL_PLUS_5M -> WakeDispatchSourceKind.GOAL_PLUS_5M
                    WakeRecoveryAnchorKind.GOAL_PLUS_15M -> WakeDispatchSourceKind.GOAL_PLUS_15M
                    WakeRecoveryAnchorKind.GOAL_PLUS_30M -> return@match false
                }
            verified.event == event &&
                verified.pendingIntentIdentity == canonicalPendingIntentIdentity &&
                kind == expectedKind
        },
    )
}
