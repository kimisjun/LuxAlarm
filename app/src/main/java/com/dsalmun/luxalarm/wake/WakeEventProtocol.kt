/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.nio.charset.StandardCharsets

internal const val MAX_WAKE_SNAPSHOT_ID_UTF8_BYTES = 192
internal const val MAX_WAKE_OWNER_TOKEN_UTF8_BYTES = 256
internal const val MAX_WAKE_EVENT_KEY_ASCII_CHARS = 512
private val LOWERCASE_HEX_CHARS = "0123456789abcdef".toCharArray()

internal enum class WakeEventKind {
    START,
    GOAL,
}

/** Immutable identity for one scheduled wake event. */
internal data class WakeEventIdentity(
    val snapshotId: String,
    val kind: WakeEventKind,
    val expectedTriggerEpochMillis: Long,
) {
    private val snapshotUtf8ByteCount: Int

    init {
        require(snapshotId.isNotBlank()) { "Snapshot id must not be blank" }
        snapshotUtf8ByteCount =
            requireBoundedUtf8(
                label = "Snapshot id",
                value = snapshotId,
                maxBytes = MAX_WAKE_SNAPSHOT_ID_UTF8_BYTES,
            )
        require(expectedTriggerEpochMillis >= 0L) { "Expected trigger epoch must not be negative" }
    }

    fun canonicalKey(): String {
        val snapshotBytes = snapshotId.toByteArray(StandardCharsets.UTF_8)
        check(snapshotBytes.size == snapshotUtf8ByteCount) { "Validated UTF-8 byte count changed" }
        val encodedSnapshot = StringBuilder(snapshotBytes.size * 2)
        snapshotBytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            encodedSnapshot.append(LOWERCASE_HEX_CHARS[value ushr 4])
            encodedSnapshot.append(LOWERCASE_HEX_CHARS[value and 0x0f])
        }
        val key =
            "wake-event-v1:$snapshotUtf8ByteCount:$encodedSnapshot:${kind.name}:" +
                expectedTriggerEpochMillis
        check(key.length <= MAX_WAKE_EVENT_KEY_ASCII_CHARS) { "Wake event key exceeds bound" }
        return key
    }
}

internal enum class WakeDispatchState {
    RECEIVED,
    DEFERRED,
    DISPATCH_REQUESTED,
    SERVICE_ACKED,
    TERMINAL,
}

internal enum class WakeScheduleOwner {
    LEGACY,
    PREPARING_WAKE,
    WAKE,
    RESTORING,
}

internal enum class WakeRecoverySlotState {
    ARMED,
    FIRED,
    IN_FLIGHT,
    CONSUMED,
    CANCELLED,
}

internal enum class WakeRecoverySlotId {
    A,
    B,
}

internal data class WakeRecoverySlot(
    val state: WakeRecoverySlotState,
    val triggerAtEpochMillis: Long?,
    val token: Long,
) {
    init {
        require(triggerAtEpochMillis == null || triggerAtEpochMillis >= 0L) {
            "Recovery trigger epoch must not be negative"
        }
        require(token >= 0L) { "Recovery token must not be negative" }
    }

    fun isUsableFutureRecovery(nowEpochMillis: Long): Boolean {
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        return state == WakeRecoverySlotState.ARMED &&
            triggerAtEpochMillis != null &&
            triggerAtEpochMillis > nowEpochMillis
    }
}

internal data class WakeDispatchInput(
    val event: WakeEventIdentity,
    val state: WakeDispatchState,
    val scheduleOwner: WakeScheduleOwner,
    val dispatchAttemptId: Long,
    val dispatchLeaseOwner: String?,
    val dispatchLeaseExpiresAt: Long?,
    val executionOwner: String?,
    val executionEpoch: Long,
    val serviceLeaseOwner: String?,
    val serviceLeaseExpiresAt: Long?,
    val heartbeatAt: Long?,
    val arrivingSlot: WakeRecoverySlotId?,
    val slotA: WakeRecoverySlot,
    val slotB: WakeRecoverySlot,
    val nowEpochMillis: Long,
    val maxHeartbeatAgeMillis: Long,
) {
    init {
        require(dispatchAttemptId >= 0L) { "Dispatch attempt id must not be negative" }
        require(executionEpoch >= 0L) { "Execution epoch must not be negative" }
        require(nowEpochMillis >= 0L) { "Current epoch must not be negative" }
        require(maxHeartbeatAgeMillis > 0L) { "Heartbeat age bound must be positive" }
        listOf(dispatchLeaseExpiresAt, serviceLeaseExpiresAt, heartbeatAt).forEach { epoch ->
            require(epoch == null || epoch >= 0L) {
                "Lease and heartbeat epochs must not be negative"
            }
        }
        listOf(dispatchLeaseOwner, executionOwner, serviceLeaseOwner).forEach { owner ->
            require(owner == null || owner.isValidOwnerToken()) { "Owner token is invalid" }
        }
    }
}

internal enum class WakeDispatchAction {
    NO_OP_TERMINAL,
    DEFER,
    REQUEST_DISPATCH,
    NO_OP_ACTIVE_DISPATCH,
    NO_OP_HEALTHY_ACK,
    FAIL_CLOSED,
}

internal data class WakeDispatchTransition(
    val expectedEventKey: String,
    val expectedState: WakeDispatchState,
    val expectedDispatchAttemptId: Long,
    val nextState: WakeDispatchState,
    val nextDispatchAttemptId: Long,
    val needsRecovery: Boolean,
    val expectedRecoverySlot: WakeRecoverySlotId? = null,
    val expectedRecoverySlotState: WakeRecoverySlotState? = null,
    val expectedRecoveryTriggerAtEpochMillis: Long? = null,
    val expectedRecoverySlotToken: Long? = null,
    val nextRecoverySlotState: WakeRecoverySlotState? = null,
    val nextRecoveryTriggerAtEpochMillis: Long? = null,
    val nextRecoverySlotToken: Long? = null,
)

internal data class WakeDispatchReduction(
    val action: WakeDispatchAction,
    val transition: WakeDispatchTransition?,
)

/** Pure reducer. Persistence applies [WakeDispatchTransition] with compare-and-set semantics. */
internal object WakeDispatchReducer {
    fun reduce(input: WakeDispatchInput): WakeDispatchReduction {
        if (input.state == WakeDispatchState.TERMINAL) {
            return WakeDispatchReduction(WakeDispatchAction.NO_OP_TERMINAL, null)
        }
        if (!input.hasValidArrival()) {
            return WakeDispatchReduction(WakeDispatchAction.FAIL_CLOSED, null)
        }
        if (input.scheduleOwner == WakeScheduleOwner.PREPARING_WAKE) {
            return WakeDispatchReduction(
                action = WakeDispatchAction.DEFER,
                transition =
                    WakeDispatchTransition(
                        expectedEventKey = input.event.canonicalKey(),
                        expectedState = input.state,
                        expectedDispatchAttemptId = input.dispatchAttemptId,
                        nextState = WakeDispatchState.DEFERRED,
                        nextDispatchAttemptId = input.dispatchAttemptId,
                        needsRecovery = false,
                        expectedRecoverySlot = input.arrivingSlot,
                        expectedRecoverySlotState = input.arrivingRecoverySlot()?.state,
                        expectedRecoveryTriggerAtEpochMillis =
                            input.arrivingRecoverySlot()?.triggerAtEpochMillis,
                        expectedRecoverySlotToken = input.arrivingSlotToken(),
                        nextRecoverySlotState =
                            input.arrivingSlot?.let { WakeRecoverySlotState.CONSUMED },
                        nextRecoveryTriggerAtEpochMillis = null,
                        nextRecoverySlotToken = input.arrivingSlotToken()?.plus(1L),
                    ),
            )
        }
        if (input.scheduleOwner != WakeScheduleOwner.WAKE) {
            return WakeDispatchReduction(WakeDispatchAction.FAIL_CLOSED, null)
        }
        return when (input.state) {
            WakeDispatchState.RECEIVED,
            WakeDispatchState.DEFERRED -> input.requestDispatch()
            WakeDispatchState.DISPATCH_REQUESTED -> {
                val activeLease =
                    input.dispatchLeaseOwner != null &&
                        input.dispatchLeaseExpiresAt != null &&
                        input.dispatchLeaseExpiresAt > input.nowEpochMillis
                if (activeLease) {
                    WakeDispatchReduction(WakeDispatchAction.NO_OP_ACTIVE_DISPATCH, null)
                } else {
                    input.requestDispatch()
                }
            }
            WakeDispatchState.SERVICE_ACKED ->
                if (input.hasHealthyServiceAck()) {
                    WakeDispatchReduction(WakeDispatchAction.NO_OP_HEALTHY_ACK, null)
                } else {
                    input.requestDispatch()
                }
            WakeDispatchState.TERMINAL -> error("TERMINAL was handled before owner reduction")
        }
    }
}

private fun WakeDispatchInput.hasValidArrival(): Boolean {
    val slot =
        when (arrivingSlot) {
            WakeRecoverySlotId.A -> slotA
            WakeRecoverySlotId.B -> slotB
            null -> return true
        }
    return slot.state in setOf(WakeRecoverySlotState.FIRED, WakeRecoverySlotState.IN_FLIGHT) &&
        slot.triggerAtEpochMillis == event.expectedTriggerEpochMillis &&
        slot.token != Long.MAX_VALUE
}

private fun WakeDispatchInput.arrivingSlotToken(): Long? =
    when (arrivingSlot) {
        WakeRecoverySlotId.A -> slotA.token
        WakeRecoverySlotId.B -> slotB.token
        null -> null
    }

private fun WakeDispatchInput.arrivingRecoverySlot(): WakeRecoverySlot? =
    when (arrivingSlot) {
        WakeRecoverySlotId.A -> slotA
        WakeRecoverySlotId.B -> slotB
        null -> null
    }

private fun WakeDispatchInput.hasHealthyServiceAck(): Boolean {
    val currentOwner = dispatchLeaseOwner ?: return false
    val leaseExpiry = serviceLeaseExpiresAt ?: return false
    val heartbeat = heartbeatAt ?: return false
    return executionEpoch > 0L &&
        executionOwner == currentOwner &&
        serviceLeaseOwner == currentOwner &&
        leaseExpiry > nowEpochMillis &&
        heartbeat <= nowEpochMillis &&
        nowEpochMillis - heartbeat <= maxHeartbeatAgeMillis
}

private fun WakeDispatchInput.requestDispatch(): WakeDispatchReduction {
    val arrivingToken =
        when (arrivingSlot) {
            WakeRecoverySlotId.A -> slotA.token
            WakeRecoverySlotId.B -> slotB.token
            null -> null
        }
    if (dispatchAttemptId == Long.MAX_VALUE || arrivingToken == Long.MAX_VALUE) {
        return WakeDispatchReduction(WakeDispatchAction.FAIL_CLOSED, null)
    }
    return WakeDispatchReduction(
        action = WakeDispatchAction.REQUEST_DISPATCH,
        transition =
            WakeDispatchTransition(
                expectedEventKey = event.canonicalKey(),
                expectedState = state,
                expectedDispatchAttemptId = dispatchAttemptId,
                nextState = WakeDispatchState.DISPATCH_REQUESTED,
                nextDispatchAttemptId = dispatchAttemptId + 1L,
                needsRecovery = !hasUsableOppositeRecoverySlot(),
                expectedRecoverySlot = arrivingSlot,
                expectedRecoverySlotState = arrivingRecoverySlot()?.state,
                expectedRecoveryTriggerAtEpochMillis = arrivingRecoverySlot()?.triggerAtEpochMillis,
                expectedRecoverySlotToken = arrivingToken,
                nextRecoverySlotState = arrivingSlot?.let { WakeRecoverySlotState.CONSUMED },
                nextRecoveryTriggerAtEpochMillis = null,
                nextRecoverySlotToken = arrivingToken?.plus(1L),
            ),
    )
}

private fun WakeDispatchInput.hasUsableOppositeRecoverySlot(): Boolean =
    when (arrivingSlot) {
        WakeRecoverySlotId.A -> slotB
        WakeRecoverySlotId.B -> slotA
        null -> null
    }?.isUsableFutureRecovery(nowEpochMillis) == true

private fun String.isValidOwnerToken(): Boolean =
    isNotBlank() && boundedUtf8ByteCount(this, MAX_WAKE_OWNER_TOKEN_UTF8_BYTES) != null

private fun requireBoundedUtf8(
    label: String,
    value: String,
    maxBytes: Int,
): Int =
    requireNotNull(boundedUtf8ByteCount(value, maxBytes)) {
        "$label must not contain controls or malformed Unicode and must not exceed $maxBytes UTF-8 bytes"
    }

/** Returns null at the first control, malformed surrogate, or byte-limit violation. */
private fun boundedUtf8ByteCount(
    value: String,
    maxBytes: Int,
): Int? {
    require(maxBytes >= 0) { "UTF-8 byte limit must not be negative" }
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isISOControl()) return null
        val additionalBytes =
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return null
                    index += 2
                    4
                }
                char.isLowSurrogate() -> return null
                char.code <= 0x7f -> {
                    index += 1
                    1
                }
                char.code <= 0x7ff -> {
                    index += 1
                    2
                }
                else -> {
                    index += 1
                    3
                }
            }
        if (byteCount > maxBytes - additionalBytes) return null
        byteCount += additionalBytes
    }
    return byteCount
}
