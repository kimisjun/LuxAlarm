/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface PendingPickerOperation {
    val playlistId: String

    data class Import(override val playlistId: String) : PendingPickerOperation

    data class Find(override val playlistId: String, val oldTrackId: String) :
        PendingPickerOperation

    data class Replace(override val playlistId: String, val oldTrackId: String) :
        PendingPickerOperation
}

enum class PlaylistMutationError {
    CREATE,
    RENAME,
    SELECT,
    MOVE,
    REMOVE,
    DELETE,
    IMPORT,
    LOAD,
}

sealed interface PlaylistNameDialogState {
    val name: String

    data class Create(override val name: String = "") : PlaylistNameDialogState

    data class Rename(val playlistId: String, override val name: String) : PlaylistNameDialogState
}

data class WakePlaylistRouteState(
    val screen: WakePlaylistScreenState = WakePlaylistScreenState(),
    val nameDialog: PlaylistNameDialogState? = null,
    val deleteConfirmationTrackId: String? = null,
    val busy: Boolean = false,
    val error: PlaylistMutationError? = null,
)

class WakePlaylistViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val playlistStore: WakePlaylistStore,
    private val importDocuments: suspend (String, List<String>) -> List<WakePlaylistImportResult>,
    private val ownedFileExists: suspend (String) -> Boolean,
    private val deleteOwnedBytes: suspend (WakeTrack) -> Boolean,
) : ViewModel() {
    private val mutationMutex = Mutex()
    private val _state =
        MutableStateFlow(
            WakePlaylistRouteState(
                nameDialog = restoredNameDialog(),
                deleteConfirmationTrackId = savedStateHandle[KEY_DELETE_TRACK_ID],
            )
        )
    val state: StateFlow<WakePlaylistRouteState> = _state.asStateFlow()

    val pendingPickerOperation: PendingPickerOperation?
        get() = decodePendingOperation()

    init {
        refresh()
    }

    fun openEditor(playlistId: String) {
        savedStateHandle[KEY_EDITOR_ID] = playlistId
        refresh()
    }

    fun closeEditor(): Boolean {
        if (_state.value.busy) return false
        savedStateHandle[KEY_EDITOR_ID] = null
        _state.value =
            _state.value.copy(
                screen = _state.value.screen.copy(editor = null, importSummary = null),
                error = null,
            )
        return true
    }

    fun showCreateDialog() {
        setNameDialog(PlaylistNameDialogState.Create())
    }

    fun showRenameDialog(playlistId: String) {
        val name =
            _state.value.screen.playlists.singleOrNull { it.id == playlistId }?.name ?: return
        setNameDialog(PlaylistNameDialogState.Rename(playlistId, name))
    }

    fun updateNameDialog(name: String) {
        val updated =
            when (val dialog = _state.value.nameDialog) {
                is PlaylistNameDialogState.Create -> dialog.copy(name = name)
                is PlaylistNameDialogState.Rename -> dialog.copy(name = name)
                null -> return
            }
        setNameDialog(updated)
    }

    fun dismissNameDialog() {
        if (!_state.value.busy) setNameDialog(null)
    }

    fun confirmNameDialog() {
        val dialog = _state.value.nameDialog ?: return
        val name = dialog.name.trim()
        if (name.isEmpty()) return
        val failure =
            if (dialog is PlaylistNameDialogState.Create) PlaylistMutationError.CREATE
            else PlaylistMutationError.RENAME
        durable(failure) {
            when (dialog) {
                is PlaylistNameDialogState.Create -> playlistStore.createPlaylist(name)
                is PlaylistNameDialogState.Rename ->
                    playlistStore.renamePlaylist(dialog.playlistId, name)
            }
            setNameDialog(null)
            refreshNow()
        }
    }

    fun selectPlaylist(playlistId: String) {
        durable(PlaylistMutationError.SELECT) {
            playlistStore.selectPlaylistForWake(playlistId)
            refreshNow()
        }
    }

    fun moveTrack(trackId: String, position: Int) {
        val playlistId = currentEditorId() ?: return
        durable(PlaylistMutationError.MOVE) {
            playlistStore.moveTrack(playlistId, trackId, position)
            refreshNow()
        }
    }

    fun removeTrack(trackId: String) {
        val playlistId = currentEditorId() ?: return
        durable(PlaylistMutationError.REMOVE) {
            playlistStore.removeTrack(playlistId, trackId)
            refreshNow()
        }
    }

    fun requestDeleteOwnedAudio(trackId: String) {
        savedStateHandle[KEY_DELETE_TRACK_ID] = trackId
        _state.value = _state.value.copy(deleteConfirmationTrackId = trackId, error = null)
    }

    fun dismissDeleteConfirmation() {
        if (_state.value.busy) return
        savedStateHandle[KEY_DELETE_TRACK_ID] = null
        _state.value = _state.value.copy(deleteConfirmationTrackId = null)
    }

    fun confirmDeleteOwnedAudio() {
        val playlistId = currentEditorId() ?: return
        val trackId = _state.value.deleteConfirmationTrackId ?: return
        durable(PlaylistMutationError.DELETE) {
            val track =
                playlistStore.listEntries(playlistId).singleOrNull { it.track.id == trackId }?.track
                    ?: error("Track is no longer in the playlist")
            check(deleteOwnedBytes(track)) { "Owned audio could not be deleted" }
            savedStateHandle[KEY_DELETE_TRACK_ID] = null
            _state.value = _state.value.copy(deleteConfirmationTrackId = null)
            refreshNow()
        }
    }

    fun requestImport() {
        currentEditorId()?.let { savePending(PendingPickerOperation.Import(it)) }
    }

    fun requestFind(oldTrackId: String) {
        currentEditorId()?.let { savePending(PendingPickerOperation.Find(it, oldTrackId)) }
    }

    fun requestReplace(oldTrackId: String) {
        currentEditorId()?.let { savePending(PendingPickerOperation.Replace(it, oldTrackId)) }
    }

    fun completePicker(documentUris: List<String>) {
        val operation = pendingPickerOperation ?: return
        clearPending()
        if (documentUris.isEmpty()) return
        durable(PlaylistMutationError.IMPORT) {
            val results = importDocuments(operation.playlistId, documentUris)
            when (operation) {
                is PendingPickerOperation.Import -> Unit
                is PendingPickerOperation.Find -> preserveFindMembership(operation, results)
                is PendingPickerOperation.Replace -> replaceAfterDistinctAdd(operation, results)
            }
            _state.value =
                _state.value.copy(
                    screen = _state.value.screen.copy(importSummary = results.toImportSummary())
                )
            refreshNow()
        }
    }

    private suspend fun preserveFindMembership(
        operation: PendingPickerOperation.Find,
        results: List<WakePlaylistImportResult>,
    ) {
        results.filterIsInstance<WakePlaylistImportResult.Added>().forEach { result ->
            if (result.ownedTrack.id != operation.oldTrackId) {
                playlistStore.removeTrack(operation.playlistId, result.ownedTrack.id)
            }
        }
    }

    private suspend fun replaceAfterDistinctAdd(
        operation: PendingPickerOperation.Replace,
        results: List<WakePlaylistImportResult>,
    ) {
        val replacementAdded = results.singleOrNull() as? WakePlaylistImportResult.Added ?: return
        if (replacementAdded.ownedTrack.id != operation.oldTrackId) {
            playlistStore.removeTrack(operation.playlistId, operation.oldTrackId)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { refreshNow() }
                .onFailure { _state.value = _state.value.copy(error = PlaylistMutationError.LOAD) }
        }
    }

    private suspend fun refreshNow() {
        val editorId = currentEditorId()
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
        _state.value =
            _state.value.copy(
                screen =
                    _state.value.screen.copy(
                        playlists = playlists.map { WakePlaylistItemUi(it.id, it.name) },
                        selectedForWakeId = selected?.id,
                        editor = editor,
                    )
            )
    }

    private fun durable(error: PlaylistMutationError, block: suspend () -> Unit) {
        viewModelScope.launch {
            mutationMutex.withLock {
                _state.value = _state.value.copy(busy = true, error = null)
                try {
                    block()
                } catch (_: Exception) {
                    _state.value = _state.value.copy(error = error)
                } finally {
                    _state.value = _state.value.copy(busy = false)
                }
            }
        }
    }

    private fun currentEditorId(): String? = savedStateHandle[KEY_EDITOR_ID]

    private fun setNameDialog(dialog: PlaylistNameDialogState?) {
        savedStateHandle[KEY_DIALOG_KIND] =
            when (dialog) {
                is PlaylistNameDialogState.Create -> DIALOG_CREATE
                is PlaylistNameDialogState.Rename -> DIALOG_RENAME
                null -> null
            }
        savedStateHandle[KEY_DIALOG_PLAYLIST_ID] =
            (dialog as? PlaylistNameDialogState.Rename)?.playlistId
        savedStateHandle[KEY_DIALOG_NAME] = dialog?.name
        _state.value = _state.value.copy(nameDialog = dialog, error = null)
    }

    private fun restoredNameDialog(): PlaylistNameDialogState? =
        when (savedStateHandle.get<String>(KEY_DIALOG_KIND)) {
            DIALOG_CREATE -> PlaylistNameDialogState.Create(savedStateHandle[KEY_DIALOG_NAME] ?: "")
            DIALOG_RENAME -> {
                val playlistId = savedStateHandle.get<String>(KEY_DIALOG_PLAYLIST_ID)
                playlistId?.let {
                    PlaylistNameDialogState.Rename(it, savedStateHandle[KEY_DIALOG_NAME] ?: "")
                }
            }
            else -> null
        }

    private fun savePending(operation: PendingPickerOperation) {
        savedStateHandle[KEY_PENDING_KIND] =
            when (operation) {
                is PendingPickerOperation.Import -> PENDING_IMPORT
                is PendingPickerOperation.Find -> PENDING_FIND
                is PendingPickerOperation.Replace -> PENDING_REPLACE
            }
        savedStateHandle[KEY_PENDING_PLAYLIST_ID] = operation.playlistId
        savedStateHandle[KEY_PENDING_TRACK_ID] =
            when (operation) {
                is PendingPickerOperation.Import -> null
                is PendingPickerOperation.Find -> operation.oldTrackId
                is PendingPickerOperation.Replace -> operation.oldTrackId
            }
    }

    private fun decodePendingOperation(): PendingPickerOperation? {
        val playlistId = savedStateHandle.get<String>(KEY_PENDING_PLAYLIST_ID) ?: return null
        return when (savedStateHandle.get<String>(KEY_PENDING_KIND)) {
            PENDING_IMPORT -> PendingPickerOperation.Import(playlistId)
            PENDING_FIND ->
                savedStateHandle.get<String>(KEY_PENDING_TRACK_ID)?.let {
                    PendingPickerOperation.Find(playlistId, it)
                }
            PENDING_REPLACE ->
                savedStateHandle.get<String>(KEY_PENDING_TRACK_ID)?.let {
                    PendingPickerOperation.Replace(playlistId, it)
                }
            else -> null
        }
    }

    private fun clearPending() {
        savedStateHandle[KEY_PENDING_KIND] = null
        savedStateHandle[KEY_PENDING_PLAYLIST_ID] = null
        savedStateHandle[KEY_PENDING_TRACK_ID] = null
    }

    private companion object {
        const val KEY_EDITOR_ID = "playlist.editorId"
        const val KEY_PENDING_KIND = "playlist.pending.kind"
        const val KEY_PENDING_PLAYLIST_ID = "playlist.pending.playlistId"
        const val KEY_PENDING_TRACK_ID = "playlist.pending.trackId"
        const val KEY_DIALOG_KIND = "playlist.dialog.kind"
        const val KEY_DIALOG_PLAYLIST_ID = "playlist.dialog.playlistId"
        const val KEY_DIALOG_NAME = "playlist.dialog.name"
        const val KEY_DELETE_TRACK_ID = "playlist.delete.trackId"
        const val PENDING_IMPORT = "import"
        const val PENDING_FIND = "find"
        const val PENDING_REPLACE = "replace"
        const val DIALOG_CREATE = "create"
        const val DIALOG_RENAME = "rename"
    }
}
