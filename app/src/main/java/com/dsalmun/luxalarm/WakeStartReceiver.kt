/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dsalmun.luxalarm.data.AlarmDatabase
import com.dsalmun.luxalarm.data.RoomWakeEventDispatchStore
import com.dsalmun.luxalarm.data.RoomWakeRecoveryAnchorProcessingStore
import com.dsalmun.luxalarm.data.RoomWakeRecoveryAnchorReceiptStore
import com.dsalmun.luxalarm.data.WakeEventArrival
import com.dsalmun.luxalarm.data.WakeRecoveryAnchorReceiptStoreOutcome
import com.dsalmun.luxalarm.wake.ParsedWakePendingIntentData
import com.dsalmun.luxalarm.wake.VerifiedWakeRecoveryAnchorArrival
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.requireCanonicalPendingIntentIdentity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Dormant START receiver: validates canonical data and requests durable Room work only. */
class WakeStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parsed = intent.dataString?.let(WakePendingIntentData::parse) ?: return
        if (parsed.event.kind != WakeEventKind.START) return
        val runtime = WakeReceiverRuntime.capture()
        executeAsync(runtime.executor) { runtime.coordinator(context).routeStart(parsed) }
    }
}

/**
 * Shared receiver routing with explicit time/owner seams; it never starts an FGS or schedules
 * alarms.
 */
internal class WakeReceiverRoutingCoordinator(
    database: AlarmDatabase,
    private val clock: () -> Long,
    private val ownerFactory: (VerifiedWakeRecoveryAnchorArrival) -> String,
) {
    private val dispatchStore = RoomWakeEventDispatchStore(database)
    private val receiptStore = RoomWakeRecoveryAnchorReceiptStore(database)
    private val processingStore = RoomWakeRecoveryAnchorProcessingStore(database)

    fun routeStart(parsed: ParsedWakePendingIntentData) {
        if (parsed.event.kind != WakeEventKind.START) return
        val now = clock().takeIf { it >= 0L } ?: return
        parsed.match(
            onPrimary = { event, _ ->
                dispatchStore.reduce(event, WakeEventArrival.Primary, now, MAX_HEARTBEAT_AGE_MILLIS)
            },
            onDynamic = { event, arrival ->
                dispatchStore.reduce(event, arrival, now, MAX_HEARTBEAT_AGE_MILLIS)
            },
            onAnchor = { Unit },
        )
    }

    fun routeGoal(parsed: ParsedWakePendingIntentData) {
        if (parsed.event.kind != WakeEventKind.GOAL) return
        val now = clock().takeIf { it >= 0L } ?: return
        parsed.match(
            onPrimary = { _, _ -> Unit },
            onDynamic = { event, arrival ->
                dispatchStore.reduce(event, arrival, now, MAX_HEARTBEAT_AGE_MILLIS)
            },
            onAnchor = { verified -> processAnchor(verified, now) },
        )
    }

    private fun processAnchor(verified: VerifiedWakeRecoveryAnchorArrival, now: Long) {
        val delivery = verified.deliveryAt(now)
        val receipt = receiptStore.claim(delivery)
        if (
            receipt.outcome != WakeRecoveryAnchorReceiptStoreOutcome.APPLIED &&
                receipt.outcome != WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING
        ) {
            return
        }
        if (verified.kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M) {
            processingStore.processDeadline(delivery)
            return
        }
        if (now > Long.MAX_VALUE - DISPATCH_LEASE_MILLIS) return
        val owner = ownerFactory(verified)
        processingStore.processFired(
            delivery,
            proposedDispatchLeaseOwner = owner,
            proposedDispatchLeaseExpiresAtEpochMillis = now + DISPATCH_LEASE_MILLIS,
            maxHeartbeatAgeMillis = MAX_HEARTBEAT_AGE_MILLIS,
        )
    }

    private companion object {
        const val DISPATCH_LEASE_MILLIS = 60_000L
        const val MAX_HEARTBEAT_AGE_MILLIS = 120_000L
    }
}

internal fun coordinator(context: Context): WakeReceiverRoutingCoordinator =
    WakeReceiverRoutingCoordinator(
        AlarmDatabase.getDatabase(context.applicationContext),
        clock = System::currentTimeMillis,
        ownerFactory = { verified -> wakeRecoveryLeaseOwner(verified.pendingIntentIdentity) },
    )

internal fun wakeRecoveryLeaseOwner(pendingIntentIdentity: String): String {
    requireCanonicalPendingIntentIdentity(pendingIntentIdentity)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("wake-receiver-v1\u0000".toByteArray(StandardCharsets.US_ASCII))
    val bytes = digest.digest(pendingIntentIdentity.toByteArray(StandardCharsets.US_ASCII))
    val hex = "0123456789abcdef"
    return buildString(81) {
        append("wake-receiver-v1-")
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(hex[unsigned ushr 4])
            append(hex[unsigned and 0x0f])
        }
    }
}

internal fun BroadcastReceiver.executeAsync(executor: Executor, work: () -> Unit) {
    val pending = goAsync()
    executeReceiverWork(executor, work, pending::finish)
}

/** Auditable completion gate: every accepted or rejected async execution finishes exactly once. */
internal fun executeReceiverWork(
    executor: Executor,
    work: () -> Unit,
    finish: () -> Unit,
) {
    val finished = AtomicBoolean(false)
    val finishOnce = {
        if (finished.compareAndSet(false, true)) runCatching(finish)
        Unit
    }
    try {
        executor.execute {
            try {
                runCatching(work)
            } finally {
                finishOnce()
            }
        }
    } catch (_: Throwable) {
        finishOnce()
    }
}

private val WAKE_RECEIVER_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "gentlewake-receiver").apply { isDaemon = true }
}

/** Test seam around execution and Room acquisition; production defaults remain fail-closed. */
internal data class WakeReceiverRuntimeConfig(
    val executor: Executor,
    val coordinatorFactory: (Context) -> WakeReceiverRoutingCoordinator,
) {
    fun coordinator(context: Context): WakeReceiverRoutingCoordinator = coordinatorFactory(context)
}

internal object WakeReceiverRuntime {
    private val defaultConfig = WakeReceiverRuntimeConfig(WAKE_RECEIVER_EXECUTOR, ::coordinator)
    @Volatile private var currentConfig = defaultConfig

    fun capture(): WakeReceiverRuntimeConfig = currentConfig

    fun installForTest(
        executor: Executor,
        coordinatorFactory: (Context) -> WakeReceiverRoutingCoordinator,
    ) {
        currentConfig = WakeReceiverRuntimeConfig(executor, coordinatorFactory)
    }

    fun resetForTest() {
        currentConfig = defaultConfig
    }
}
