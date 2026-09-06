/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import kotlinx.coroutines.CancellationException

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

sealed interface WakePlaylistFindResult {
    val documentUri: String

    data class Restored(
        override val documentUri: String,
        val ownedTrack: WakeAudioStore.OwnedTrack,
        val entry: WakePlaylistEntry,
        val duplicateContent: Boolean,
    ) : WakePlaylistFindResult

    data class ContentMismatch(
        override val documentUri: String,
        val expectedTrackId: String,
        val actualTrackId: String,
    ) : WakePlaylistFindResult

    data class Unsupported(override val documentUri: String, val mimeType: String?) :
        WakePlaylistFindResult

    data class Failed(override val documentUri: String, val cause: Throwable) :
        WakePlaylistFindResult
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
    private val findTransaction:
        suspend (suspend () -> WakePlaylistFindResult) -> WakePlaylistFindResult =
        { operation ->
            operation()
        },
    private val fallbackTitle: String = "Imported audio",
    private val titleFor: (String) -> String?,
) {
    suspend fun findMissingTrack(
        playlistId: String,
        expectedTrackId: String,
        documentUri: String,
    ): WakePlaylistFindResult = findTransaction {
        try {
            beforeImport()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            return@findTransaction WakePlaylistFindResult.Failed(documentUri, cause)
        }
        if (playlistStore.listPlaylists().none { it.id == playlistId }) {
            return@findTransaction WakePlaylistFindResult.Failed(
                documentUri,
                IllegalArgumentException("Playlist does not exist: $playlistId"),
            )
        }
        when (val preparation = localTrackImporter.prepareDocument(documentUri)) {
            is LocalTrackPreparation.Ready ->
                validateAndRegisterFoundTrack(
                    playlistId,
                    expectedTrackId,
                    preparation.documentUri,
                    preparation.pending,
                )
            is LocalTrackPreparation.Unsupported ->
                WakePlaylistFindResult.Unsupported(preparation.documentUri, preparation.mimeType)
            is LocalTrackPreparation.Failed ->
                WakePlaylistFindResult.Failed(preparation.documentUri, preparation.cause)
        }
    }

    private suspend fun validateAndRegisterFoundTrack(
        playlistId: String,
        expectedTrackId: String,
        documentUri: String,
        pending: WakeAudioStore.PreparedImport,
    ): WakePlaylistFindResult {
        val stored = pending.result
        val ownedTrack = stored.track
        if (ownedTrack.id != expectedTrackId) {
            pending.rollback(publishedBytesAreReferenced = false)
            return WakePlaylistFindResult.ContentMismatch(
                documentUri,
                expectedTrackId,
                ownedTrack.id,
            )
        }
        return try {
            val track = WakeTrack(ownedTrack.id, safeTitleFor(documentUri), ownedTrack.path)
            val registration = playlistStore.registerTrackInPlaylist(playlistId, track)
            pending.commit()
            WakePlaylistFindResult.Restored(
                documentUri,
                ownedTrack,
                registration.entry,
                stored is WakeAudioStore.ImportResult.Duplicate,
            )
        } catch (cause: CancellationException) {
            cleanupPreparedAfterFailure(pending, ownedTrack, cause)
            throw cause
        } catch (cause: Exception) {
            cleanupPreparedAfterFailure(pending, ownedTrack, cause)
            WakePlaylistFindResult.Failed(documentUri, cause)
        }
    }

    private suspend fun cleanupPreparedAfterFailure(
        pending: WakeAudioStore.PreparedImport,
        ownedTrack: WakeAudioStore.OwnedTrack,
        cause: Throwable,
    ) {
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
    }

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
