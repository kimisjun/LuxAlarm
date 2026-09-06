/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.util.Locale
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class WakePlaylistScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun koreanLocaleUsesRequestedPrimaryActionLabel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration =
            Configuration(context.resources.configuration).apply {
                setLocale(Locale.KOREAN)
            }
        val localized = context.createConfigurationContext(configuration)

        assertEquals("플레이리스트 만들기", localized.getString(R.string.warmly_playlist_create))
    }

    @Test
    fun primaryActionRemainsReachableAt390By844WithLargeText() {
        var createCalls = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(390.dp, 844.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                LuxAlarmTheme(dynamicColor = false) {
                    WakePlaylistScreen(
                        state =
                            WakePlaylistScreenState(
                                playlists =
                                    List(6) { index ->
                                        WakePlaylistItemUi("$index", "Playlist number $index")
                                    }
                            ),
                        onCreatePlaylist = { createCalls += 1 },
                    )
                }
            }
        }

        composeRule
            .onNodeWithText("Create playlist")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, createCalls)
    }

    @Test
    fun partialImportSummaryShowsEveryOutcome() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state =
                        WakePlaylistScreenState(
                            importSummary =
                                WakePlaylistImportSummaryUi(
                                    added = 3,
                                    duplicates = 2,
                                    unsupported = 1,
                                    failed = 4,
                                )
                        ),
                    onCreatePlaylist = {},
                )
            }
        }

        composeRule
            .onNodeWithText(
                "Import results · Added: 3 · Duplicates: 2 · Unsupported: 1 · Failed: 4"
            )
            .assertIsDisplayed()
    }

    @Test
    fun trackActionsHaveUnambiguousAccessibleNames() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state =
                        WakePlaylistScreenState(
                            editor =
                                WakePlaylistEditorUi(
                                    "list",
                                    "Morning calm",
                                    listOf(
                                        WakePlaylistTrackUi("one", "First song"),
                                        WakePlaylistTrackUi(
                                            "gone",
                                            "Missing song",
                                            isMissing = true,
                                        ),
                                    ),
                                )
                        ),
                    onCreatePlaylist = {},
                )
            }
        }

        listOf(
                "Move First song down",
                "Remove First song from playlist",
                "Delete owned audio First song",
                "Find Missing song",
                "Replace Missing song",
                "Move Missing song up",
                "Move Missing song down",
                "Remove Missing song from playlist",
            )
            .forEach { description ->
                composeRule.onNodeWithContentDescription(description).assertExists()
            }
    }

    @Test
    fun editorActionsReportTrackIdsWithoutOwningState() {
        val actions = mutableListOf<String>()
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state =
                        WakePlaylistScreenState(
                            editor =
                                WakePlaylistEditorUi(
                                    "list",
                                    "Morning calm",
                                    listOf(
                                        WakePlaylistTrackUi("one", "First song"),
                                        WakePlaylistTrackUi(
                                            "gone",
                                            "Missing song",
                                            isMissing = true,
                                        ),
                                        WakePlaylistTrackUi("three", "Last song"),
                                    ),
                                )
                        ),
                    onCreatePlaylist = {},
                    onMoveTrackDown = { actions += "down:$it" },
                    onMoveTrackUp = { actions += "up:$it" },
                    onRemoveFromPlaylist = { actions += "remove:$it" },
                    onDeleteOwnedAudio = { actions += "delete:$it" },
                    onFindMissingTrack = { actions += "find:$it" },
                    onReplaceMissingTrack = { actions += "replace:$it" },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Move First song down")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithContentDescription("Move Last song up")
            .performScrollTo()
            .performClick()
        composeRule.onAllNodesWithText("Remove from playlist")[0].performScrollTo().performClick()
        composeRule.onAllNodesWithText("Delete owned audio")[0].performScrollTo().performClick()
        composeRule.onNodeWithText("Find").performScrollTo().performClick()
        composeRule.onNodeWithText("Replace").performScrollTo().performClick()
        composeRule.onAllNodesWithText("Remove from playlist")[1].performScrollTo().performClick()

        assertEquals(
            listOf(
                "down:one",
                "up:three",
                "remove:one",
                "delete:one",
                "find:gone",
                "replace:gone",
                "remove:gone",
            ),
            actions,
        )
    }

    @Test
    fun editorPreservesTrackOrderAndKeepsMissingTracksVisible() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state =
                        WakePlaylistScreenState(
                            editor =
                                WakePlaylistEditorUi(
                                    id = "morning",
                                    name = "Morning calm",
                                    tracks =
                                        listOf(
                                            WakePlaylistTrackUi("one", "First song"),
                                            WakePlaylistTrackUi(
                                                "gone",
                                                "Missing song",
                                                isMissing = true,
                                            ),
                                            WakePlaylistTrackUi("three", "Last song"),
                                        ),
                                )
                        ),
                    onCreatePlaylist = {},
                )
            }
        }

        val titles =
            composeRule
                .onAllNodes(hasTestTag("playlist-track-title"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.Text].single().text }
        assertEquals(listOf("First song", "Missing song", "Last song"), titles)
        composeRule.onNodeWithText("Missing song").assertIsNotEnabled()
        composeRule
            .onNodeWithContentDescription("Reorder Missing song")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("File missing").assertIsDisplayed()
        composeRule.onNodeWithText("Find").assertIsDisplayed()
        composeRule.onNodeWithText("Replace").assertIsDisplayed()
        composeRule.onAllNodesWithText("Remove from playlist").assertCountEquals(3)
    }

    @Test
    fun catalogExposesSelectionAndRenameCallbacks() {
        var selectedId: String? = null
        var renamedId: String? = null
        var editedId: String? = null
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state =
                        WakePlaylistScreenState(
                            playlists =
                                listOf(
                                    WakePlaylistItemUi("morning", "Morning calm"),
                                    WakePlaylistItemUi("rain", "Rainy day"),
                                ),
                            selectedForWakeId = "morning",
                        ),
                    onCreatePlaylist = {},
                    onSelectForWake = { selectedId = it },
                    onRenamePlaylist = { renamedId = it },
                    onEditPlaylist = { editedId = it },
                )
            }
        }

        composeRule.onNodeWithText("Morning calm").assertIsDisplayed()
        composeRule.onNodeWithText("Rainy day").assertIsDisplayed()
        composeRule.onNodeWithText("Selected for wake").assertIsDisplayed()
        composeRule.onNodeWithText("Use for wake").performClick()
        composeRule.onNodeWithContentDescription("Rename Morning calm").performClick()
        composeRule.onNodeWithContentDescription("Edit Morning calm").performClick()

        assertEquals("rain", selectedId)
        assertEquals("morning", renamedId)
        assertEquals("morning", editedId)
    }

    @Test
    fun emptyCatalogOffersPlaylistCreation() {
        var createCalls = 0
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                WakePlaylistScreen(
                    state = WakePlaylistScreenState(),
                    onCreatePlaylist = { createCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("No playlists yet").assertIsDisplayed()
        composeRule.onNodeWithText("Create playlist").performClick()

        assertEquals(1, createCalls)
    }
}
