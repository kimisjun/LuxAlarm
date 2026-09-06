/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import com.dsalmun.luxalarm.data.IAlarmRepository
import com.dsalmun.luxalarm.data.RoomSleepPlanStore
import com.dsalmun.luxalarm.data.RoomWakePlaylistStore
import com.dsalmun.luxalarm.data.WarmlyDatabase
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AppContainer : Application() {
    companion object {
        /** Legacy runtime seam retained only while reusable alarm components are redesigned. */
        lateinit var repository: IAlarmRepository
        lateinit var settingsManager: SettingsManager
        lateinit var sleepPlanStore: SleepPlanStore
        lateinit var wakePlaylistStore: WakePlaylistStore
        lateinit var wakeAudioStore: WakeAudioStore

        /** Backs the work the disabled legacy broadcast receivers use in focused tests. */
        @VisibleForTesting var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

        @VisibleForTesting internal var startupReconciliationJob: Job? = null
        private val wakeAudioTransactions = WakeAudioTransactionCoordinator()

        private suspend fun reconcileWakeAudioUnlocked(): WakeAudioStore.ReconciliationReport {
            val referencedIds =
                wakePlaylistStore.listLibraryTracks().mapNotNullTo(mutableSetOf()) {
                    wakeAudioStore.ownedTrackId(it.storedPath)
                }
            settingsManager
                .getWakeProfile()
                .importedAudioPath
                ?.let(wakeAudioStore::ownedTrackId)
                ?.let(referencedIds::add)
            return wakeAudioStore.reconcile(referencedIds)
        }

        suspend fun reconcileWakeAudio(): WakeAudioStore.ReconciliationReport =
            wakeAudioTransactions.withTransaction {
                reconcileWakeAudioUnlocked()
            }

        /** Joins startup before locking, so startup reconciliation can never deadlock an import. */
        suspend fun <T> withWakeAudioImportTransaction(block: suspend () -> T): T {
            startupReconciliationJob?.join()
            return wakeAudioTransactions.withTransaction {
                reconcileWakeAudioUnlocked()
                block()
            }
        }
    }

    private var applicationScope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        val database = WarmlyDatabase.getDatabase(this)
        sleepPlanStore = RoomSleepPlanStore(database.sleepPlanDao())
        wakePlaylistStore = RoomWakePlaylistStore(database)
        wakeAudioStore =
            WakeAudioStore(File(filesDir, "gentle-wake-audio")) { documentUri ->
                contentResolver.openInputStream(documentUri.toUri())
            }
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        applicationScope = scope
        startupReconciliationJob = scope.launch {
            runCatching { reconcileWakeAudio() }
                .onFailure { Log.e("AppContainer", "Wake audio reconciliation failed", it) }
        }
    }

    override fun onTerminate() {
        applicationScope?.cancel()
        applicationScope = null
        startupReconciliationJob = null
        super.onTerminate()
    }
}
