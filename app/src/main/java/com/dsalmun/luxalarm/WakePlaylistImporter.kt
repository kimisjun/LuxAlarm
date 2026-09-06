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
    private val beforeImport: suspend () -> Unit = {},
    private val transaction:
        suspend (suspend () -> List<WakePlaylistImportResult>) -> List<WakePlaylistImportResult> =
        { operation ->
            operation()
        },
    private val fallbackTitle: String = "Imported audio",
    private val titleFor: (String) -> String?,
) {
    suspend fun importIntoPlaylist(
        playlistId: String,
        documentUris: List<String>,
    ): List<WakePlaylistImportResult> = transaction {
        try {
            beforeImport()
        } catch (cause: Exception) {
            return@transaction documentUris.map { WakePlaylistImportResult.Failed(it, cause) }
        }
        if (playlistStore.listPlaylists().none { it.id == playlistId }) {
            return@transaction documentUris.map { documentUri ->
                WakePlaylistImportResult.Failed(
                    documentUri,
                    IllegalArgumentException("Playlist does not exist: $playlistId"),
                )
            }
        }
        documentUris.map { documentUri ->
            when (val preparation = localTrackImporter.prepareDocument(documentUri)) {
                is LocalTrackPreparation.Ready ->
                    registerPrepared(playlistId, preparation.documentUri, preparation.pending)
                is LocalTrackPreparation.Unsupported ->
                    WakePlaylistImportResult.Unsupported(
                        preparation.documentUri,
                        preparation.mimeType,
                    )
                is LocalTrackPreparation.Failed ->
                    WakePlaylistImportResult.Failed(preparation.documentUri, preparation.cause)
            }
        }
    }

    private suspend fun registerPrepared(
        playlistId: String,
        documentUri: String,
        pending: WakeAudioStore.PreparedImport,
    ): WakePlaylistImportResult {
        val stored = pending.result
        val ownedTrack = stored.track
        return try {
            val track = WakeTrack(ownedTrack.id, safeTitleFor(documentUri), ownedTrack.path)
            val result =
                when (val registration = playlistStore.registerTrackInPlaylist(playlistId, track)) {
                    is WakePlaylistRegistration.Added ->
                        WakePlaylistImportResult.Added(
                            documentUri = documentUri,
                            ownedTrack = ownedTrack,
                            entry = registration.entry,
                            duplicateContent = stored is WakeAudioStore.ImportResult.Duplicate,
                        )
                    is WakePlaylistRegistration.AlreadyPresent ->
                        WakePlaylistImportResult.AlreadyInPlaylist(
                            documentUri = documentUri,
                            ownedTrack = ownedTrack,
                            entry = registration.entry,
                            duplicateContent = stored is WakeAudioStore.ImportResult.Duplicate,
                        )
                }
            pending.commit()
            result
        } catch (cause: Exception) {
            val referenced = runCatching {
                playlistStore.listLibraryTracks().any { it.id == ownedTrack.id }
            }
            runCatching {
                    referenced.fold(
                        onSuccess = pending::rollback,
                        onFailure = { pending.deferToReconciliation() },
                    )
                }
                .onFailure(cause::addSuppressed)
            WakePlaylistImportResult.Failed(documentUri, cause)
        }
    }

    private fun safeTitleFor(documentUri: String): String =
        try {
            titleFor(documentUri)?.trim().orEmpty().ifEmpty { fallbackTitle }
        } catch (_: Exception) {
            fallbackTitle
        }
}
