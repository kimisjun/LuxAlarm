/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal const val MAX_LEGACY_AUDIO_EVIDENCE_BYTES = 24 * 1024

internal data class LegacyAudioIntentEvidence(
    val schemaVersion: Int,
    val installEpoch: String,
    val sourceFingerprint: String,
    val attemptToken: String,
    val targetStorageKey: String,
    val sourcePathHash: String,
) {
    companion object {
        fun from(descriptor: LegacyAudioBootstrapDescriptor) =
            LegacyAudioIntentEvidence(
                schemaVersion = 1,
                installEpoch = descriptor.installEpoch,
                sourceFingerprint = descriptor.sourceFingerprint,
                attemptToken = descriptor.attemptToken,
                targetStorageKey = descriptor.targetStorageKey,
                sourcePathHash =
                    MessageDigest.getInstance("SHA-256")
                        .digest(descriptor.sourcePath.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) },
            )
    }
}

internal object LegacyAudioIntentEvidenceCodec {
    private const val MAGIC = "GentleWake legacy audio intent"

    fun encode(value: LegacyAudioIntentEvidence): ByteArray {
        val payload =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.text(MAGIC)
                        out.writeInt(value.schemaVersion)
                        out.text(value.installEpoch)
                        out.text(value.sourceFingerprint)
                        out.text(value.attemptToken)
                        out.text(value.targetStorageKey)
                        out.text(value.sourcePathHash)
                    }
                }
                .toByteArray()
        require(payload.size + 32 <= MAX_LEGACY_AUDIO_EVIDENCE_BYTES) {
            "Intent evidence is too large"
        }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(bytes: ByteArray): LegacyAudioIntentEvidence {
        require(bytes.size in 33..MAX_LEGACY_AUDIO_EVIDENCE_BYTES) {
            "Intent evidence size is invalid"
        }
        val payload = bytes.copyOf(bytes.size - 32)
        val checksum = bytes.copyOfRange(bytes.size - 32, bytes.size)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(checksum)) {
            "Intent evidence checksum mismatch"
        }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.text(64) == MAGIC) { "Intent evidence magic mismatch" }
                val value =
                    LegacyAudioIntentEvidence(
                        schemaVersion = input.readInt(),
                        installEpoch = input.text(MAX_INSTALL_EPOCH_UTF8_BYTES),
                        sourceFingerprint = input.text(160),
                        attemptToken = input.text(64),
                        targetStorageKey = input.text(256),
                        sourcePathHash = input.text(64),
                    )
                require(value.schemaVersion == 1) { "Unsupported intent schema" }
                require(value.sourcePathHash.matches(Regex("[0-9a-f]{64}"))) {
                    "Invalid intent source hash"
                }
                require(input.available() == 0) { "Trailing intent evidence bytes" }
                value
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException("Malformed intent evidence", failure)
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(maxBytes: Int): String {
        val size = readInt()
        require(size in 0..maxBytes && size <= available()) { "Intent text length is invalid" }
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = bytes.toString(Charsets.UTF_8)
        require(value.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "Malformed intent UTF-8" }
        return value
    }
}
