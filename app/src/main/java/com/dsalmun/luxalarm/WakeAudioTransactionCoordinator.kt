/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes reference snapshots/reconciliation with complete metadata-backed audio imports. */
class WakeAudioTransactionCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withTransaction(block: suspend () -> T): T = mutex.withLock { block() }
}
