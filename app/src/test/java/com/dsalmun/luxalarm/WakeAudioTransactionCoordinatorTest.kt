/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

class WakeAudioTransactionCoordinatorTest {
    @Test
    fun reconciliationCannotEnterWhileImportTransactionIsPending() = runTest {
        val coordinator = WakeAudioTransactionCoordinator()
        val importEntered = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val import = async {
            coordinator.withTransaction {
                events += "import-enter"
                importEntered.complete(Unit)
                releaseImport.await()
                events += "import-exit"
            }
        }
        importEntered.await()

        val reconciliation = async {
            coordinator.withTransaction { events += "reconcile-enter" }
        }
        yield()

        assertEquals(listOf("import-enter"), events)
        releaseImport.complete(Unit)
        import.await()
        reconciliation.await()
        assertEquals(listOf("import-enter", "import-exit", "reconcile-enter"), events)
    }

    @Test
    fun importCannotEnterWhileReferenceSnapshotAndReconciliationAreActive() = runTest {
        val coordinator = WakeAudioTransactionCoordinator()
        val snapshotEntered = CompletableDeferred<Unit>()
        val releaseReconciliation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val reconciliation = async {
            coordinator.withTransaction {
                events += "snapshot"
                snapshotEntered.complete(Unit)
                releaseReconciliation.await()
                events += "reconcile"
            }
        }
        snapshotEntered.await()

        val import = async {
            coordinator.withTransaction { events += "import" }
        }
        yield()

        assertEquals(listOf("snapshot"), events)
        releaseReconciliation.complete(Unit)
        reconciliation.await()
        import.await()
        assertEquals(listOf("snapshot", "reconcile", "import"), events)
    }
}
