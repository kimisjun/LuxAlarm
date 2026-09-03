/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CanonicalEncoderTest {
    @Test
    fun boundedUtf8LengthAccepts4096BytesAndRejectsTheNextByte() {
        assertEquals(4096, utf8LengthAtMost("x".repeat(4096), 4096))
        assertNull(utf8LengthAtMost("x".repeat(4097), 4096))
    }

    @Test
    fun boundedUtf8LengthCountsEveryUtf8WidthAndJavaMalformedReplacement() {
        assertEquals(10, utf8LengthAtMost("Aé한😀", 10))
        assertEquals(1, utf8LengthAtMost("\uD800", 1))
        assertEquals(1, utf8LengthAtMost("\uDC00", 1))
        assertEquals(2, utf8LengthAtMost("\uD800x", 2))
        assertNull(utf8LengthAtMost("😀", 3))
    }

    @Test
    fun boundedUtf8LengthStopsReadingAsSoonAsTheLimitIsExceeded() {
        val guarded =
            object : CharSequence {
                override val length = Int.MAX_VALUE

                override fun get(index: Int): Char =
                    if (index <= 4) 'x' else error("scanner read past the first rejected byte")

                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                    error("scanner must not copy the input")
            }

        assertNull(utf8LengthAtMost(guarded, 4))
    }

    @Test
    fun stringFieldUtf8LimitIsAcceptedAndOneByteMoreIsRejected() {
        val exact = "x".repeat(MAX_CANONICAL_STRING_UTF8_BYTES)
        CanonicalEncoder.record("limit", 1) { string("value", exact) }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CanonicalEncoder.record("limit", 1) { string("value", exact + "x") }
            }
        assertTrue(failure.message.orEmpty().contains("value"))
        assertTrue(failure.message.orEmpty().contains(MAX_CANONICAL_STRING_UTF8_BYTES.toString()))
    }

    @Test
    fun recordFieldCountLimitIsAcceptedAndOneMoreIsRejectedBeforeWriting() {
        CanonicalEncoder.record("limit", 1) {
            repeat(MAX_CANONICAL_FIELDS_PER_RECORD) { int("f$it", it) }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.record("limit", 1) {
                repeat(MAX_CANONICAL_FIELDS_PER_RECORD + 1) { int("f$it", it) }
            }
        }
    }

    @Test
    fun collectionElementLimitIsAcceptedAndOneMoreIsRejected() {
        val exact = List(MAX_CANONICAL_COLLECTION_ELEMENTS) { byteArrayOf(it.toByte()) }
        CanonicalEncoder.record("limit", 1) { records("items", exact) }
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.record("limit", 1) {
                records("items", exact + byteArrayOf(0))
            }
        }
    }

    @Test
    fun recordAndTotalEncodedSizesAreBounded() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.record("limit", 1) {
                repeat(MAX_CANONICAL_FIELDS_PER_RECORD) {
                    encodedRecord("field-$it", ByteArray(MAX_CANONICAL_FIELD_PAYLOAD_BYTES))
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.frame(
                "total-limit",
                ByteArray(MAX_CANONICAL_TOTAL_BYTES + 1),
            )
        }
    }

    @Test
    fun typedLengthFramingRemovesOldAdjacentFieldAndRecordSeparatorAmbiguity() {
        val oldAdjacentLeft = listOf("alpha|beta", "gamma").joinToString("|")
        val oldAdjacentRight = listOf("alpha", "beta|gamma").joinToString("|")
        assertEquals(oldAdjacentLeft, oldAdjacentRight)

        val newAdjacentLeft = twoStrings("alpha|beta", "gamma")
        val newAdjacentRight = twoStrings("alpha", "beta|gamma")
        assertFalse(newAdjacentLeft.contentEquals(newAdjacentRight))

        val oldRecordsLeft = listOf("first\nsecond", "third").joinToString("\n")
        val oldRecordsRight = listOf("first", "second", "third").joinToString("\n")
        assertEquals(oldRecordsLeft, oldRecordsRight)

        val newRecordsLeft = encodedRecords(listOf("first\nsecond", "third"))
        val newRecordsRight = encodedRecords(listOf("first", "second", "third"))
        assertFalse(newRecordsLeft.contentEquals(newRecordsRight))
    }

    @Test
    fun nullHasATypeTagDistinctFromTheOldLiteralNullCollision() {
        val oldNull = null.toString()
        val oldLiteral = "null"
        assertEquals(oldNull, oldLiteral)

        assertFalse(
            twoNullableStrings(null, "tail").contentEquals(twoNullableStrings("null", "tail"))
        )
    }

    @Test
    fun attemptTokenFramesInstallEpochAndFingerprintInsteadOfUsingAmbiguousNewlines() {
        fun oldPayload(installEpoch: String, fingerprint: String) =
            "legacy-bootstrap-v1\n$installEpoch\n$fingerprint"
        assertEquals(oldPayload("a\nb", "c"), oldPayload("a", "b\nc"))

        val left = legacyDiscoveryAttemptTokenPayload("a\nb", "c")
        val right = legacyDiscoveryAttemptTokenPayload("a", "b\nc")
        assertFalse(left.contentEquals(right))
        assertFalse(
            legacyDiscoveryAttemptToken("a\nb", "c") == legacyDiscoveryAttemptToken("a", "b\nc")
        )
    }

    @Test
    fun manifestFieldsCarryTypesThatOldLengthOnlyValuesCouldNotDistinguish() {
        fun oldLengthOnly(value: String) = "${value.toByteArray().size}:$value"
        assertEquals(oldLengthOnly(1L.toString()), oldLengthOnly("1"))

        val payload =
            legacyManifestRowsPayload(
                    listOf(
                        LegacyMigrationManifestEntity(
                            legacyAlarmId = 1L,
                            goalEpochMs = 2L,
                            pendingIntentIdentity = "pending\nidentity",
                            proposedDisposition = LegacyDisposition.SELECT_AS_WAKE.name,
                            userConfirmed = 0,
                            terminalAt = null,
                        )
                    )
                )
                .toString(Charsets.UTF_8)

        assertFalse("field/legacyAlarmId/string" in payload)
        assertEquals(true, "field/legacyAlarmId/long" in payload)
        assertEquals(true, "field/proposedDisposition/enum" in payload)
        assertEquals(true, "field/terminalAt/null" in payload)
        assertEquals(true, "collection-count/int" in payload)
    }

    @Test
    fun floatsNormalizeSignedZeroAndRejectNonfinitePayloads() {
        val positive = CanonicalEncoder.record("float-test", 1) { float("value", 0f) }
        val negative = CanonicalEncoder.record("float-test", 1) { float("value", -0f) }
        assertContentEquals(positive, negative)
        assertFailsWith<IllegalArgumentException> {
            CanonicalEncoder.record("float-test", 1) { float("value", Float.NaN) }
        }
    }

    private fun twoStrings(first: String, second: String): ByteArray =
        CanonicalEncoder.record("two-strings-test", 1) {
            string("first", first)
            string("second", second)
        }

    private fun twoNullableStrings(first: String?, second: String?): ByteArray =
        CanonicalEncoder.record("two-nullable-strings-test", 1) {
            nullableString("first", first)
            nullableString("second", second)
        }

    private fun encodedRecords(values: List<String>): ByteArray =
        CanonicalEncoder.record("record-list-test", 1) {
            records(
                "items",
                values.map { value ->
                    CanonicalEncoder.record("record-list-item-test", 1) { string("value", value) }
                },
            )
        }
}
