/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class WakePendingIntentDataTest {
    private val start = WakeEventIdentity("아침", WakeEventKind.START, 1_725_000_000_123L)
    private val goal = WakeEventIdentity("goal-1", WakeEventKind.GOAL, 2_000L)

    @Test
    fun maximumGrammarUriFitsBoundAndRoundTripsWhileBoundPlusOneIsRejected() {
        val maximum =
            WakePendingIntentData.dynamic(
                WakeEventIdentity(
                    "x".repeat(MAX_WAKE_SNAPSHOT_ID_UTF8_BYTES),
                    WakeEventKind.START,
                    Long.MAX_VALUE,
                ),
                WakeRecoverySlotId.A,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE,
            )

        assertEquals(491, maximum.length)
        assertTrue(maximum.length < MAX_CANONICAL_URI_ASCII_CHARS)
        assertNotNull(WakePendingIntentData.parse(maximum))
        assertNull(WakePendingIntentData.parse("x".repeat(MAX_CANONICAL_URI_ASCII_CHARS + 1)))
    }

    @Test
    fun requestCodesReserveTheDocumentedEventualBroadcastTuples() {
        assertEquals(5100, WakePendingIntentData.START_REQUEST_CODE)
        assertEquals(5101, WakePendingIntentData.GOAL_REQUEST_CODE)
    }

    @Test
    fun primaryUsesExactCanonicalUriAndParsesItsIdentity() {
        val encoded = WakePendingIntentData.primary(start)

        assertEquals(
            "gentlewake://wake-event/v1/primary/START/6/ec9584ecb9a8/1725000000123",
            encoded,
        )
        assertEquals("primary:${start.canonicalKey()}:$encoded", describe(encoded))
    }

    @Test
    fun dynamicRoundTripsThroughAuthenticatedArrivalFactory() {
        val encoded = WakePendingIntentData.dynamic(start, WakeRecoverySlotId.B, 17L, 2_500L)

        assertEquals(
            "gentlewake://wake-event/v1/dynamic/START/6/ec9584ecb9a8/1725000000123/B/17/2500",
            encoded,
        )
        assertEquals(
            "dynamic:${start.canonicalKey()}:B:17:2500",
            describe(encoded),
        )
    }

    @Test
    fun anchorsRoundTripAndGoalPrimaryUsesThePrimaryIdentityPath() {
        WakeRecoveryAnchorKind.entries.forEach { kind ->
            val encoded =
                if (kind == WakeRecoveryAnchorKind.GOAL_PRIMARY) {
                    WakePendingIntentData.primary(goal)
                } else {
                    WakePendingIntentData.anchor(goal, kind)
                }
            val trigger = requireNotNull(kind.triggerForGoalOrNull(goal.expectedTriggerEpochMillis))
            assertEquals(
                "anchor:${goal.canonicalKey()}:${kind.name}:$trigger:$encoded",
                describe(encoded),
            )
        }
        assertEquals(
            "gentlewake://wake-event/v1/anchor/GOAL/6/676f616c2d31/2000/PLUS1/62000",
            WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M),
        )
        assertFailsWith<IllegalArgumentException> {
            WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PRIMARY)
        }
        assertFailsWith<IllegalArgumentException> {
            WakePendingIntentData.anchor(start, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        }
    }

    @Test
    fun anchorWireTokensAreExactAndMapExplicitlyToStoredKinds() {
        val expected =
            mapOf(
                WakeRecoveryAnchorKind.GOAL_PLUS_1M to "PLUS1",
                WakeRecoveryAnchorKind.GOAL_PLUS_5M to "PLUS5",
                WakeRecoveryAnchorKind.GOAL_PLUS_15M to "PLUS15",
                WakeRecoveryAnchorKind.GOAL_PLUS_30M to "PLUS30",
            )

        expected.forEach { (storedKind, wireToken) ->
            val encoded = WakePendingIntentData.anchor(goal, storedKind)
            assertEquals(wireToken, encoded.split('/')[9], storedKind.name)
            assertEquals(
                storedKind,
                requireNotNull(WakePendingIntentData.parse(encoded))
                    .match(
                        onPrimary = { _, _ -> error("not primary") },
                        onDynamic = { _, _ -> error("not dynamic") },
                        onAnchor = { it.kind },
                    ),
            )
        }
    }

    @Test
    fun anchorParserRejectsStoredNamesPrimaryStartCaseChangesAndUnknownTokens() {
        val canonical = WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        listOf(
                "GOAL_PLUS_1M",
                "GOAL_PLUS_5M",
                "GOAL_PLUS_15M",
                "GOAL_PLUS_30M",
                "GOAL_PRIMARY",
                "START",
                "plus1",
                "Plus1",
                "PLUS01",
                "UNKNOWN",
            )
            .forEach { token ->
                assertNull(
                    WakePendingIntentData.parse(canonical.replace("/PLUS1/", "/$token/")),
                    token,
                )
            }
    }

    @Test
    fun malformedOrNoncanonicalUrisAreRejected() {
        val canonical = WakePendingIntentData.primary(start)
        val dynamic = WakePendingIntentData.dynamic(start, WakeRecoverySlotId.A, 0L, 2_500L)
        val anchor = WakePendingIntentData.anchor(goal, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        val cases =
            listOf(
                canonical.replace("ec95", "EC95"),
                canonical.replace("ec95", "%ec95"),
                "$canonical?event=trusted",
                "$canonical#trusted",
                "$canonical/extra",
                "$canonical/",
                canonical.replace("/primary/", "/unknown/"),
                canonical.replace("/v1/primary", "/v1/v1/primary"),
                canonical.replace("/6/", "/06/"),
                canonical.replace("/1725000000123", "/01725000000123"),
                canonical.replace("1725000000123", "9223372036854775808"),
                dynamic.replace("/0/2500", "/00/2500"),
                dynamic.replace("/0/2500", "/9223372036854775807/2500"),
                dynamic.replace("/2500", "/9223372036854775808"),
                dynamic.replace("/A/", "/a/"),
                anchor.replace("/62000", "/62001"),
                anchor.replace("/PLUS1/", "/GOAL_PRIMARY/"),
                anchor.replace("/anchor/GOAL/", "/anchor/START/"),
                "gentlewake://wake-event/v1/primary/START/2/c328/1",
                "gentlewake://wake-event/v1/primary/START/1/20/1",
                "gentlewake://wake-event/v1/primary/START/1/00/1",
                "gentlewake://wake-event/v1/primary/START/0//1",
                "gentlewake://wake-event/v1/primary/START/193/${"61".repeat(193)}/1",
                canonical.replace("gentlewake", "Gentlewake"),
                canonical.replace("wake-event", "Wake-event"),
                canonical.replace("/START/", "/start/"),
                canonical.replace("wake-event/", "user@wake-event/"),
                canonical.replace("wake-event/", "wake-event:7/"),
                canonical.replace("//wake-event/", "/wake-event/"),
                canonical.replace("//wake-event/", "///wake-event/"),
                canonical.replace("/v1/", "/v1//"),
                canonical.replace("/primary/", "/PRIMARY/"),
                canonical.replace("/v1/", "/V1/"),
                canonical.replace("/6/", "/7/"),
                canonical.replace("/6/ec", "/6/eg"),
                canonical.replace("/6/ec", "/6/EC"),
                canonical.replace("/1725000000123", "/+1725000000123"),
                canonical.replace("/1725000000123", "/-1"),
                canonical.replace("/1725000000123", "/"),
                canonical.replace("gentlewake://", "gentlewake:opaque:"),
                canonical.replace("gentlewake://wake-event", "gentlewake://wake-event."),
                canonical.replace("gentlewake://wake-event", "other://wake-event"),
                "",
                "gentlewake://wake-event",
                "gentlewake://wake-event/",
                "gentlewake://wake-event/v1",
                "gentlewake://wake-event/v1/primary/START/1/c0/1",
                "gentlewake://wake-event/v1/primary/START/1/80/1",
                "gentlewake://wake-event/v1/primary/START/2/e282/1",
                dynamic.replace("/A/", "/C/"),
                dynamic.replace("/dynamic/START/", "/dynamic/start/"),
                anchor.replace("/PLUS1/", "/plus1/"),
                anchor.replace("/PLUS1/", "/UNKNOWN/"),
            )
        cases.forEach { candidate -> assertNull(WakePendingIntentData.parse(candidate), candidate) }
    }

    @Test
    fun canonicalBoundariesRoundTripByteForByte() {
        val oneByte = WakeEventIdentity("x", WakeEventKind.START, 0L)
        val maxBytes = WakeEventIdentity("한".repeat(64), WakeEventKind.GOAL, Long.MAX_VALUE)
        val maxToken = Long.MAX_VALUE - 1L

        listOf(
                WakePendingIntentData.primary(oneByte),
                WakePendingIntentData.primary(maxBytes),
                WakePendingIntentData.dynamic(
                    oneByte,
                    WakeRecoverySlotId.A,
                    maxToken,
                    Long.MAX_VALUE,
                ),
            )
            .forEach { encoded ->
                assertNotNull(WakePendingIntentData.parse(encoded), encoded)
                assertTrue(encoded.all { it.code in 0x21..0x7e }, encoded)
            }
        assertEquals(
            "gentlewake://wake-event/v1/dynamic/START/1/78/0/A/9223372036854775806/9223372036854775807",
            WakePendingIntentData.dynamic(oneByte, WakeRecoverySlotId.A, maxToken, Long.MAX_VALUE),
        )
        assertFailsWith<IllegalArgumentException> {
            WakePendingIntentData.dynamic(oneByte, WakeRecoverySlotId.A, Long.MAX_VALUE, 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            WakePendingIntentData.anchor(maxBytes, WakeRecoveryAnchorKind.GOAL_PLUS_1M)
        }
    }

    @Test
    fun snapshotValidationRejectsMalformedSurrogateBeforeSerialization() {
        assertFailsWith<IllegalArgumentException> {
            WakeEventIdentity("bad\uD800", WakeEventKind.START, 1L)
        }
    }

    private fun describe(value: String): String {
        val parsed = assertNotNull(WakePendingIntentData.parse(value), value)
        return parsed.match(
            onPrimary = { event, identity -> "primary:${event.canonicalKey()}:$identity" },
            onDynamic = { event, arrival ->
                arrival.match(
                    onPrimary = { error("parser returned raw primary as dynamic") },
                    onRecovery = { verifiedEvent, slot, token, trigger ->
                        assertEquals(event, verifiedEvent)
                        "dynamic:${event.canonicalKey()}:${slot.name}:$token:$trigger"
                    },
                )
            },
            onAnchor = { verified ->
                val delivery = verified.deliveryAt(verified.triggerEpochMillis)
                "anchor:${delivery.event.canonicalKey()}:${delivery.kind.name}:" +
                    "${delivery.triggerEpochMillis}:${delivery.pendingIntentIdentity}"
            },
        )
    }
}
