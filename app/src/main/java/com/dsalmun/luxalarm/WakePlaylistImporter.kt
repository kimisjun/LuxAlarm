/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

sealed interface WakePlaylistImportResult {
    val documentUri: String

    data class Added(
        override val documentUri: String,
        val ownedTrack: WakeAudioStore.OwnedTrack,
        val entry: WakePlaylistEntry,
        val duplicateContent: Boolean,
    ) : WakePlaylistImportResult

    data class AlreadyInPlaylist(
        override val documentUri: String,
        val ownedTrack: WakeAudioStore.OwnedTrack,
        val entry: WakePlaylistEntry,
        val duplicateContent: Boolean,
    ) : WakePlaylistImportResult

    data class Unsupported(
        override val documentUri: String,
        val mimeType: String?,
    ) : WakePlaylistImportResult

    data class Failed(
        override val documentUri: String,
        val cause: Throwable,
    ) : WakePlaylistImportResult
}

class WakePlaylistImporter(
    private val localTrackImporter: LocalTrackImporter,
    private val playlistStore: WakePlaylistStore,
    private val titleFor: (String) -> String?,
) {
    suspend fun importIntoPlaylist(
        playlistId: String,
        documentUris: List<String>,
    ): List<WakePlaylistImportResult> =
        localTrackImporter.importDocuments(documentUris).map { result ->
            when (result) {
                is LocalTrackImportResult.Added ->
                    registerImported(
                        playlistId = playlistId,
                        documentUri = result.documentUri,
                        ownedTrack = result.track,
                        duplicateContent = false,
                    )
                is LocalTrackImportResult.Duplicate ->
                    registerImported(
                        playlistId = playlistId,
                        documentUri = result.documentUri,
                        ownedTrack = result.track,
                        duplicateContent = true,
                    )
                is LocalTrackImportResult.Unsupported ->
                    WakePlaylistImportResult.Unsupported(result.documentUri, result.mimeType)
                is LocalTrackImportResult.Failed ->
                    WakePlaylistImportResult.Failed(result.documentUri, result.cause)
            }
        }

    private suspend fun registerImported(
        playlistId: String,
        documentUri: String,
        ownedTrack: WakeAudioStore.OwnedTrack,
        duplicateContent: Boolean,
    ): WakePlaylistImportResult =
        try {
            val track = WakeTrack(ownedTrack.id, safeTitleFor(documentUri), ownedTrack.path)
            when (val registration = playlistStore.registerTrackInPlaylist(playlistId, track)) {
                is WakePlaylistRegistration.Added ->
                    WakePlaylistImportResult.Added(
                        documentUri = documentUri,
                        ownedTrack = ownedTrack,
                        entry = registration.entry,
                        duplicateContent = duplicateContent,
                    )
                is WakePlaylistRegistration.AlreadyPresent ->
                    WakePlaylistImportResult.AlreadyInPlaylist(
                        documentUri = documentUri,
                        ownedTrack = ownedTrack,
                        entry = registration.entry,
                        duplicateContent = duplicateContent,
                    )
            }
        } catch (cause: Exception) {
            WakePlaylistImportResult.Failed(documentUri, cause)
        }

    private fun safeTitleFor(documentUri: String): String =
        try {
            titleFor(documentUri)?.trim().orEmpty().ifEmpty { FALLBACK_TITLE }
        } catch (_: Exception) {
            FALLBACK_TITLE
        }

    private companion object {
        const val FALLBACK_TITLE = "Imported audio"
    }
}
