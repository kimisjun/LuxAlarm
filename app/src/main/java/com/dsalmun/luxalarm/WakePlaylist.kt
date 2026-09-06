/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

data class WakePlaylist(val id: String, val name: String)

data class WakeTrack(val id: String, val title: String, val storedPath: String)

data class WakePlaylistEntry(
    val id: String,
    val playlistId: String,
    val track: WakeTrack,
    val position: Int,
)

sealed interface WakePlaylistRegistration {
    val entry: WakePlaylistEntry

    data class Added(override val entry: WakePlaylistEntry) : WakePlaylistRegistration

    data class AlreadyPresent(override val entry: WakePlaylistEntry) : WakePlaylistRegistration
}

interface WakePlaylistStore {
    suspend fun createPlaylist(name: String): WakePlaylist

    suspend fun listPlaylists(): List<WakePlaylist>

    suspend fun renamePlaylist(playlistId: String, name: String)

    suspend fun selectPlaylistForWake(playlistId: String)

    suspend fun selectedPlaylistForWake(): WakePlaylist?

    suspend fun addTrackToLibrary(title: String, storedPath: String): WakeTrack

    suspend fun registerTrackInPlaylist(
        playlistId: String,
        track: WakeTrack,
    ): WakePlaylistRegistration

    suspend fun listLibraryTracks(): List<WakeTrack>

    suspend fun addTrack(playlistId: String, trackId: String): WakePlaylistEntry

    suspend fun removeTrack(playlistId: String, trackId: String)

    suspend fun moveTrack(playlistId: String, trackId: String, position: Int)

    suspend fun listEntries(playlistId: String): List<WakePlaylistEntry>
}
