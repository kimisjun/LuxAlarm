/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class WakeDispatchAuthorizationTest {
    private val start = WakeEventIdentity("authorization", WakeEventKind.START, 1_000L)

    @Test
    fun canonicalFactoryBuildsExactSourceAuthorizationAndVersionedOwner() {
        val identity = WakePendingIntentData.primary(start)
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                start,
                WakeDispatchSourceKind.START_PRIMARY,
                identity,
                1_000L,
            )
        val authorization =
            WakeDispatchAuthorizationFactory.create(
                event = start,
                scheduleGeneration = 7L,
                dispatchAttemptId = 3L,
                expectedExecutionEpoch = 11L,
                leaseExpiresAt = 61_000L,
                source = source,
            )
        val envelope =
            listOf(
                    "wake-dispatch-v1",
                    "scheduleGeneration=7",
                    "eventKey=${start.canonicalKey()}",
                    "nextAttempt=3",
                    "expectedExecutionEpoch=11",
                    "sourceKind=START_PRIMARY",
                    "pendingIntentIdentity=$identity",
                )
                .joinToString("\u0000", postfix = "\u0000")
        val expectedHex =
            MessageDigest.getInstance("SHA-256")
                .digest(envelope.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        assertEquals(start, authorization.event)
        assertEquals(start.canonicalKey(), authorization.eventKey)
        assertEquals(7L, authorization.scheduleGeneration)
        assertEquals(3L, authorization.dispatchAttemptId)
        assertEquals(11L, authorization.expectedExecutionEpoch)
        assertEquals("wake-dispatch-v1-$expectedHex", authorization.leaseOwner)
        assertEquals(61_000L, authorization.leaseExpiresAt)
        assertEquals(1_000L, authorization.requestedAt)
        assertEquals(source, authorization.source)
        assertTrue(authorization.leaseOwner.matches(Regex("wake-dispatch-v1-[0-9a-f]{64}")))
    }

    @Test
    fun ownerSeparatesEveryAuthorityDimension() {
        val primary = WakePendingIntentData.primary(start)
        val base = source(start, WakeDispatchSourceKind.START_PRIMARY, primary)
        val owners =
            listOf(
                auth(start, 7L, 3L, 11L, base).leaseOwner,
                auth(start, 8L, 3L, 11L, base).leaseOwner,
                auth(start, 7L, 4L, 11L, base).leaseOwner,
                auth(start, 7L, 3L, 12L, base).leaseOwner,
                auth(
                        start,
                        7L,
                        3L,
                        11L,
                        source(
                            start,
                            WakeDispatchSourceKind.START_DYNAMIC_A,
                            WakePendingIntentData.dynamic(start, WakeRecoverySlotId.A, 2L, 1_000L),
                        ),
                    )
                    .leaseOwner,
            )
        assertEquals(owners.size, owners.toSet().size)
    }

    @Test
    fun sourceFactoryRejectsEventKindSlotAnchorAndCanonicalIdentityMismatch() {
        val dynamicA = WakePendingIntentData.dynamic(start, WakeRecoverySlotId.A, 2L, 1_000L)
        assertFailsWith<IllegalArgumentException> {
            source(start, WakeDispatchSourceKind.START_DYNAMIC_B, dynamicA)
        }
        assertFailsWith<IllegalArgumentException> {
            source(start, WakeDispatchSourceKind.GOAL_PRIMARY, WakePendingIntentData.primary(start))
        }
        assertFailsWith<IllegalArgumentException> {
            source(start, WakeDispatchSourceKind.START_PRIMARY, dynamicA)
        }
        assertFailsWith<IllegalArgumentException> {
            source(start, WakeDispatchSourceKind.START_PRIMARY, dynamicA.uppercase())
        }
        assertFalse(WakeDispatchSourceKind.entries.any { it.name.contains("30") })
    }

    @Test
    fun authorizationInterfaceHasNoConstructorAndImplementationHasNoPublicBypass() {
        assertTrue(WakeDispatchAuthorization::class.java.isInterface)
        assertTrue(WakeDispatchAuthorization::class.java.declaredConstructors.isEmpty())
        val implementation =
            auth(
                    start,
                    7L,
                    3L,
                    11L,
                    source(
                        start,
                        WakeDispatchSourceKind.START_PRIMARY,
                        WakePendingIntentData.primary(start),
                    ),
                )
                .javaClass
        implementation.declaredConstructors.forEach { constructor ->
            assertFalse(Modifier.isPublic(constructor.modifiers))
            assertFalse(Modifier.isProtected(constructor.modifiers))
            assertTrue(
                constructor.parameterTypes.none {
                    it.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }
            )
        }
    }

    private fun source(
        event: WakeEventIdentity,
        kind: WakeDispatchSourceKind,
        identity: String,
    ) = WakeDispatchAuthorizationFactory.canonicalSource(event, kind, identity, 1_000L)

    private fun auth(
        event: WakeEventIdentity,
        generation: Long,
        attempt: Long,
        epoch: Long,
        source: WakeDispatchSource,
    ) =
        WakeDispatchAuthorizationFactory.create(
            event,
            generation,
            attempt,
            epoch,
            61_000L,
            source,
        )
}
