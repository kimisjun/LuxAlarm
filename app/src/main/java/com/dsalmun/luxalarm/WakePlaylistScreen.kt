/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class WakePlaylistScreenState(
    val playlists: List<WakePlaylistItemUi> = emptyList(),
    val selectedForWakeId: String? = null,
    val editor: WakePlaylistEditorUi? = null,
    val importSummary: WakePlaylistImportSummaryUi? = null,
)

data class WakePlaylistItemUi(val id: String, val name: String)

data class WakePlaylistEditorUi(
    val id: String,
    val name: String,
    val tracks: List<WakePlaylistTrackUi>,
)

data class WakePlaylistTrackUi(
    val id: String,
    val title: String,
    val isMissing: Boolean = false,
)

data class WakePlaylistImportSummaryUi(
    val added: Int = 0,
    val duplicates: Int = 0,
    val unsupported: Int = 0,
    val failed: Int = 0,
)

@Composable
fun WakePlaylistScreen(
    state: WakePlaylistScreenState,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectForWake: (String) -> Unit = {},
    onRenamePlaylist: (String) -> Unit = {},
    onEditPlaylist: (String) -> Unit = {},
    onMoveTrackUp: (String) -> Unit = {},
    onMoveTrackDown: (String) -> Unit = {},
    onRemoveFromPlaylist: (String) -> Unit = {},
    onDeleteOwnedAudio: (String) -> Unit = {},
    onImportTracks: () -> Unit = {},
    onFindMissingTrack: (String) -> Unit = {},
    onReplaceMissingTrack: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onPreview: () -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.warmly_back)) }
        state.editor?.let { editor ->
            PlaylistEditor(
                editor = editor,
                onRenamePlaylist = onRenamePlaylist,
                onMoveTrackUp = onMoveTrackUp,
                onMoveTrackDown = onMoveTrackDown,
                onRemoveFromPlaylist = onRemoveFromPlaylist,
                onDeleteOwnedAudio = onDeleteOwnedAudio,
                onImportTracks = onImportTracks,
                onFindMissingTrack = onFindMissingTrack,
                onReplaceMissingTrack = onReplaceMissingTrack,
                onPreview = onPreview,
            )
        }
            ?: PlaylistCatalog(
                state,
                onCreatePlaylist,
                onSelectForWake,
                onRenamePlaylist,
                onEditPlaylist,
                onPreview,
            )
        state.importSummary?.let { ImportSummary(it) }
    }
}

@Composable
private fun PlaylistCatalog(
    state: WakePlaylistScreenState,
    onCreatePlaylist: () -> Unit,
    onSelectForWake: (String) -> Unit,
    onRenamePlaylist: (String) -> Unit,
    onEditPlaylist: (String) -> Unit,
    onPreview: () -> Unit,
) {
    Text(
        text = stringResource(R.string.warmly_playlist_title),
        style = MaterialTheme.typography.headlineMedium,
    )
    if (state.playlists.isEmpty()) {
        Text(stringResource(R.string.warmly_playlist_empty))
    } else {
        state.playlists.forEach { playlist ->
            val isSelected = playlist.id == state.selectedForWakeId
            Surface(
                modifier = Modifier.fillMaxWidth().semantics { selected = isSelected },
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                    if (isSelected) {
                        Text(stringResource(R.string.warmly_playlist_selected_for_wake))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isSelected) {
                            TextButton(onClick = { onSelectForWake(playlist.id) }) {
                                Text(stringResource(R.string.warmly_playlist_use_for_wake))
                            }
                        }
                        RenameButton(playlist, onRenamePlaylist)
                        val editDescription =
                            stringResource(R.string.warmly_playlist_edit_description, playlist.name)
                        TextButton(
                            onClick = { onEditPlaylist(playlist.id) },
                            modifier = Modifier.semantics { contentDescription = editDescription },
                        ) {
                            Text(stringResource(R.string.warmly_playlist_edit))
                        }
                    }
                }
            }
        }
    }
    Button(onClick = onCreatePlaylist, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.warmly_playlist_create))
    }
    Button(
        onClick = onPreview,
        enabled = state.selectedForWakeId != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.warmly_playlist_preview))
    }
}

@Composable
private fun RenameButton(playlist: WakePlaylistItemUi, onRenamePlaylist: (String) -> Unit) {
    val description = stringResource(R.string.warmly_playlist_rename_description, playlist.name)
    TextButton(
        onClick = { onRenamePlaylist(playlist.id) },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(stringResource(R.string.warmly_playlist_rename))
    }
}

@Composable
private fun PlaylistEditor(
    editor: WakePlaylistEditorUi,
    onRenamePlaylist: (String) -> Unit,
    onMoveTrackUp: (String) -> Unit,
    onMoveTrackDown: (String) -> Unit,
    onRemoveFromPlaylist: (String) -> Unit,
    onDeleteOwnedAudio: (String) -> Unit,
    onImportTracks: () -> Unit,
    onFindMissingTrack: (String) -> Unit,
    onReplaceMissingTrack: (String) -> Unit,
    onPreview: () -> Unit,
) {
    Text(editor.name, style = MaterialTheme.typography.headlineMedium)
    RenameButton(WakePlaylistItemUi(editor.id, editor.name), onRenamePlaylist)
    Button(onClick = onImportTracks, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.warmly_playlist_import_songs))
    }
    Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.warmly_playlist_preview))
    }
    editor.tracks.forEachIndexed { index, track ->
        TrackCard(
            track = track,
            canMoveUp = index > 0,
            canMoveDown = index < editor.tracks.lastIndex,
            onMoveTrackUp = onMoveTrackUp,
            onMoveTrackDown = onMoveTrackDown,
            onRemoveFromPlaylist = onRemoveFromPlaylist,
            onDeleteOwnedAudio = onDeleteOwnedAudio,
            onFindMissingTrack = onFindMissingTrack,
            onReplaceMissingTrack = onReplaceMissingTrack,
        )
    }
}

@Composable
private fun TrackCard(
    track: WakePlaylistTrackUi,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveTrackUp: (String) -> Unit,
    onMoveTrackDown: (String) -> Unit,
    onRemoveFromPlaylist: (String) -> Unit,
    onDeleteOwnedAudio: (String) -> Unit,
    onFindMissingTrack: (String) -> Unit,
    onReplaceMissingTrack: (String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val handleDescription =
                stringResource(R.string.warmly_playlist_reorder_handle_description, track.title)
            Text(
                text = "≡",
                modifier = Modifier.semantics { contentDescription = handleDescription },
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier.testTag("playlist-track-title")
                        .then(if (track.isMissing) Modifier.semantics { disabled() } else Modifier),
            )
            if (track.isMissing) {
                Text(stringResource(R.string.warmly_playlist_file_missing))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val findDescription =
                        stringResource(R.string.warmly_playlist_find_description, track.title)
                    TextButton(
                        onClick = { onFindMissingTrack(track.id) },
                        modifier = Modifier.semantics { contentDescription = findDescription },
                    ) {
                        Text(stringResource(R.string.warmly_playlist_find))
                    }
                    val replaceDescription =
                        stringResource(R.string.warmly_playlist_replace_description, track.title)
                    TextButton(
                        onClick = { onReplaceMissingTrack(track.id) },
                        modifier = Modifier.semantics { contentDescription = replaceDescription },
                    ) {
                        Text(stringResource(R.string.warmly_playlist_replace))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val moveUpDescription =
                    stringResource(R.string.warmly_playlist_move_up_description, track.title)
                TextButton(
                    onClick = { onMoveTrackUp(track.id) },
                    enabled = canMoveUp,
                    modifier = Modifier.semantics { contentDescription = moveUpDescription },
                ) {
                    Text(stringResource(R.string.warmly_playlist_move_up))
                }
                val moveDownDescription =
                    stringResource(R.string.warmly_playlist_move_down_description, track.title)
                TextButton(
                    onClick = { onMoveTrackDown(track.id) },
                    enabled = canMoveDown,
                    modifier = Modifier.semantics { contentDescription = moveDownDescription },
                ) {
                    Text(stringResource(R.string.warmly_playlist_move_down))
                }
            }
            val removeDescription =
                stringResource(R.string.warmly_playlist_remove_description, track.title)
            TextButton(
                onClick = { onRemoveFromPlaylist(track.id) },
                modifier = Modifier.semantics { contentDescription = removeDescription },
            ) {
                Text(stringResource(R.string.warmly_playlist_remove_membership))
            }
            if (!track.isMissing) {
                val deleteDescription =
                    stringResource(R.string.warmly_playlist_delete_description, track.title)
                TextButton(
                    onClick = { onDeleteOwnedAudio(track.id) },
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                ) {
                    Text(stringResource(R.string.warmly_playlist_delete_owned_audio))
                }
            }
        }
    }
}

@Composable
private fun ImportSummary(summary: WakePlaylistImportSummaryUi) {
    Text(
        stringResource(
            R.string.warmly_playlist_import_summary,
            summary.added,
            summary.duplicates,
            summary.unsupported,
            summary.failed,
        )
    )
}
