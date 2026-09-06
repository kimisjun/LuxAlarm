/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WakePlaylistDocumentResolverTest {
    @Test
    fun allDocumentAndOwnedFileOperationsUseInjectedIoDispatcher() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "resolver-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        val threads = mutableListOf<String>()
        val store = ResolverPlaylistStore()
        val root = File("build/test-resolver/${UUID.randomUUID()}")
        val resolver =
            WakePlaylistDocumentResolver(
                storageDirectory = root,
                playlistStore = store,
                ioDispatcher = dispatcher,
                openDocument = {
                    threads += Thread.currentThread().name
                    ByteArrayInputStream("audio".encodeToByteArray())
                },
                mimeTypeFor = {
                    threads += Thread.currentThread().name
                    "audio/mpeg"
                },
                titleFor = {
                    threads += Thread.currentThread().name
                    "Song"
                },
            )

        try {
            runBlocking {
                val imported = resolver.importIntoPlaylist("playlist", listOf("content://song"))
                val track = (imported.single() as WakePlaylistImportResult.Added).ownedTrack
                assertTrue(resolver.ownedFileExists(track.path))
                assertTrue(resolver.deleteOwnedBytes(WakeTrack(track.id, "Song", track.path)))
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }

        assertTrue(threads.isNotEmpty())
        assertTrue(threads.all { it.startsWith("resolver-io") }, threads.toString())
        assertTrue(store.registrationThreads.single().startsWith("resolver-io"))
    }
}

private class ResolverPlaylistStore : WakePlaylistStore {
    val registrationThreads = mutableListOf<String>()
    override suspend fun createPlaylist(name: String) = error("unused")
    override suspend fun listPlaylists(): List<WakePlaylist> = emptyList()
    override suspend fun renamePlaylist(playlistId: String, name: String) = Unit
    override suspend fun selectPlaylistForWake(playlistId: String) = Unit
    override suspend fun selectedPlaylistForWake(): WakePlaylist? = null
    override suspend fun addTrackToLibrary(title: String, storedPath: String) = error("unused")
    override suspend fun registerTrackInPlaylist(playlistId: String, track: WakeTrack): WakePlaylistRegistration {
        registrationThreads += Thread.currentThread().name
        return WakePlaylistRegistration.Added(WakePlaylistEntry("entry", playlistId, track, 0))
    }
    override suspend fun listLibraryTracks(): List<WakeTrack> = emptyList()
    override suspend fun addTrack(playlistId: String, trackId: String) = error("unused")
    override suspend fun removeTrack(playlistId: String, trackId: String) = Unit
    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) = Unit
    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> = emptyList()
}
