/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.lifecycle.SavedStateHandle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakePlaylistViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pickerResultUsesCapturedPlaylistAfterEditorChangesAndRecreation() =
        runTest(dispatcher) {
            val handle = SavedStateHandle()
            val store = RecordingPlaylistStore()
            val imports = mutableListOf<Pair<String, List<String>>>()
            val first =
                viewModel(handle, store) { playlistId, uris ->
                    imports += playlistId to uris
                    emptyList()
                }
            first.openEditor("playlist-a")
            first.requestImport()
            first.openEditor("playlist-b")

            val recreated =
                viewModel(handle, store) { playlistId, uris ->
                    imports += playlistId to uris
                    emptyList()
                }
            recreated.completePicker(listOf("content://song"))
            advanceUntilIdle()

            assertEquals(listOf("playlist-a" to listOf("content://song")), imports)
            assertNull(recreated.pendingPickerOperation)
        }

    @Test
    fun cancellationClearsFindBeforeNextOrdinaryImport() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val imports = mutableListOf<String>()
            val model =
                viewModel(SavedStateHandle(), store) { playlistId, _ ->
                    imports += playlistId
                    emptyList()
                }
            model.openEditor("playlist-a")
            model.requestFind("missing")
            assertIs<PendingPickerOperation.Find>(model.pendingPickerOperation)

            model.completePicker(emptyList())
            model.requestImport()
            assertIs<PendingPickerOperation.Import>(model.pendingPickerOperation)
            model.completePicker(listOf("content://song"))
            advanceUntilIdle()

            assertEquals(listOf("playlist-a"), imports)
            assertFalse(store.removeCalls.any { it.second == "missing" })
        }

    @Test
    fun findSameFilePreservesMembershipWhileDistinctFindRollsBackNewMembership() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            var result: WakePlaylistImportResult = imported("missing", added = false)
            val model = viewModel(SavedStateHandle(), store) { _, _ -> listOf(result) }
            model.openEditor("playlist-a")
            model.requestFind("missing")
            model.completePicker(listOf("content://same"))
            advanceUntilIdle()
            assertEquals(emptyList(), store.removeCalls)

            result = imported("different", added = true)
            model.requestFind("missing")
            model.completePicker(listOf("content://different"))
            advanceUntilIdle()
            assertEquals(listOf("playlist-a" to "different"), store.removeCalls)
        }

    @Test
    fun replaceRemovesOldOnlyAfterOneDistinctTrackWasAdded() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            var results = listOf(imported("replacement", added = true))
            val model = viewModel(SavedStateHandle(), store) { _, _ -> results }
            model.openEditor("playlist-a")

            model.requestReplace("old")
            model.completePicker(listOf("content://replacement"))
            advanceUntilIdle()
            assertEquals(listOf("playlist-a" to "old"), store.removeCalls)

            results = listOf(imported("already-present", added = false))
            model.requestReplace("old-duplicate")
            model.completePicker(listOf("content://duplicate"))
            advanceUntilIdle()
            assertEquals(
                listOf("playlist-a" to "old", "playlist-a" to "old-duplicate"),
                store.removeCalls,
            )

            results =
                listOf(
                    imported("partial", added = true),
                    WakePlaylistImportResult.Failed(
                        "content://broken",
                        IllegalStateException("broken"),
                    ),
                )
            model.requestReplace("old-partial")
            model.completePicker(listOf("content://partial", "content://broken"))
            advanceUntilIdle()

            assertEquals(
                listOf("playlist-a" to "old", "playlist-a" to "old-duplicate"),
                store.removeCalls,
            )
        }

    @Test
    fun replacementRetryFinishesAfterOldRemovalFailedWithoutAddingADuplicate() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore().apply { failNextRemove = true }
            var importCalls = 0
            val model =
                viewModel(SavedStateHandle(), store) { _, _ ->
                    importCalls++
                    listOf(imported("replacement", added = importCalls == 1))
                }
            model.openEditor("playlist-a")
            model.requestReplace("old")
            model.completePicker(listOf("content://replacement"))
            advanceUntilIdle()
            assertEquals(PlaylistMutationError.IMPORT, model.state.value.error)

            model.requestReplace("old")
            model.completePicker(listOf("content://replacement"))
            advanceUntilIdle()

            assertEquals(2, importCalls)
            assertEquals(
                listOf("playlist-a" to "old", "playlist-a" to "old"),
                store.removeCalls,
            )
            assertNull(model.state.value.error)
        }

    @Test
    fun createCommitSurvivesRefreshFailureAndRetryDoesNotCreateAgain() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            advanceUntilIdle()
            model.showCreateDialog()
            model.updateNameDialog("Morning")
            store.failListPlaylists = true

            model.confirmNameDialog()
            advanceUntilIdle()

            assertNull(model.state.value.nameDialog)
            assertEquals(PlaylistMutationError.REFRESH, model.state.value.error)
            assertTrue(model.state.value.screen.playlists.any { it.name == "Morning" })
            assertEquals(1, store.createCalls)

            store.failListPlaylists = false
            model.refresh()
            advanceUntilIdle()
            assertEquals(1, store.createCalls)
            assertTrue(model.state.value.screen.playlists.any { it.name == "Morning" })
        }

    @Test
    fun renameCommitSurvivesRefreshFailureWithCommittedNameVisible() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            advanceUntilIdle()
            model.showRenameDialog("playlist-a")
            model.updateNameDialog("Renamed")
            store.failListPlaylists = true

            model.confirmNameDialog()
            advanceUntilIdle()

            assertNull(model.state.value.nameDialog)
            assertEquals(PlaylistMutationError.REFRESH, model.state.value.error)
            assertEquals(
                "Renamed",
                model.state.value.screen.playlists.single { it.id == "playlist-a" }.name,
            )
            assertEquals(1, store.renameCalls)
        }

    @Test
    fun nameDialogStaysOpenOnFailureAndBackCannotCancelAnActiveCommit() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val gate = CompletableDeferred<Unit>()
            store.createGate = gate
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            model.showCreateDialog()
            model.updateNameDialog("Morning")
            model.confirmNameDialog()
            runCurrent()

            assertTrue(model.state.value.busy)
            assertFalse(model.closeEditor())
            assertIs<PlaylistNameDialogState.Create>(model.state.value.nameDialog)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.nameDialog)

            store.failCreate = true
            model.showCreateDialog()
            model.updateNameDialog("Broken")
            model.confirmNameDialog()
            advanceUntilIdle()
            assertIs<PlaylistNameDialogState.Create>(model.state.value.nameDialog)
            assertEquals(PlaylistMutationError.CREATE, model.state.value.error)
        }

    @Test
    fun selectionCommitIsNotReportedAsFailedWhenItsRefreshFails() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            advanceUntilIdle()
            store.failListPlaylists = true

            model.selectPlaylist("playlist-a")
            advanceUntilIdle()

            assertEquals("playlist-a", model.state.value.screen.selectedForWakeId)
            assertEquals(PlaylistMutationError.REFRESH, model.state.value.error)
        }

    @Test
    fun slowRefreshCannotOverwriteANewerPublishedRefresh() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            advanceUntilIdle()
            val slow = store.enqueuePlaylistRefresh()
            val fast = store.enqueuePlaylistRefresh()

            model.refresh()
            runCurrent()
            model.refresh()
            runCurrent()
            fast.complete(listOf(WakePlaylist("new", "New")))
            runCurrent()
            assertEquals(listOf("New"), model.state.value.screen.playlists.map { it.name })

            slow.complete(listOf(WakePlaylist("old", "Old")))
            advanceUntilIdle()

            assertEquals(listOf("New"), model.state.value.screen.playlists.map { it.name })
        }

    @Test
    fun staleDisplayedEditorControlsCannotTargetANewEditorStillLoading() =
        runTest(dispatcher) {
            val trackA = WakeTrack("track-a", "A track", "/owned/a")
            val store =
                RecordingPlaylistStore(
                    entries = listOf(WakePlaylistEntry("entry-a", "playlist-a", trackA, 0))
                )
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            advanceUntilIdle()
            model.openEditor("playlist-a")
            advanceUntilIdle()
            val loadingB = store.enqueuePlaylistRefresh()

            model.openEditor("playlist-b")
            runCurrent()
            model.moveTrack("track-a", 0)
            advanceUntilIdle()

            assertEquals(emptyList(), store.moveCalls)
            loadingB.complete(
                listOf(WakePlaylist("playlist-a", "A"), WakePlaylist("playlist-b", "B"))
            )
            advanceUntilIdle()
        }

    @Test
    fun durableMutationsAreSerializedAndSelectionRefreshesCommittedSummary() =
        runTest(dispatcher) {
            val store = RecordingPlaylistStore()
            val model = viewModel(SavedStateHandle(), store) { _, _ -> emptyList() }
            model.selectPlaylist("playlist-a")
            model.selectPlaylist("playlist-b")
            advanceUntilIdle()

            assertEquals(1, store.maxConcurrentMutations)
            assertEquals("playlist-b", model.state.value.screen.selectedForWakeId)
        }

    @Test
    fun deleteCommitSurvivesRefreshFailureWithDeletedAudioShownMissing() =
        runTest(dispatcher) {
            val track = WakeTrack("track", "Song", "/owned/track")
            val store =
                RecordingPlaylistStore(
                    entries = listOf(WakePlaylistEntry("entry", "playlist-a", track, 0))
                )
            var deleteCalls = 0
            val model =
                WakePlaylistViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("playlist.editorId" to "playlist-a")),
                    playlistStore = store,
                    importDocuments = { _, _ -> emptyList() },
                    ownedFileExists = { true },
                    deleteOwnedBytes = {
                        deleteCalls++
                        true
                    },
                )
            advanceUntilIdle()
            model.requestDeleteOwnedAudio("track")
            store.failListPlaylists = true

            model.confirmDeleteOwnedAudio()
            advanceUntilIdle()

            assertNull(model.state.value.deleteConfirmationTrackId)
            assertEquals(PlaylistMutationError.REFRESH, model.state.value.error)
            assertTrue(model.state.value.screen.editor!!.tracks.single().isMissing)
            assertEquals(1, deleteCalls)
        }

    @Test
    fun ownedAudioDeletionRequiresConfirmationAndReportsFailure() =
        runTest(dispatcher) {
            val track = WakeTrack("track", "Song", "/owned/track")
            val store =
                RecordingPlaylistStore(
                    entries = listOf(WakePlaylistEntry("entry", "playlist-a", track, 0))
                )
            val model =
                WakePlaylistViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("playlist.editorId" to "playlist-a")),
                    playlistStore = store,
                    importDocuments = { _, _ -> emptyList() },
                    ownedFileExists = { true },
                    deleteOwnedBytes = { false },
                )
            advanceUntilIdle()

            model.requestDeleteOwnedAudio("track")
            assertEquals("track", model.state.value.deleteConfirmationTrackId)
            model.confirmDeleteOwnedAudio()
            advanceUntilIdle()

            assertEquals("track", model.state.value.deleteConfirmationTrackId)
            assertEquals(PlaylistMutationError.DELETE, model.state.value.error)
        }

    private fun imported(trackId: String, added: Boolean): WakePlaylistImportResult {
        val owned = WakeAudioStore.OwnedTrack(trackId, trackId, "/owned/$trackId")
        val entry =
            WakePlaylistEntry(
                "entry-$trackId",
                "playlist-a",
                WakeTrack(trackId, trackId, owned.path),
                0,
            )
        return if (added) {
            WakePlaylistImportResult.Added("content://$trackId", owned, entry, false)
        } else {
            WakePlaylistImportResult.AlreadyInPlaylist("content://$trackId", owned, entry, true)
        }
    }

    private fun viewModel(
        handle: SavedStateHandle,
        store: RecordingPlaylistStore,
        importer: suspend (String, List<String>) -> List<WakePlaylistImportResult>,
    ) =
        WakePlaylistViewModel(
            savedStateHandle = handle,
            playlistStore = store,
            importDocuments = importer,
            ownedFileExists = { true },
            deleteOwnedBytes = { true },
        )
}

private class RecordingPlaylistStore(private val entries: List<WakePlaylistEntry> = emptyList()) :
    WakePlaylistStore {
    val removeCalls = mutableListOf<Pair<String, String>>()
    val moveCalls = mutableListOf<Triple<String, String, Int>>()
    var createGate: CompletableDeferred<Unit>? = null
    var failCreate = false
    var failNextRemove = false
    var failListPlaylists = false
    var createCalls = 0
    var renameCalls = 0
    var maxConcurrentMutations = 0
    private var concurrentMutations = 0
    private var selectedId: String? = null
    private val playlists =
        mutableListOf(WakePlaylist("playlist-a", "A"), WakePlaylist("playlist-b", "B"))
    private val playlistRefreshes = ArrayDeque<CompletableDeferred<List<WakePlaylist>>>()

    fun enqueuePlaylistRefresh(): CompletableDeferred<List<WakePlaylist>> =
        CompletableDeferred<List<WakePlaylist>>().also(playlistRefreshes::addLast)

    override suspend fun createPlaylist(name: String): WakePlaylist {
        createCalls++
        concurrentMutations += 1
        maxConcurrentMutations = maxOf(maxConcurrentMutations, concurrentMutations)
        try {
            createGate?.await()
            if (failCreate) error("create failed")
            return WakePlaylist("created", name).also { playlists += it }
        } finally {
            concurrentMutations -= 1
        }
    }

    override suspend fun listPlaylists(): List<WakePlaylist> {
        if (failListPlaylists) error("refresh failed")
        return if (playlistRefreshes.isEmpty()) {
            playlists.toList()
        } else {
            playlistRefreshes.removeFirst().await()
        }
    }

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        renameCalls++
        val index = playlists.indexOfFirst { it.id == playlistId }
        playlists[index] = playlists[index].copy(name = name)
    }

    override suspend fun selectPlaylistForWake(playlistId: String) {
        concurrentMutations += 1
        maxConcurrentMutations = maxOf(maxConcurrentMutations, concurrentMutations)
        try {
            yield()
            selectedId = playlistId
        } finally {
            concurrentMutations -= 1
        }
    }

    override suspend fun selectedPlaylistForWake(): WakePlaylist? = playlists.singleOrNull {
        it.id == selectedId
    }

    override suspend fun addTrackToLibrary(title: String, storedPath: String) =
        WakeTrack("track", title, storedPath)

    override suspend fun registerTrackInPlaylist(playlistId: String, track: WakeTrack) =
        error("unused")

    override suspend fun listLibraryTracks(): List<WakeTrack> = emptyList()

    override suspend fun addTrack(playlistId: String, trackId: String) = error("unused")

    override suspend fun removeTrack(playlistId: String, trackId: String) {
        removeCalls += playlistId to trackId
        if (failNextRemove) {
            failNextRemove = false
            error("remove failed")
        }
    }

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) {
        moveCalls += Triple(playlistId, trackId, position)
    }

    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> = entries.filter {
        it.playlistId == playlistId
    }
}
