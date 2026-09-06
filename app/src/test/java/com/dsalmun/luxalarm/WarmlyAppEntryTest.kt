/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarmlyAppEntryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun use24HourTime() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    @Test
    fun emptyStoreStartsWithTheWarmlySoloWelcomeExperience() {
        setContent(FakeSleepPlanStore())

        composeRule.onNodeWithText("Wake gently. Start your day warmly.").assertIsDisplayed()
        composeRule.onNodeWithText("Create my first sleep plan").assertIsDisplayed()
    }

    @Test
    fun savedPlanStartsOnItsHomeSummary() {
        setContent(
            FakeSleepPlanStore(
                SleepPlan(
                    wakeMinutes = 7 * 60 + 15,
                    bedtimeMinutes = 23 * 60,
                    bedtimeDayOffset = -1,
                )
            )
        )

        composeRule.onNodeWithText("Tomorrow’s wake time").assertIsDisplayed()
        composeRule.onNodeWithText("07:15").assertIsDisplayed()
        composeRule.onNodeWithText("Bedtime · 23:00").assertIsDisplayed()
        composeRule.onNodeWithText("Create my first sleep plan").assertDoesNotExist()
    }

    @Test
    fun homeSummarizesTheSelectedWakePlaylist() {
        val playlistStore =
            FakeAppWakePlaylistStore(
                playlists = listOf(WakePlaylist("morning", "Morning calm")),
                selectedId = "morning",
            )
        setContent(
            FakeSleepPlanStore(SleepPlan(7 * 60, 23 * 60, -1)),
            playlistStore,
        )

        composeRule.onNodeWithText("Wake music · Morning calm").assertIsDisplayed()
    }

    @Test
    fun savedPlanOpensTheMusicPlaylistRoute() {
        setContent(
            FakeSleepPlanStore(
                SleepPlan(
                    wakeMinutes = 7 * 60 + 15,
                    bedtimeMinutes = 23 * 60,
                    bedtimeDayOffset = -1,
                )
            )
        )

        composeRule.onNodeWithText("Music").performClick()

        composeRule.onNodeWithText("Wake playlists").assertIsDisplayed()
    }

    @Test
    fun savedStateRestorationKeepsTheMusicRouteVisible() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                LuxAlarmApp(FakeSleepPlanStore(SleepPlan(7 * 60, 23 * 60, -1)))
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Music").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Music").performClick()
        composeRule.onNodeWithText("Wake playlists").assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Wake playlists").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Wake playlists").assertIsDisplayed()
        composeRule.onNodeWithText("Music").assertDoesNotExist()
    }

    @Test
    fun returningFromMusicReloadsTheCommittedSelection() {
        val store =
            FakeAppWakePlaylistStore(
                playlists =
                    listOf(
                        WakePlaylist("morning", "Morning calm"),
                        WakePlaylist("rain", "Rain"),
                    ),
                selectedId = "morning",
            )
        setContent(FakeSleepPlanStore(SleepPlan(7 * 60, 23 * 60, -1)), store)
        composeRule.onNodeWithText("Music").performClick()
        composeRule.onNodeWithText("Wake playlists").assertIsDisplayed()

        runBlocking { store.selectPlaylistForWake("rain") }
        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithText("Wake music · Rain").assertIsDisplayed()
    }

    @Test
    fun returningFromMusicClosesTheRouteWhenSelectionReloadFails() {
        val store =
            FakeAppWakePlaylistStore(
                playlists = listOf(WakePlaylist("morning", "Morning calm")),
                selectedId = "morning",
            )
        setContent(FakeSleepPlanStore(SleepPlan(7 * 60, 23 * 60, -1)), store)
        composeRule.onNodeWithText("Music").performClick()
        composeRule.onNodeWithText("Wake playlists").assertIsDisplayed()
        composeRule.runOnIdle { store.failSelectionReads = true }

        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithText("Wake playlists").assertDoesNotExist()
        composeRule.onNodeWithText("Wake music · Morning calm").assertIsDisplayed()
    }

    @Test
    fun completingOnboardingSavesAndShowsHome() {
        val store = FakeSleepPlanStore()
        setContent(store)
        composeRule.onNodeWithText("Create my first sleep plan").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("22:45").performClick()

        composeRule.onNodeWithText("Save this sleep plan").performClick()

        composeRule.onNodeWithText("Tomorrow’s wake time").assertIsDisplayed()
        composeRule.onNodeWithText("07:00").assertIsDisplayed()
        composeRule.onNodeWithText("Bedtime · 22:45").assertIsDisplayed()
    }

    private fun setContent(
        store: SleepPlanStore,
        playlistStore: WakePlaylistStore = FakeAppWakePlaylistStore(),
    ) {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) { LuxAlarmApp(store, playlistStore) }
        }
    }
}

private class FakeAppWakePlaylistStore(
    private val playlists: List<WakePlaylist> = emptyList(),
    private var selectedId: String? = null,
) : WakePlaylistStore {
    var failSelectionReads = false

    override suspend fun createPlaylist(name: String) = error("Not needed")

    override suspend fun listPlaylists() = playlists

    override suspend fun renamePlaylist(playlistId: String, name: String) = Unit

    override suspend fun selectPlaylistForWake(playlistId: String) {
        selectedId = playlistId
    }

    override suspend fun selectedPlaylistForWake(): WakePlaylist? {
        if (failSelectionReads) error("selection unavailable")
        return playlists.singleOrNull { it.id == selectedId }
    }

    override suspend fun addTrackToLibrary(title: String, storedPath: String) = error("Not needed")

    override suspend fun registerTrackInPlaylist(playlistId: String, track: WakeTrack) =
        error("Not needed")

    override suspend fun listLibraryTracks() = emptyList<WakeTrack>()

    override suspend fun addTrack(playlistId: String, trackId: String) = error("Not needed")

    override suspend fun removeTrack(playlistId: String, trackId: String) = Unit

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) = Unit

    override suspend fun listEntries(playlistId: String) = emptyList<WakePlaylistEntry>()
}

private class FakeSleepPlanStore(initial: SleepPlan? = null) : SleepPlanStore {
    private var plan = initial

    override suspend fun load(): SleepPlan? = plan

    override suspend fun save(plan: SleepPlan) {
        this.plan = plan
    }
}
