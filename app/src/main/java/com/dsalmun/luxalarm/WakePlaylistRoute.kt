/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.launch

/** Connects the stateless playlist UI to durable storage, document import, and preview. */
@Composable
fun WakePlaylistRoute(
    playlistStore: WakePlaylistStore,
    onBack: () -> Unit = {},
    onSelectionChanged: (WakePlaylist?) -> Unit = {},
    importDocuments: (suspend (String, List<String>) -> List<WakePlaylistImportResult>)? = null,
    ownedFileExists: (String) -> Boolean = { File(it).isFile },
    deleteOwnedBytes: ((WakeTrack) -> Boolean)? = null,
    usePlatformNameDialog: Boolean = true,
) {
    val context = LocalContext.current
    val audioStore =
        remember(context) {
            WakeAudioStore(File(context.filesDir, "gentle-wake-audio")) { documentUri ->
                context.contentResolver.openInputStream(documentUri.toUri())
            }
        }
    val productionImporter =
        remember(context, playlistStore, audioStore) {
            WakePlaylistImporter(
                localTrackImporter =
                    LocalTrackImporter(audioStore) { documentUri ->
                        context.contentResolver.getType(Uri.parse(documentUri))
                    },
                playlistStore = playlistStore,
                titleFor = { documentUri -> displayNameFor(context, documentUri) },
            )
        }
    val doImport = importDocuments ?: productionImporter::importIntoPlaylist
    val doDelete =
        deleteOwnedBytes
            ?: { track: WakeTrack ->
                runCatching {
                        audioStore.deleteOwnedBytes(
                            WakeAudioStore.OwnedTrack(track.id, track.id, track.storedPath)
                        )
                    }
                    .getOrDefault(false)
            }

    var state by remember(playlistStore) { mutableStateOf(WakePlaylistScreenState()) }
    var dialog by remember { mutableStateOf<NameDialog?>(null) }
    var dialogName by remember { mutableStateOf("") }
    var replacementTrackId by remember { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var previewProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(editorId: String? = state.editor?.id) {
        val playlists = playlistStore.listPlaylists()
        val selected = playlistStore.selectedPlaylistForWake()
        val editorPlaylist = editorId?.let { id -> playlists.singleOrNull { it.id == id } }
        val editor = editorPlaylist?.let { playlist ->
            WakePlaylistEditorUi(
                id = playlist.id,
                name = playlist.name,
                tracks =
                    playlistStore
                        .listEntries(playlist.id)
                        .sortedBy(WakePlaylistEntry::position)
                        .map { entry ->
                            WakePlaylistTrackUi(
                                id = entry.track.id,
                                title = entry.track.title,
                                isMissing = !ownedFileExists(entry.track.storedPath),
                            )
                        },
            )
        }
        state =
            state.copy(
                playlists = playlists.map { WakePlaylistItemUi(it.id, it.name) },
                selectedForWakeId = selected?.id,
                editor = editor,
            )
        onSelectionChanged(selected)
    }

    suspend fun importSelectedDocuments(documentUris: List<String>) {
        val editor = state.editor ?: return
        if (documentUris.isEmpty()) return
        val results = doImport(editor.id, documentUris)
        val replacement = replacementTrackId
        replacementTrackId = null
        if (replacement != null && results.any { it.isSuccessfulMembership() }) {
            playlistStore.removeTrack(editor.id, replacement)
        }
        state = state.copy(importSummary = results.toImportSummary())
        refresh(editor.id)
    }

    val audioPicker =
        rememberLauncherForActivityResult(WakeAudioDocumentsContract()) { documentUris ->
            scope.launch { importSelectedDocuments(documentUris) }
        }

    LaunchedEffect(playlistStore) { refresh(editorId = null) }

    BackHandler {
        if (showPreview) {
            showPreview = false
        } else if (state.editor != null) {
            state = state.copy(editor = null, importSummary = null)
        } else {
            onBack()
        }
    }

    if (showPreview) {
        GentleWakePreviewRoute(
            progress = previewProgress,
            onProgressChange = { previewProgress = it },
            onAwake = { showPreview = false },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        WakePlaylistScreen(
            state = state,
            onCreatePlaylist = {
                dialogName = ""
                dialog = NameDialog.Create
            },
            onSelectForWake = { playlistId ->
                scope.launch {
                    playlistStore.selectPlaylistForWake(playlistId)
                    refresh(editorId = null)
                }
            },
            onRenamePlaylist = { playlistId ->
                val playlist = state.playlists.single { it.id == playlistId }
                dialogName = playlist.name
                dialog = NameDialog.Rename(playlistId)
            },
            onEditPlaylist = { playlistId -> scope.launch { refresh(playlistId) } },
            onImportTracks = { audioPicker.launch(Unit) },
            onMoveTrackUp = { trackId ->
                val editor = state.editor ?: return@WakePlaylistScreen
                val index = editor.tracks.indexOfFirst { it.id == trackId }
                if (index > 0) {
                    scope.launch {
                        playlistStore.moveTrack(editor.id, trackId, index - 1)
                        refresh(editor.id)
                    }
                }
            },
            onMoveTrackDown = { trackId ->
                val editor = state.editor ?: return@WakePlaylistScreen
                val index = editor.tracks.indexOfFirst { it.id == trackId }
                if (index in 0 until editor.tracks.lastIndex) {
                    scope.launch {
                        playlistStore.moveTrack(editor.id, trackId, index + 1)
                        refresh(editor.id)
                    }
                }
            },
            onRemoveFromPlaylist = { trackId ->
                val editor = state.editor ?: return@WakePlaylistScreen
                scope.launch {
                    playlistStore.removeTrack(editor.id, trackId)
                    refresh(editor.id)
                }
            },
            onDeleteOwnedAudio = { trackId ->
                val editor = state.editor ?: return@WakePlaylistScreen
                scope.launch {
                    val entry =
                        playlistStore.listEntries(editor.id).singleOrNull { it.track.id == trackId }
                    if (entry != null) doDelete(entry.track)
                    refresh(editor.id)
                }
            },
            onFindMissingTrack = { trackId ->
                replacementTrackId = trackId
                audioPicker.launch(Unit)
            },
            onReplaceMissingTrack = { trackId ->
                replacementTrackId = trackId
                audioPicker.launch(Unit)
            },
            onBack = {
                if (state.editor != null) {
                    state = state.copy(editor = null, importSummary = null)
                } else {
                    onBack()
                }
            },
            onPreview = {
                scope.launch {
                    state.editor?.id?.let { playlistStore.selectPlaylistForWake(it) }
                    refresh(state.editor?.id)
                    previewProgress = 0f
                    showPreview = true
                }
            },
        )

        dialog?.let { activeDialog ->
            PlaylistNameDialog(
                title =
                    stringResource(
                        if (activeDialog is NameDialog.Create) {
                            R.string.warmly_playlist_create
                        } else {
                            R.string.warmly_playlist_rename
                        }
                    ),
                value = dialogName,
                confirmLabel =
                    stringResource(
                        if (activeDialog is NameDialog.Create) {
                            R.string.warmly_playlist_create_confirm
                        } else {
                            R.string.warmly_playlist_rename_confirm
                        }
                    ),
                onValueChange = { dialogName = it },
                onDismiss = { dialog = null },
                onConfirm = {
                    val name = dialogName.trim()
                    dialog = null
                    scope.launch {
                        when (activeDialog) {
                            NameDialog.Create -> playlistStore.createPlaylist(name)
                            is NameDialog.Rename ->
                                playlistStore.renamePlaylist(activeDialog.playlistId, name)
                        }
                        refresh()
                    }
                },
                usePlatformDialog = usePlatformNameDialog,
            )
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    usePlatformDialog: Boolean,
) {
    val content: @Composable () -> Unit = {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(stringResource(R.string.warmly_playlist_name)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.warmly_cancel))
                    }
                    Button(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) }
                }
            }
        }
    }
    if (usePlatformDialog) {
        Dialog(onDismissRequest = onDismiss) { content() }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private sealed interface NameDialog {
    data object Create : NameDialog

    data class Rename(val playlistId: String) : NameDialog
}

internal fun List<WakePlaylistImportResult>.toImportSummary() =
    WakePlaylistImportSummaryUi(
        added = count { it is WakePlaylistImportResult.Added },
        duplicates = count { it is WakePlaylistImportResult.AlreadyInPlaylist },
        unsupported = count { it is WakePlaylistImportResult.Unsupported },
        failed = count { it is WakePlaylistImportResult.Failed },
    )

private fun WakePlaylistImportResult.isSuccessfulMembership(): Boolean =
    this is WakePlaylistImportResult.Added || this is WakePlaylistImportResult.AlreadyInPlaylist

private fun displayNameFor(context: android.content.Context, documentUri: String): String? =
    context.contentResolver
        .query(Uri.parse(documentUri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
