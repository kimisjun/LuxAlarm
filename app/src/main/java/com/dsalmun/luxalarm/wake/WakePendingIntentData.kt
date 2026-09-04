/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import com.dsalmun.luxalarm.data.AuthenticatedWakeEventArrivalFactory
import com.dsalmun.luxalarm.data.WakeEventArrival
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// Longest valid form is dynamic START with 192 snapshot bytes (384 hex chars) and
// maximum-width unsigned Long fields: 27 + 7 + 7 separators + 5 + 3 + 384 + 19 + 1 + 19 + 19.
private const val MAX_VALID_CANONICAL_URI_ASCII_CHARS = 491
internal const val MAX_CANONICAL_URI_ASCII_CHARS = MAX_VALID_CANONICAL_URI_ASCII_CHARS + 1

/**
 * Canonical, durable PendingIntent data identities for the dormant Task 5 receiver boundary.
 *
 * The eventual token tuple is fixed as broadcast + explicit receiver component + null action + this
 * canonical data + no categories/package. START uses request code 5100 and GOAL 5101. Creation uses
 * FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT; lookup uses FLAG_IMMUTABLE | FLAG_NO_CREATE. This codec
 * does not schedule or cancel alarms.
 */
internal object WakePendingIntentData {
    const val START_REQUEST_CODE = 5100
    const val GOAL_REQUEST_CODE = 5101

    fun primary(event: WakeEventIdentity): String =
        canonical(listOf("primary", event.kind.name) + eventSegments(event))

    fun dynamic(
        event: WakeEventIdentity,
        slot: WakeRecoverySlotId,
        token: Long,
        recoveryTriggerEpochMillis: Long,
    ): String {
        require(token in 0 until Long.MAX_VALUE)
        require(recoveryTriggerEpochMillis >= 0L)
        return canonical(
            listOf("dynamic", event.kind.name) +
                eventSegments(event) +
                listOf(slot.name, token.toString(), recoveryTriggerEpochMillis.toString())
        )
    }

    fun anchor(event: WakeEventIdentity, kind: WakeRecoveryAnchorKind): String {
        require(event.kind == WakeEventKind.GOAL)
        require(kind != WakeRecoveryAnchorKind.GOAL_PRIMARY)
        val trigger =
            requireNotNull(kind.triggerForGoalOrNull(event.expectedTriggerEpochMillis)) {
                "Anchor trigger overflows epoch range"
            }
        return canonical(
            listOf("anchor", WakeEventKind.GOAL.name) +
                eventSegments(event) +
                listOf(anchorWireToken(kind), trigger.toString())
        )
    }

    fun parse(value: String): ParsedWakePendingIntentData? {
        if (value.length > MAX_CANONICAL_URI_ASCII_CHARS) return null
        return runCatching { parseVerified(value) }.getOrNull()
    }

    private fun parseVerified(value: String): ParsedWakePendingIntentData {
        require(value.all { it.code in 0x21..0x7e })
        require('%' !in value)
        val uri = URI(value)
        require(uri.scheme == "gentlewake")
        require(uri.rawAuthority == "wake-event")
        require(uri.rawUserInfo == null && uri.port == -1)
        require(uri.rawQuery == null && uri.rawFragment == null)
        val path = requireNotNull(uri.rawPath)
        require(path.startsWith('/') && !path.endsWith('/'))
        val segments = path.substring(1).split('/')
        require(segments.none(String::isEmpty))
        require(segments.firstOrNull() == "v1")
        return when (segments.getOrNull(1)) {
            "primary" -> parsePrimary(segments, value)
            "dynamic" -> parseDynamic(segments, value)
            "anchor" -> parseAnchor(segments, value)
            else -> error("Unknown wake data kind")
        }
    }

    private fun parsePrimary(segments: List<String>, value: String): ParsedWakePendingIntentData {
        require(segments.size == 6)
        val event =
            parseEvent(
                WakeEventKind.valueOf(segments[2]),
                segments[3],
                segments[4],
                segments[5],
            )
        require(primary(event) == value)
        if (event.kind == WakeEventKind.GOAL) {
            val verified =
                AuthenticatedWakeRecoveryAnchorArrivalFactory.fromVerifiedPendingIntentData(
                    event,
                    WakeRecoveryAnchorKind.GOAL_PRIMARY,
                    event.expectedTriggerEpochMillis,
                    value,
                )
            return parsedAnchor(verified)
        }
        return parsedPrimary(event, value)
    }

    private fun parseDynamic(segments: List<String>, value: String): ParsedWakePendingIntentData {
        require(segments.size == 9)
        val event =
            parseEvent(
                WakeEventKind.valueOf(segments[2]),
                segments[3],
                segments[4],
                segments[5],
            )
        val slot = WakeRecoverySlotId.valueOf(segments[6])
        val token = parseCanonicalUnsigned(segments[7]).also { require(it < Long.MAX_VALUE) }
        val recoveryTrigger = parseCanonicalUnsigned(segments[8])
        require(dynamic(event, slot, token, recoveryTrigger) == value)
        val arrival =
            AuthenticatedWakeEventArrivalFactory.fromVerifiedPendingIntentData(
                event,
                slot,
                token,
                recoveryTrigger,
            )
        return parsedDynamic(event, arrival)
    }

    private fun parseAnchor(segments: List<String>, value: String): ParsedWakePendingIntentData {
        require(segments.size == 8)
        require(segments[2] == WakeEventKind.GOAL.name)
        val event = parseEvent(WakeEventKind.GOAL, segments[3], segments[4], segments[5])
        val kind = anchorKindFromWireToken(segments[6])
        val trigger = parseCanonicalUnsigned(segments[7])
        require(kind.triggerForGoalOrNull(event.expectedTriggerEpochMillis) == trigger)
        require(anchor(event, kind) == value)
        val verified =
            AuthenticatedWakeRecoveryAnchorArrivalFactory.fromVerifiedPendingIntentData(
                event,
                kind,
                trigger,
                value,
            )
        return parsedAnchor(verified)
    }

    private fun eventSegments(event: WakeEventIdentity): List<String> {
        val bytes = event.snapshotId.toByteArray(StandardCharsets.UTF_8)
        val hexChars = "0123456789abcdef"
        val hex =
            buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(hexChars[unsigned ushr 4])
                    append(hexChars[unsigned and 0x0f])
                }
            }
        return listOf(bytes.size.toString(), hex, event.expectedTriggerEpochMillis.toString())
    }

    private fun parseEvent(
        kind: WakeEventKind,
        countText: String,
        hex: String,
        triggerText: String,
    ): WakeEventIdentity {
        val count = parseCanonicalUnsigned(countText).also { require(it in 1..192) }.toInt()
        require(hex.length == count * 2 && hex.all { it in "0123456789abcdef" })
        val bytes =
            ByteArray(count) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        val snapshot =
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        return WakeEventIdentity(snapshot, kind, parseCanonicalUnsigned(triggerText))
    }

    private fun parseCanonicalUnsigned(text: String): Long {
        require(text == "0" || (text.isNotEmpty() && text[0] in '1'..'9'))
        require(text.all { it in '0'..'9' })
        return text.toLong()
    }

    private fun canonical(segments: List<String>): String =
        "gentlewake://wake-event/v1/" + segments.joinToString("/")

    private fun anchorWireToken(kind: WakeRecoveryAnchorKind): String =
        when (kind) {
            WakeRecoveryAnchorKind.GOAL_PLUS_1M -> "PLUS1"
            WakeRecoveryAnchorKind.GOAL_PLUS_5M -> "PLUS5"
            WakeRecoveryAnchorKind.GOAL_PLUS_15M -> "PLUS15"
            WakeRecoveryAnchorKind.GOAL_PLUS_30M -> "PLUS30"
            WakeRecoveryAnchorKind.GOAL_PRIMARY -> error("Primary GOAL has no anchor wire token")
        }

    private fun anchorKindFromWireToken(token: String): WakeRecoveryAnchorKind =
        when (token) {
            "PLUS1" -> WakeRecoveryAnchorKind.GOAL_PLUS_1M
            "PLUS5" -> WakeRecoveryAnchorKind.GOAL_PLUS_5M
            "PLUS15" -> WakeRecoveryAnchorKind.GOAL_PLUS_15M
            "PLUS30" -> WakeRecoveryAnchorKind.GOAL_PLUS_30M
            else -> error("Unknown anchor wire token")
        }

    private fun parsedPrimary(
        parsedEvent: WakeEventIdentity,
        identity: String,
    ): ParsedWakePendingIntentData =
        object : ParsedWakePendingIntentData {
            override val event = parsedEvent

            override fun <T> match(
                onPrimary: (WakeEventIdentity, String) -> T,
                onDynamic: (WakeEventIdentity, WakeEventArrival) -> T,
                onAnchor: (VerifiedWakeRecoveryAnchorArrival) -> T,
            ): T = onPrimary(event, identity)
        }

    private fun parsedDynamic(
        parsedEvent: WakeEventIdentity,
        arrival: WakeEventArrival,
    ): ParsedWakePendingIntentData =
        object : ParsedWakePendingIntentData {
            override val event = parsedEvent

            override fun <T> match(
                onPrimary: (WakeEventIdentity, String) -> T,
                onDynamic: (WakeEventIdentity, WakeEventArrival) -> T,
                onAnchor: (VerifiedWakeRecoveryAnchorArrival) -> T,
            ): T = onDynamic(event, arrival)
        }

    private fun parsedAnchor(
        verified: VerifiedWakeRecoveryAnchorArrival
    ): ParsedWakePendingIntentData =
        object : ParsedWakePendingIntentData {
            override val event = verified.event

            override fun <T> match(
                onPrimary: (WakeEventIdentity, String) -> T,
                onDynamic: (WakeEventIdentity, WakeEventArrival) -> T,
                onAnchor: (VerifiedWakeRecoveryAnchorArrival) -> T,
            ): T = onAnchor(verified)
        }
}

/** Opaque parser result: production routing can only branch through [match]. */
internal interface ParsedWakePendingIntentData {
    val event: WakeEventIdentity

    fun <T> match(
        onPrimary: (WakeEventIdentity, String) -> T,
        onDynamic: (WakeEventIdentity, WakeEventArrival) -> T,
        onAnchor: (VerifiedWakeRecoveryAnchorArrival) -> T,
    ): T
}
