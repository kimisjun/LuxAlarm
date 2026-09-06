/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
    REFRESH,
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
    private val findDocument: suspend (String, String, String) -> WakePlaylistFindResult,
    private val ownedFileExists: suspend (String) -> Boolean,
    private val deleteOwnedBytes: suspend (WakeTrack) -> Boolean,
) : ViewModel() {
    private val mutationMutex = Mutex()
    private var refreshGeneration = 0L
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
                is PlaylistNameDialogState.Create -> {
                    val created = playlistStore.createPlaylist(name)
                    setNameDialog(null)
                    _state.value =
                        _state.value.copy(
                            screen =
                                _state.value.screen.copy(
                                    playlists =
                                        (_state.value.screen.playlists +
                                                WakePlaylistItemUi(created.id, created.name))
                                            .distinctBy { it.id }
                                )
                        )
                }
                is PlaylistNameDialogState.Rename -> {
                    playlistStore.renamePlaylist(dialog.playlistId, name)
                    val screen = _state.value.screen
                    _state.value =
                        _state.value.copy(
                            screen =
                                screen.copy(
                                    playlists =
                                        screen.playlists.map {
                                            if (it.id == dialog.playlistId) it.copy(name = name)
                                            else it
                                        },
                                    editor =
                                        screen.editor?.let {
                                            if (it.id == dialog.playlistId) it.copy(name = name)
                                            else it
                                        },
                                )
                        )
                }
            }
            setNameDialog(null)
            refreshAfterCommit()
        }
    }

    fun selectPlaylist(playlistId: String) {
        durable(PlaylistMutationError.SELECT) {
            playlistStore.selectPlaylistForWake(playlistId)
            _state.value =
                _state.value.copy(screen = _state.value.screen.copy(selectedForWakeId = playlistId))
            refreshAfterCommit()
        }
    }

    fun moveTrack(trackId: String, position: Int) {
        val playlistId = actionableEditorId() ?: return
        durable(PlaylistMutationError.MOVE) {
            playlistStore.moveTrack(playlistId, trackId, position)
            val editor = _state.value.screen.editor
            if (editor?.id == playlistId) {
                val tracks = editor.tracks.toMutableList()
                val oldIndex = tracks.indexOfFirst { it.id == trackId }
                if (oldIndex >= 0) {
                    val moved = tracks.removeAt(oldIndex)
                    tracks.add(position.coerceIn(0, tracks.size), moved)
                    _state.value =
                        _state.value.copy(
                            screen = _state.value.screen.copy(editor = editor.copy(tracks = tracks))
                        )
                }
            }
            refreshAfterCommit()
        }
    }

    fun removeTrack(trackId: String) {
        val playlistId = actionableEditorId() ?: return
        durable(PlaylistMutationError.REMOVE) {
            playlistStore.removeTrack(playlistId, trackId)
            val editor = _state.value.screen.editor
            if (editor?.id == playlistId) {
                _state.value =
                    _state.value.copy(
                        screen =
                            _state.value.screen.copy(
                                editor =
                                    editor.copy(
                                        tracks = editor.tracks.filterNot { it.id == trackId }
                                    )
                            )
                    )
            }
            refreshAfterCommit()
        }
    }

    fun requestDeleteOwnedAudio(trackId: String) {
        if (actionableEditorId() == null) return
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
            val editor = _state.value.screen.editor
            _state.value =
                _state.value.copy(
                    deleteConfirmationTrackId = null,
                    screen =
                        _state.value.screen.copy(
                            editor =
                                editor?.copy(
                                    tracks =
                                        editor.tracks.map {
                                            if (it.id == trackId) it.copy(isMissing = true) else it
                                        }
                                )
                        ),
                )
            refreshAfterCommit()
        }
    }

    fun requestImport() {
        actionableEditorId()?.let { savePending(PendingPickerOperation.Import(it)) }
    }

    fun requestFind(oldTrackId: String) {
        actionableEditorId()?.let { savePending(PendingPickerOperation.Find(it, oldTrackId)) }
    }

    fun requestReplace(oldTrackId: String) {
        actionableEditorId()?.let { savePending(PendingPickerOperation.Replace(it, oldTrackId)) }
    }

    fun completePicker(documentUris: List<String>) {
        val operation = pendingPickerOperation ?: return
        if (documentUris.isEmpty()) {
            clearPending()
            return
        }
        if (operation !is PendingPickerOperation.Find) clearPending()
        durable(PlaylistMutationError.IMPORT) {
            if (operation is PendingPickerOperation.Find) {
                val result =
                    findDocument(operation.playlistId, operation.oldTrackId, documentUris.single())
                _state.value =
                    _state.value.copy(
                        screen = _state.value.screen.copy(importSummary = result.toImportSummary())
                    )
                clearPending()
            } else {
                val results = importDocuments(operation.playlistId, documentUris)
                if (operation is PendingPickerOperation.Replace) {
                    replaceAfterDistinctAdd(operation, results)
                }
                _state.value =
                    _state.value.copy(
                        screen = _state.value.screen.copy(importSummary = results.toImportSummary())
                    )
            }
            refreshAfterCommit()
        }
    }

    private suspend fun replaceAfterDistinctAdd(
        operation: PendingPickerOperation.Replace,
        results: List<WakePlaylistImportResult>,
    ) {
        val replacementTrackId =
            when (val replacement = results.singleOrNull()) {
                is WakePlaylistImportResult.Added -> replacement.ownedTrack.id
                is WakePlaylistImportResult.AlreadyInPlaylist -> replacement.ownedTrack.id
                else -> return
            }
        if (replacementTrackId != operation.oldTrackId) {
            playlistStore.removeTrack(operation.playlistId, operation.oldTrackId)
        }
    }

    fun refresh() {
        val request = beginRefresh()
        viewModelScope.launch {
            try {
                refreshNow(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrent(request)) {
                    _state.value = _state.value.copy(error = PlaylistMutationError.LOAD)
                }
            }
        }
    }

    private suspend fun refreshNow(request: RefreshRequest = beginRefresh()) {
        val editorId = request.editorId
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
        if (!isCurrent(request)) return
        _state.value =
            _state.value.copy(
                screen =
                    _state.value.screen.copy(
                        playlists = playlists.map { WakePlaylistItemUi(it.id, it.name) },
                        selectedForWakeId = selected?.id,
                        editor = editor,
                    ),
                error = null,
            )
    }

    private suspend fun refreshAfterCommit() {
        val request = beginRefresh()
        try {
            refreshNow(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isCurrent(request)) {
                _state.value = _state.value.copy(error = PlaylistMutationError.REFRESH)
            }
        }
    }

    private fun beginRefresh(): RefreshRequest =
        RefreshRequest(generation = ++refreshGeneration, editorId = currentEditorId())

    private fun isCurrent(request: RefreshRequest): Boolean =
        request.generation == refreshGeneration && request.editorId == currentEditorId()

    private data class RefreshRequest(val generation: Long, val editorId: String?)

    private fun durable(error: PlaylistMutationError, block: suspend () -> Unit) {
        viewModelScope.launch {
            mutationMutex.withLock {
                _state.value = _state.value.copy(busy = true, error = null)
                try {
                    block()
                } catch (caught: CancellationException) {
                    throw caught
                } catch (_: Exception) {
                    _state.value = _state.value.copy(error = error)
                } finally {
                    _state.value = _state.value.copy(busy = false)
                }
            }
        }
    }

    private fun currentEditorId(): String? = savedStateHandle[KEY_EDITOR_ID]

    private fun actionableEditorId(): String? {
        val current = currentEditorId() ?: return null
        val displayed = _state.value.screen.editor?.id
        return current.takeIf { displayed == null || displayed == current }
    }

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
