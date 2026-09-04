/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.nio.charset.StandardCharsets

private const val MAX_OUTBOX_ID_ASCII_CHARS = 4096
private const val MAX_OUTBOX_ID_FIELDS = 16
private const val MAX_OUTBOX_ID_FIELD_ASCII_CHARS = 2048

/** Strict printable-ASCII length-prefix encoding for deterministic schedule-outbox identities. */
internal object ScheduleOutboxCanonicalizer {
    fun id(vararg fields: Pair<String, String>): String {
        require(fields.isNotEmpty() && fields.size <= MAX_OUTBOX_ID_FIELDS)
        val encoded = buildString {
            append("schedule-outbox-v1|")
            fields.forEach { (name, value) ->
                requirePrintableAscii(name)
                requirePrintableAscii(value)
                require(name.isNotEmpty() && name.length <= MAX_OUTBOX_ID_FIELD_ASCII_CHARS)
                require(value.length <= MAX_OUTBOX_ID_FIELD_ASCII_CHARS)
                append(name.length).append(':').append(name)
                append(value.length).append(':').append(value)
            }
        }
        require(encoded.length <= MAX_OUTBOX_ID_ASCII_CHARS) { "Schedule outbox id exceeds bound" }
        return encoded
    }

    fun hexUtf8(value: String): String {
        require(
            value.indices.all { index ->
                val character = value[index]
                when {
                    character.isHighSurrogate() ->
                        index + 1 < value.length && value[index + 1].isLowSurrogate()
                    character.isLowSurrogate() -> index > 0 && value[index - 1].isHighSurrogate()
                    else -> true
                }
            }
        ) {
            "Schedule outbox identity must be well-formed UTF-16"
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_OUTBOX_ID_FIELD_ASCII_CHARS / 2) {
            "Schedule outbox identity field exceeds bound"
        }
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
    }

    private fun requirePrintableAscii(value: String) {
        require(value.all { it.code in 0x21..0x7e }) {
            "Schedule outbox identity fields must be printable ASCII"
        }
    }
}
