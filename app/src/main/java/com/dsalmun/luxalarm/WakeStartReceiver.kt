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
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorization
import com.dsalmun.luxalarm.wake.WakeDispatchAuthorizationFactory
import com.dsalmun.luxalarm.wake.WakeDispatchSource
import com.dsalmun.luxalarm.wake.WakeDispatchSourceKind
import com.dsalmun.luxalarm.wake.WakeEventKind
import com.dsalmun.luxalarm.wake.WakePendingIntentData
import com.dsalmun.luxalarm.wake.WakeRecoveryAnchorKind
import com.dsalmun.luxalarm.wake.WakeRecoverySlotId
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
) {
    private val dispatchStore = RoomWakeEventDispatchStore(database)
    private val receiptStore = RoomWakeRecoveryAnchorReceiptStore(database)
    private val processingStore = RoomWakeRecoveryAnchorProcessingStore(database)

    fun routeStart(parsed: ParsedWakePendingIntentData): WakeReceiverRouteResult {
        if (parsed.event.kind != WakeEventKind.START) return WakeReceiverRouteResult(null)
        val now = clock().takeIf { it >= 0L } ?: return WakeReceiverRouteResult(null)
        val authorization =
            parsed.match(
                onPrimary = { event, identity ->
                    dispatchStore
                        .reduce(
                            event,
                            WakeDispatchAuthorizationFactory.canonicalSource(
                                event,
                                WakeDispatchSourceKind.START_PRIMARY,
                                identity,
                                now,
                            ),
                            now,
                            MAX_HEARTBEAT_AGE_MILLIS,
                        )
                        .authorization
                },
                onDynamic = { event, arrival ->
                    dispatchStore
                        .reduce(
                            event,
                            dynamicSource(event, arrival, now),
                            now,
                            MAX_HEARTBEAT_AGE_MILLIS,
                        )
                        .authorization
                },
                onAnchor = { null },
            )
        return WakeReceiverRouteResult(authorization)
    }

    fun routeGoal(parsed: ParsedWakePendingIntentData): WakeReceiverRouteResult {
        if (parsed.event.kind != WakeEventKind.GOAL) return WakeReceiverRouteResult(null)
        val now = clock().takeIf { it >= 0L } ?: return WakeReceiverRouteResult(null)
        val authorization =
            parsed.match(
                onPrimary = { _, _ -> null },
                onDynamic = { event, arrival ->
                    dispatchStore
                        .reduce(
                            event,
                            dynamicSource(event, arrival, now),
                            now,
                            MAX_HEARTBEAT_AGE_MILLIS,
                        )
                        .authorization
                },
                onAnchor = { verified -> processAnchor(verified, now) },
            )
        return WakeReceiverRouteResult(authorization)
    }

    private fun processAnchor(
        verified: VerifiedWakeRecoveryAnchorArrival,
        now: Long,
    ): WakeDispatchAuthorization? {
        val delivery = verified.deliveryAt(now)
        val receipt = receiptStore.claim(delivery)
        if (
            receipt.outcome != WakeRecoveryAnchorReceiptStoreOutcome.APPLIED &&
                receipt.outcome != WakeRecoveryAnchorReceiptStoreOutcome.RESUME_PROCESSING
        )
            return null
        if (verified.kind == WakeRecoveryAnchorKind.GOAL_PLUS_30M) {
            processingStore.processDeadline(delivery)
            return null
        }
        val sourceKind =
            when (verified.kind) {
                WakeRecoveryAnchorKind.GOAL_PRIMARY -> WakeDispatchSourceKind.GOAL_PRIMARY
                WakeRecoveryAnchorKind.GOAL_PLUS_1M -> WakeDispatchSourceKind.GOAL_PLUS_1M
                WakeRecoveryAnchorKind.GOAL_PLUS_5M -> WakeDispatchSourceKind.GOAL_PLUS_5M
                WakeRecoveryAnchorKind.GOAL_PLUS_15M -> WakeDispatchSourceKind.GOAL_PLUS_15M
                WakeRecoveryAnchorKind.GOAL_PLUS_30M -> return null
            }
        val source =
            WakeDispatchAuthorizationFactory.canonicalSource(
                verified.event,
                sourceKind,
                verified.pendingIntentIdentity,
                now,
            )
        return processingStore
            .processFired(
                delivery,
                source,
                maxHeartbeatAgeMillis = MAX_HEARTBEAT_AGE_MILLIS,
            )
            .authorization
    }

    private fun dynamicSource(
        event: com.dsalmun.luxalarm.wake.WakeEventIdentity,
        arrival: WakeEventArrival,
        now: Long,
    ): WakeDispatchSource =
        arrival.match(
            onPrimary = { error("Dynamic callback carried primary") },
            onRecovery = { deliveredEvent, slot, token, trigger ->
                require(deliveredEvent == event)
                val kind =
                    when (event.kind to slot) {
                        WakeEventKind.START to WakeRecoverySlotId.A ->
                            WakeDispatchSourceKind.START_DYNAMIC_A
                        WakeEventKind.START to WakeRecoverySlotId.B ->
                            WakeDispatchSourceKind.START_DYNAMIC_B
                        WakeEventKind.GOAL to WakeRecoverySlotId.A ->
                            WakeDispatchSourceKind.GOAL_DYNAMIC_A
                        WakeEventKind.GOAL to WakeRecoverySlotId.B ->
                            WakeDispatchSourceKind.GOAL_DYNAMIC_B
                        else -> error("Unknown dynamic source")
                    }
                WakeDispatchAuthorizationFactory.canonicalSource(
                    event,
                    kind,
                    WakePendingIntentData.dynamic(event, slot, token, trigger),
                    now,
                )
            },
        )

    private companion object {
        const val MAX_HEARTBEAT_AGE_MILLIS = 120_000L
    }
}

internal data class WakeReceiverRouteResult(val authorization: WakeDispatchAuthorization?)

internal fun coordinator(context: Context): WakeReceiverRoutingCoordinator =
    WakeReceiverRoutingCoordinator(
        AlarmDatabase.getDatabase(context.applicationContext),
        clock = System::currentTimeMillis,
    )

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
