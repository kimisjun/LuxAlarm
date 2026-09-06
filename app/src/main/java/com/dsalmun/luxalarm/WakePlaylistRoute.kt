/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel

/** Connects the stateless playlist UI to lifecycle-safe state and durable operations. */
@Composable
fun WakePlaylistRoute(
    playlistStore: WakePlaylistStore,
    onBack: () -> Unit = {},
    onSelectionChanged: (WakePlaylist?) -> Unit = {},
    importDocuments: (suspend (String, List<String>) -> List<WakePlaylistImportResult>)? = null,
    ownedFileExists: ((String) -> Boolean)? = null,
    deleteOwnedBytes: ((WakeTrack) -> Boolean)? = null,
    usePlatformNameDialog: Boolean = true,
) {
    val context = LocalContext.current
    val importedAudioFallbackTitle = stringResource(R.string.warmly_imported_audio_title)
    val resolver =
        remember(context, playlistStore, importedAudioFallbackTitle) {
            WakePlaylistDocumentResolver.production(
                context = context,
                playlistStore = playlistStore,
                fallbackTitle = importedAudioFallbackTitle,
            )
        }
    val factory =
        remember(playlistStore, resolver, importDocuments, ownedFileExists, deleteOwnedBytes) {
            WakePlaylistViewModelFactory(
                playlistStore = playlistStore,
                importDocuments = importDocuments ?: resolver::importIntoPlaylist,
                ownedFileExists =
                    ownedFileExists?.let { exists -> { path: String -> exists(path) } }
                        ?: resolver::ownedFileExists,
                deleteOwnedBytes =
                    deleteOwnedBytes?.let { delete -> { track: WakeTrack -> delete(track) } }
                        ?: resolver::deleteOwnedBytes,
            )
        }
    val model: WakePlaylistViewModel = viewModel(factory = factory)
    val routeState by model.state.collectAsStateWithLifecycle()
    val state = routeState.screen
    var showPreview by remember { mutableStateOf(false) }
    var previewProgress by remember { mutableFloatStateOf(0f) }

    val importPicker =
        rememberLauncherForActivityResult(WakeAudioDocumentsContract()) { uris ->
            model.completePicker(uris)
        }
    val recoveryPicker =
        rememberLauncherForActivityResult(WakeAudioDocumentContract()) { uri ->
            model.completePicker(uri?.let(::listOf).orEmpty())
        }

    LaunchedEffect(state.selectedForWakeId, state.playlists) {
        onSelectionChanged(
            state.selectedForWakeId?.let { id ->
                state.playlists.singleOrNull { it.id == id }?.let { WakePlaylist(it.id, it.name) }
            }
        )
    }

    BackHandler {
        when {
            routeState.busy -> Unit
            showPreview -> showPreview = false
            state.editor != null -> model.closeEditor()
            else -> onBack()
        }
    }

    if (showPreview) {
        GentleWakePreviewRoute(
            progress = previewProgress,
            onProgressChange = { previewProgress = it },
            onAwake = { showPreview = false },
            playlistStore = playlistStore,
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        WakePlaylistScreen(
            state = state,
            onCreatePlaylist = model::showCreateDialog,
            onSelectForWake = model::selectPlaylist,
            onRenamePlaylist = model::showRenameDialog,
            onEditPlaylist = model::openEditor,
            onImportTracks = {
                model.requestImport()
                importPicker.launch(Unit)
            },
            onMoveTrackUp = { trackId ->
                val index = state.editor?.tracks?.indexOfFirst { it.id == trackId } ?: -1
                if (index > 0) model.moveTrack(trackId, index - 1)
            },
            onMoveTrackDown = { trackId ->
                val editor = state.editor
                val index = editor?.tracks?.indexOfFirst { it.id == trackId } ?: -1
                if (editor != null && index in 0 until editor.tracks.lastIndex) {
                    model.moveTrack(trackId, index + 1)
                }
            },
            onRemoveFromPlaylist = model::removeTrack,
            onDeleteOwnedAudio = model::requestDeleteOwnedAudio,
            onFindMissingTrack = { trackId ->
                model.requestFind(trackId)
                recoveryPicker.launch(Unit)
            },
            onReplaceMissingTrack = { trackId ->
                model.requestReplace(trackId)
                recoveryPicker.launch(Unit)
            },
            onBack = {
                if (state.editor != null) {
                    model.closeEditor()
                } else if (!routeState.busy) {
                    onBack()
                }
            },
            onPreview = {
                state.editor?.id?.let(model::selectPlaylist)
                previewProgress = 0f
                showPreview = true
            },
        )

        if (routeState.busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
        }
        routeState.error?.let { error ->
            Text(
                text = stringResource(error.messageResource()),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }

        routeState.nameDialog?.let { dialog ->
            PlaylistNameDialog(
                title =
                    stringResource(
                        if (dialog is PlaylistNameDialogState.Create)
                            R.string.warmly_playlist_create
                        else R.string.warmly_playlist_rename
                    ),
                value = dialog.name,
                confirmLabel =
                    stringResource(
                        if (dialog is PlaylistNameDialogState.Create) {
                            R.string.warmly_playlist_create_confirm
                        } else {
                            R.string.warmly_playlist_rename_confirm
                        }
                    ),
                busy = routeState.busy,
                onValueChange = model::updateNameDialog,
                onDismiss = model::dismissNameDialog,
                onConfirm = model::confirmNameDialog,
                usePlatformDialog = usePlatformNameDialog,
            )
        }

        routeState.deleteConfirmationTrackId?.let {
            ConfirmationDialog(
                title = stringResource(R.string.warmly_playlist_delete_confirm_title),
                body = stringResource(R.string.warmly_playlist_delete_confirm_body),
                confirmLabel = stringResource(R.string.warmly_playlist_delete_confirm),
                busy = routeState.busy,
                onDismiss = model::dismissDeleteConfirmation,
                onConfirm = model::confirmDeleteOwnedAudio,
                usePlatformDialog = usePlatformNameDialog,
            )
        }
    }
}

private class WakePlaylistViewModelFactory(
    private val playlistStore: WakePlaylistStore,
    private val importDocuments: suspend (String, List<String>) -> List<WakePlaylistImportResult>,
    private val ownedFileExists: suspend (String) -> Boolean,
    private val deleteOwnedBytes: suspend (WakeTrack) -> Boolean,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass.isAssignableFrom(WakePlaylistViewModel::class.java))
        @Suppress("UNCHECKED_CAST")
        return WakePlaylistViewModel(
            savedStateHandle = extras.createSavedStateHandle(),
            playlistStore = playlistStore,
            importDocuments = importDocuments,
            ownedFileExists = ownedFileExists,
            deleteOwnedBytes = deleteOwnedBytes,
        )
            as T
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    value: String,
    confirmLabel: String,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    usePlatformDialog: Boolean,
) {
    DialogSurface(usePlatformDialog, onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.warmly_playlist_name)) },
            singleLine = true,
            enabled = !busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.warmly_cancel))
            }
            Button(onClick = onConfirm, enabled = value.isNotBlank() && !busy) {
                Text(confirmLabel)
            }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    usePlatformDialog: Boolean,
) {
    DialogSurface(usePlatformDialog, onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.warmly_cancel))
            }
            Button(onClick = onConfirm, enabled = !busy) { Text(confirmLabel) }
        }
    }
}

@Composable
private fun DialogSurface(
    usePlatformDialog: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val surface: @Composable () -> Unit = {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        }
    }
    if (usePlatformDialog) {
        Dialog(onDismissRequest = onDismiss) { surface() }
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            surface()
        }
    }
}

internal fun List<WakePlaylistImportResult>.toImportSummary() =
    WakePlaylistImportSummaryUi(
        added = count { it is WakePlaylistImportResult.Added },
        duplicates = count { it is WakePlaylistImportResult.AlreadyInPlaylist },
        unsupported = count { it is WakePlaylistImportResult.Unsupported },
        failed = count { it is WakePlaylistImportResult.Failed },
    )

private fun PlaylistMutationError.messageResource(): Int =
    when (this) {
        PlaylistMutationError.DELETE -> R.string.warmly_playlist_delete_failed
        PlaylistMutationError.LOAD -> R.string.warmly_playlist_load_failed
        else -> R.string.warmly_playlist_save_failed
    }
