/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps every provider and owned-file operation on the injected I/O dispatcher. */
class WakePlaylistDocumentResolver(
    storageDirectory: File,
    playlistStore: WakePlaylistStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    openDocument: (String) -> InputStream?,
    mimeTypeFor: (String) -> String?,
    titleFor: (String) -> String?,
    providedAudioStore: WakeAudioStore? = null,
    beforeImport: suspend () -> Unit = {},
    fallbackTitle: String = "Imported audio",
) {
    private val audioStore = providedAudioStore ?: WakeAudioStore(storageDirectory, openDocument)
    private val importer =
        WakePlaylistImporter(
            localTrackImporter = LocalTrackImporter(audioStore, mimeTypeFor),
            playlistStore = playlistStore,
            beforeImport = beforeImport,
            fallbackTitle = fallbackTitle,
            titleFor = titleFor,
        )

    suspend fun importIntoPlaylist(
        playlistId: String,
        documentUris: List<String>,
    ): List<WakePlaylistImportResult> =
        withContext(ioDispatcher) {
            importer.importIntoPlaylist(playlistId, documentUris).toList()
        }

    suspend fun ownedFileExists(path: String): Boolean =
        withContext(ioDispatcher) { File(path).isFile }

    suspend fun deleteOwnedBytes(track: WakeTrack): Boolean =
        withContext(ioDispatcher) {
            audioStore.deleteOwnedBytes(WakeAudioStore.OwnedTrack(track.id, track.id, track.storedPath))
        }

    companion object {
        fun production(
            context: Context,
            playlistStore: WakePlaylistStore,
            ioDispatcher: CoroutineDispatcher = AppContainer.ioDispatcher,
            fallbackTitle: String = "Imported audio",
        ): WakePlaylistDocumentResolver =
            WakePlaylistDocumentResolver(
                storageDirectory = File(context.filesDir, "gentle-wake-audio"),
                playlistStore = playlistStore,
                ioDispatcher = ioDispatcher,
                openDocument = { documentUri ->
                    context.contentResolver.openInputStream(documentUri.toUri())
                },
                mimeTypeFor = { documentUri ->
                    context.contentResolver.getType(Uri.parse(documentUri))
                },
                titleFor = { documentUri -> displayNameFor(context, documentUri) },
                providedAudioStore = AppContainer.wakeAudioStore,
                beforeImport = AppContainer::reconcileWakeAudioBeforeImport,
                fallbackTitle = fallbackTitle,
            )
    }
}

private fun displayNameFor(context: Context, documentUri: String): String? =
    context.contentResolver
        .query(Uri.parse(documentUri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
