/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppContainerWakeAudioTransactionTest {
    private lateinit var playlistStore: BarrierReferenceStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("lux_alarm_settings", 0).edit().clear().commit()
        playlistStore = BarrierReferenceStore()
        AppContainer.settingsManager = SettingsManager(context)
        AppContainer.wakePlaylistStore = playlistStore
        AppContainer.wakeAudioStore =
            WakeAudioStore(File("build/test-audio/${UUID.randomUUID()}")) { null }
        AppContainer.startupReconciliationJob = null
    }

    @After
    fun tearDown() {
        AppContainer.startupReconciliationJob = null
        AppContainer.ioDispatcher = Dispatchers.IO
    }

    @Test
    fun authoritativeReconciliationCannotSnapshotWhileImportIsPending() = runTest {
        val importEntered = CompletableDeferred<Unit>()
        val releaseImport = CompletableDeferred<Unit>()
        val import = async {
            AppContainer.withWakeAudioImportTransaction {
                importEntered.complete(Unit)
                releaseImport.await()
            }
        }
        importEntered.await()

        val reconciliation = async { AppContainer.reconcileWakeAudio() }
        yield()

        assertEquals(1, playlistStore.snapshotCalls)
        releaseImport.complete(Unit)
        import.await()
        reconciliation.await()
        assertEquals(2, playlistStore.snapshotCalls)
    }

    @Test
    fun importCannotReconcileOrEnterWhileAuthoritativeSnapshotIsActive() = runTest {
        playlistStore.blockNextSnapshot = true
        val reconciliation = async { AppContainer.reconcileWakeAudio() }
        playlistStore.snapshotEntered.await()
        var importEntered = false

        val import = async {
            AppContainer.withWakeAudioImportTransaction { importEntered = true }
        }
        yield()

        assertEquals(1, playlistStore.snapshotCalls)
        assertFalse(importEntered)
        playlistStore.releaseSnapshot.complete(Unit)
        reconciliation.await()
        import.await()
        assertEquals(2, playlistStore.snapshotCalls)
        assertTrue(importEntered)
    }
}

private class BarrierReferenceStore : WakePlaylistStore {
    var snapshotCalls = 0
    var blockNextSnapshot = false
    val snapshotEntered = CompletableDeferred<Unit>()
    val releaseSnapshot = CompletableDeferred<Unit>()

    override suspend fun listLibraryTracks(): List<WakeTrack> {
        snapshotCalls += 1
        if (blockNextSnapshot) {
            blockNextSnapshot = false
            snapshotEntered.complete(Unit)
            releaseSnapshot.await()
        }
        return emptyList()
    }

    override suspend fun createPlaylist(name: String) = error("unused")

    override suspend fun listPlaylists(): List<WakePlaylist> = emptyList()

    override suspend fun renamePlaylist(playlistId: String, name: String) = Unit

    override suspend fun selectPlaylistForWake(playlistId: String) = Unit

    override suspend fun selectedPlaylistForWake(): WakePlaylist? = null

    override suspend fun addTrackToLibrary(title: String, storedPath: String) = error("unused")

    override suspend fun registerTrackInPlaylist(playlistId: String, track: WakeTrack) =
        error("unused")

    override suspend fun addTrack(playlistId: String, trackId: String) = error("unused")

    override suspend fun removeTrack(playlistId: String, trackId: String) = Unit

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) = Unit

    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> = emptyList()
}
