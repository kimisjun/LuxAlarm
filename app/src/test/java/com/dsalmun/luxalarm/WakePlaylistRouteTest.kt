/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakePlaylistRouteTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun createDialogRequiresANameAndRefreshesTheCatalog() {
        val store = FakeWakePlaylistStore()
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistRoute(playlistStore = store, usePlatformNameDialog = false)
            }
        }

        composeRule.onNodeWithText("Create playlist").performClick()
        composeRule.onNodeWithText("Create").assertIsNotEnabled()
        composeRule.onNodeWithText("Playlist name").performTextInput("Morning calm")
        composeRule.onNodeWithText("Create").performClick()

        composeRule.onNodeWithText("Morning calm").assertIsDisplayed()
    }

    @Test
    fun catalogSelectionAndEditingAreConnectedToTheStore() {
        val store =
            FakeWakePlaylistStore(
                initialPlaylists = listOf(WakePlaylist("morning", "Morning calm")),
                initialEntries =
                    listOf(
                        WakePlaylistEntry(
                            "entry",
                            "morning",
                            WakeTrack("track", "Birdsong", "/owned/birdsong"),
                            0,
                        )
                    ),
            )
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistRoute(playlistStore = store, usePlatformNameDialog = false)
            }
        }

        composeRule.onNodeWithText("Use for wake").performClick()
        composeRule.onNodeWithText("Selected for wake").assertIsDisplayed()
        composeRule.onNodeWithText("Edit").performClick()

        composeRule.onNodeWithText("Birdsong").assertIsDisplayed()
        composeRule.onNodeWithText("Import songs").assertIsDisplayed()
    }

    @Test
    fun editorMoveRemoveAndOwnedByteDeletionMutateAndRefresh() {
        val first = WakeTrack("first", "First", "/owned/first")
        val second = WakeTrack("second", "Second", "/owned/second")
        val store =
            FakeWakePlaylistStore(
                initialPlaylists = listOf(WakePlaylist("morning", "Morning")),
                initialEntries =
                    listOf(
                        WakePlaylistEntry("one", "morning", first, 0),
                        WakePlaylistEntry("two", "morning", second, 1),
                    ),
            )
        val missingPaths = mutableSetOf<String>()
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistRoute(
                    playlistStore = store,
                    ownedFileExists = { it !in missingPaths },
                    deleteOwnedBytes = { missingPaths.add(it.storedPath) },
                    usePlatformNameDialog = false,
                )
            }
        }

        composeRule.onNodeWithText("Edit").performClick()
        composeRule.onNodeWithContentDescription("Move First down").performClick()
        composeRule.onNodeWithContentDescription("Remove First from playlist").performClick()
        composeRule.onNodeWithContentDescription("Delete owned audio Second").performClick()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("first" to 1), store.moveCalls)
        assertEquals(listOf("first"), store.removeCalls)
        composeRule.onNodeWithText("File missing").assertIsDisplayed()
    }

    @Test
    fun importResultsAreSummarizedPerOutcome() {
        val track = WakeAudioStore.OwnedTrack("id", "id", "/owned/id")
        val entry = WakePlaylistEntry("entry", "playlist", WakeTrack("id", "Song", "/owned/id"), 0)

        val summary =
            listOf(
                    WakePlaylistImportResult.Added("one", track, entry, false),
                    WakePlaylistImportResult.AlreadyInPlaylist("two", track, entry, true),
                    WakePlaylistImportResult.Unsupported("three", "text/plain"),
                    WakePlaylistImportResult.Failed("four", IllegalStateException("broken")),
                )
                .toImportSummary()

        assertEquals(WakePlaylistImportSummaryUi(1, 1, 1, 1), summary)
    }
}

private class FakeWakePlaylistStore(
    initialPlaylists: List<WakePlaylist> = emptyList(),
    initialEntries: List<WakePlaylistEntry> = emptyList(),
) : WakePlaylistStore {
    val moveCalls = mutableListOf<Pair<String, Int>>()
    val removeCalls = mutableListOf<String>()
    private val playlists = initialPlaylists.toMutableList()
    private val entries =
        initialEntries
            .groupByTo(mutableMapOf()) { it.playlistId }
            .mapValuesTo(mutableMapOf()) {
                it.value.toMutableList()
            }
    private var selectedId: String? = null

    override suspend fun createPlaylist(name: String): WakePlaylist =
        WakePlaylist("playlist-${playlists.size}", name).also { playlists += it }

    override suspend fun listPlaylists(): List<WakePlaylist> = playlists.toList()

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        val index = playlists.indexOfFirst { it.id == playlistId }
        playlists[index] = playlists[index].copy(name = name)
    }

    override suspend fun selectPlaylistForWake(playlistId: String) {
        selectedId = playlistId
    }

    override suspend fun selectedPlaylistForWake(): WakePlaylist? = playlists.singleOrNull {
        it.id == selectedId
    }

    override suspend fun addTrackToLibrary(title: String, storedPath: String): WakeTrack =
        WakeTrack("track", title, storedPath)

    override suspend fun registerTrackInPlaylist(
        playlistId: String,
        track: WakeTrack,
    ): WakePlaylistRegistration {
        val list = entries.getOrPut(playlistId) { mutableListOf() }
        val existing = list.singleOrNull { it.track.id == track.id }
        if (existing != null) return WakePlaylistRegistration.AlreadyPresent(existing)
        val entry = WakePlaylistEntry("entry-${list.size}", playlistId, track, list.size)
        list += entry
        return WakePlaylistRegistration.Added(entry)
    }

    override suspend fun listLibraryTracks(): List<WakeTrack> =
        entries.values.flatten().map { it.track }.distinctBy { it.id }

    override suspend fun addTrack(playlistId: String, trackId: String): WakePlaylistEntry =
        error("Not needed")

    override suspend fun removeTrack(playlistId: String, trackId: String) {
        removeCalls += trackId
        entries[playlistId]?.removeAll { it.track.id == trackId }
    }

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) {
        moveCalls += trackId to position
        val list = checkNotNull(entries[playlistId])
        val entry = list.removeAt(list.indexOfFirst { it.track.id == trackId })
        list.add(position, entry)
    }

    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> =
        entries[playlistId].orEmpty().mapIndexed { index, entry -> entry.copy(position = index) }
}
