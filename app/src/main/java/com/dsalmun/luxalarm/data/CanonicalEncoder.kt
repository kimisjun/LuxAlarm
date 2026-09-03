/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal const val MAX_CANONICAL_FIELD_PAYLOAD_BYTES = 192 * 1024
internal const val MAX_CANONICAL_STRING_UTF8_BYTES = MAX_CANONICAL_FIELD_PAYLOAD_BYTES
internal const val MAX_CANONICAL_FIELDS_PER_RECORD = 32
internal const val MAX_CANONICAL_COLLECTION_ELEMENTS = 128
internal const val MAX_CANONICAL_COLLECTION_BYTES = 192 * 1024
internal const val MAX_CANONICAL_RECORD_BYTES = 224 * 1024
internal const val MAX_CANONICAL_TOTAL_BYTES = 256 * 1024
private const val MAX_CANONICAL_TAG_UTF8_BYTES = 256

/** Versioned, unambiguous UTF-8 type-length-value encoding for migration identities. */
internal object CanonicalEncoder {
    fun record(schema: String, version: Int, block: RecordBuilder.() -> Unit): ByteArray {
        require(schema.isNotEmpty()) { "Canonical record schema must not be empty" }
        requireBoundedUtf8("Canonical record schema", schema, MAX_CANONICAL_TAG_UTF8_BYTES)
        require(version > 0) { "Canonical record version must be positive" }
        val payload = RecordBuilder().apply(block).toByteArray()
        require(payload.size <= MAX_CANONICAL_RECORD_BYTES) { "Canonical record is too large" }
        return frame("record/$schema/v$version", payload)
    }

    internal fun frame(tag: String, payload: ByteArray): ByteArray {
        require(tag.isNotEmpty()) { "Canonical type tag must not be empty" }
        val tagBytes = encodeBoundedUtf8("Canonical type tag", tag, MAX_CANONICAL_TAG_UTF8_BYTES)
        require(payload.size <= MAX_CANONICAL_TOTAL_BYTES) { "Canonical payload is too large" }
        val encodedSize =
            tagBytes.size.toString().length +
                1 +
                tagBytes.size +
                payload.size.toString().length +
                1 +
                payload.size
        require(encodedSize <= MAX_CANONICAL_TOTAL_BYTES) { "Canonical encoding is too large" }
        return ByteArrayOutputStream()
            .apply {
                writeAscii(tagBytes.size.toString())
                write(':'.code)
                write(tagBytes)
                writeAscii(payload.size.toString())
                write(':'.code)
                write(payload)
            }
            .toByteArray()
    }

    class RecordBuilder internal constructor() {
        private val fields = ByteArrayOutputStream()
        private var fieldCount = 0

        fun string(name: String, value: String) {
            requireFieldName(name)
            val payload =
                encodeBoundedUtf8(
                    "Canonical string field '$name'",
                    value,
                    MAX_CANONICAL_STRING_UTF8_BYTES,
                )
            field(name, "string", payload)
        }

        fun nullableString(name: String, value: String?) =
            if (value == null) nullField(name) else string(name, value)

        fun int(name: String, value: Int) = field(name, "int", value.toString().utf8())

        fun long(name: String, value: Long) = field(name, "long", value.toString().utf8())

        fun nullableLong(name: String, value: Long?) =
            if (value == null) nullField(name) else long(name, value)

        fun float(name: String, value: Float) {
            require(value.isFinite()) { "Canonical floats must be finite" }
            val payload = if (value == 0f) "0.0" else value.toString()
            field(name, "float", payload.utf8())
        }

        fun boolean(name: String, value: Boolean) =
            field(name, "boolean", if (value) "true".utf8() else "false".utf8())

        fun enum(name: String, value: Enum<*>) = field(name, "enum", value.name.utf8())

        fun sortedInts(name: String, values: Collection<Int>) {
            requireCollectionCount(values.size)
            collection(name, "int", values.sorted().map { it.toString().utf8() })
        }

        fun sortedStrings(name: String, values: Collection<String>) {
            requireFieldName(name)
            requireCollectionCount(values.size)
            collection(
                name,
                "string",
                values.sorted().mapIndexed { index, value ->
                    encodeBoundedUtf8(
                        "Canonical string collection '$name' element $index",
                        value,
                        MAX_CANONICAL_STRING_UTF8_BYTES,
                    )
                },
            )
        }

        fun records(name: String, values: Collection<ByteArray>) {
            requireCollectionCount(values.size)
            collection(name, "record", values.sortedWith(::compareBytes))
        }

        fun encodedRecord(name: String, value: ByteArray) = field(name, "record", value)

        internal fun toByteArray(): ByteArray = fields.toByteArray()

        private fun nullField(name: String) = field(name, "null", byteArrayOf())

        private fun collection(name: String, elementType: String, values: List<ByteArray>) {
            requireCollectionCount(values.size)
            val payload = ByteArrayOutputStream()
            fun append(value: ByteArray) {
                require(payload.size() + value.size <= MAX_CANONICAL_COLLECTION_BYTES) {
                    "Canonical collection is too large"
                }
                payload.write(value)
            }
            append(frame("collection-count/int/v1", values.size.toString().utf8()))
            values.forEach { value ->
                append(frame("collection-element/$elementType/v1", value))
            }
            field(name, "collection/$elementType", payload.toByteArray())
        }

        private fun field(name: String, type: String, payload: ByteArray) {
            requireFieldName(name)
            require(payload.size <= MAX_CANONICAL_FIELD_PAYLOAD_BYTES) {
                "Canonical field payload is too large"
            }
            require(++fieldCount <= MAX_CANONICAL_FIELDS_PER_RECORD) {
                "Canonical record has too many fields"
            }
            val encoded = frame("field/$name/$type/v1", payload)
            require(fields.size() + encoded.size <= MAX_CANONICAL_RECORD_BYTES) {
                "Canonical record is too large"
            }
            fields.write(encoded)
        }

        private fun requireFieldName(name: String) {
            require(name.isNotEmpty()) { "Canonical field name must not be empty" }
            requireBoundedUtf8("Canonical field name", name, MAX_CANONICAL_TAG_UTF8_BYTES)
        }

        private fun requireCollectionCount(size: Int) {
            require(size <= MAX_CANONICAL_COLLECTION_ELEMENTS) {
                "Canonical collection has too many elements"
            }
        }
    }
}

private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

/**
 * Returns the UTF-8 byte length when it fits [maxBytes], or null immediately on overflow. Malformed
 * UTF-16 code units count as Java's one-byte UTF-8 replacement (`?`).
 */
internal fun utf8LengthAtMost(value: CharSequence, maxBytes: Int): Int? {
    require(maxBytes >= 0) { "UTF-8 byte limit must not be negative" }
    var byteCount = 0
    var index = 0
    val length = value.length
    while (index < length) {
        if (byteCount == maxBytes) return null
        val current = value[index]
        val width =
            when {
                current.code < 0x80 -> 1
                current.code < 0x800 -> 2
                current.isHighSurrogate() &&
                    index + 1 < length &&
                    value[index + 1].isLowSurrogate() -> 4
                current.isSurrogate() -> 1
                else -> 3
            }
        if (width > maxBytes - byteCount) return null
        byteCount += width
        index += if (width == 4) 2 else 1
    }
    return byteCount
}

internal fun requireBoundedUtf8(name: String, value: CharSequence, maxBytes: Int) {
    require(utf8LengthAtMost(value, maxBytes) != null) {
        "$name exceeds UTF-8 byte limit $maxBytes"
    }
}

private fun encodeBoundedUtf8(name: String, value: String, maxBytes: Int): ByteArray {
    requireBoundedUtf8(name, value, maxBytes)
    return value.toByteArray(StandardCharsets.UTF_8)
}

private fun ByteArrayOutputStream.writeAscii(value: String) {
    write(value.toByteArray(StandardCharsets.US_ASCII))
}

private fun compareBytes(left: ByteArray, right: ByteArray): Int {
    val common = minOf(left.size, right.size)
    for (index in 0 until common) {
        val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}
