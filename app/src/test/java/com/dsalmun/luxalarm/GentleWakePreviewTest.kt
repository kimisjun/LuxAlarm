/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dsalmun.luxalarm.ui.theme.LuxAlarmTheme
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GentleWakePreviewTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun halfProgressUsesTheRampAndOffersALargeConfirmation() {
        var confirmations = 0
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(progress = 0.5f, onAwake = { confirmations++ })
            }
        }

        composeRule.onNodeWithText("Time to wake gently").assertIsDisplayed()
        composeRule.onNodeWithText("Progress 50% · Screen 51% · Music 20%").assertIsDisplayed()
        composeRule.onNodeWithTag("gentle-wake-preview").assertIsDisplayed()
        composeRule
            .onNodeWithText("I'm awake")
            .assertWidthIsAtLeast(240.dp)
            .assertHeightIsAtLeast(64.dp)
            .performClick()

        assertEquals(1, confirmations)
    }

    @Test
    fun previewUsesEnglishResourcesInTheBaseLocale() {
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(progress = 0.5f, onAwake = {})
            }
        }

        composeRule.onNodeWithText("Time to wake gently").assertIsDisplayed()
        composeRule.onNodeWithText("Progress 50% · Screen 51% · Music 20%").assertIsDisplayed()
        composeRule.onNodeWithText("Preview progress").assertIsDisplayed()
        composeRule.onNodeWithText("I'm awake").assertIsDisplayed()
    }

    @Test
    fun previewResourcesIncludeKoreanTranslations() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration =
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(Locale.KOREAN)
            }
        val localized = context.createConfigurationContext(configuration)

        assertEquals("부드럽게 깨어날 시간이에요", localized.getString(R.string.warmly_preview_title))
        assertEquals("일어났어요", localized.getString(R.string.warmly_preview_awake))
        assertEquals(
            "미리보기 오디오 불러오는 중…",
            localized.getString(R.string.warmly_preview_loading),
        )
        assertEquals(
            "가져온 음악 재생 실패 · 기본 알람 소리 재생 중",
            localized.getString(R.string.warmly_preview_fallback),
        )
        assertEquals(
            "미리보기 소리를 재생할 수 없어요",
            localized.getString(R.string.warmly_preview_failed),
        )
        assertEquals("가져온 오디오", localized.getString(R.string.warmly_imported_audio_title))
    }

    @Test
    fun missingPlaylistFallbackHasAKoreanTranslation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration =
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(Locale.KOREAN)
            }
        val localized = context.createConfigurationContext(configuration)

        assertEquals(
            "플레이리스트 오디오 파일을 사용할 수 없음 · 기본 알람 소리 재생 중",
            localized.getString(R.string.warmly_preview_missing_fallback),
        )
    }

    @Test
    fun progressControlScrubsThroughDeterministicRampFrames() {
        val progress = mutableFloatStateOf(0f)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreview(
                    progress = progress.floatValue,
                    onProgressChange = { progress.floatValue = it },
                    onAwake = {},
                )
            }
        }

        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }

        composeRule.onNodeWithText("Progress 75% · Screen 85% · Music 30%").assertIsDisplayed()
    }

    @Test
    fun routePreviewsAnOrderedPlaylistFromItsFirstTrack() {
        val firstUri = Uri.parse("file:///private/first")
        val secondUri = Uri.parse("file:///private/second")
        val factory = PreviewRecordingFactory()
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistAudioUris = listOf(firstUri, secondUri),
                    defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                    playerFactory = factory,
                )
            }
        }

        composeRule.runOnIdle { assertEquals(listOf(firstUri), factory.requestedUris) }
    }

    @Test
    fun resolvingRouteCreatesNoPlayerUntilStoreResolutionThenStartsOnlySelectedFirstSource() {
        val selected = WakePlaylist("selected", "Morning")
        val selection = CompletableDeferred<WakePlaylist?>()
        val firstUri = Uri.parse("file:///private/first")
        val secondUri = Uri.parse("file:///private/second")
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                selected,
                listOf(
                    WakePlaylistEntry(
                        "second",
                        selected.id,
                        WakeTrack("track-2", "Second", secondUri.path!!),
                        1,
                    ),
                    WakePlaylistEntry(
                        "first",
                        selected.id,
                        WakeTrack("track-1", "First", firstUri.path!!),
                        0,
                    ),
                ),
            )
        val factory = PreviewRecordingFactory()
        val globalStore =
            PreviewDeferredPlaylistStore(
                CompletableDeferred(),
                WakePlaylist("global", "Wrong"),
                emptyList(),
            )
        AppContainer.wakePlaylistStore = globalStore

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                    playerFactory = factory,
                )
            }
        }

        composeRule.onNodeWithText("Loading preview audio…").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList(), factory.requestedUris) }

        selection.complete(selected)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf(firstUri), factory.requestedUris)
            assertEquals(1, store.selectionCalls)
            assertEquals(0, globalStore.selectionCalls)
        }
    }

    @Test
    fun explicitEditorPlaylistBypassesSlowSelectionAndPreviewsItsOwnFirstTrack() {
        val editor = WakePlaylist("editor", "Editor")
        val editorUri = Uri.parse("file:///private/editor")
        val store =
            PreviewDeferredPlaylistStore(
                CompletableDeferred(),
                editor,
                listOf(
                    WakePlaylistEntry(
                        "editor-entry",
                        editor.id,
                        WakeTrack("editor-track", "Editor track", editorUri.path!!),
                        0,
                    )
                ),
            )
        val factory = PreviewRecordingFactory()

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    playlistId = editor.id,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                    playerFactory = factory,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf(editorUri), factory.requestedUris)
            assertEquals(0, store.selectionCalls)
        }
    }

    @Test
    fun selectedPlaylistWithOnlyMissingFilesPlaysDefaultWithTruthfulFallbackStatus() {
        val selected = WakePlaylist("selected", "Morning")
        val missingPath = "/private/missing"
        val store =
            PreviewDeferredPlaylistStore(
                CompletableDeferred(selected),
                selected,
                listOf(
                    WakePlaylistEntry(
                        "entry",
                        selected.id,
                        WakeTrack("track", "Missing", missingPath),
                        0,
                    )
                ),
            )
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory()

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = null,
                    isLocalFile = { it != missingPath },
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Playlist audio files are unavailable · Playing default alarm sound")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(defaultUri), factory.requestedUris) }
    }

    @Test
    fun leavingBeforePlaylistResolutionNeverStartsPlayback() {
        val selected = WakePlaylist("selected", "Morning")
        val selection = CompletableDeferred<WakePlaylist?>()
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                selected,
                listOf(
                    WakePlaylistEntry(
                        "entry",
                        selected.id,
                        WakeTrack("track", "First", "/private/first"),
                        0,
                    )
                ),
            )
        val factory = PreviewRecordingFactory()
        val visible = mutableStateOf(true)

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (visible.value) {
                    GentleWakePreviewRoute(
                        progress = 0f,
                        onAwake = {},
                        playlistStore = store,
                        legacyImportedPath = "/private/legacy",
                        isLocalFile = { true },
                        defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                        playerFactory = factory,
                    )
                }
            }
        }

        composeRule.runOnIdle { visible.value = false }
        selection.complete(selected)
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(emptyList(), factory.requestedUris) }
    }

    @Test
    fun resolutionFailureStartsOnlyTheDefaultFallbackAfterFailure() {
        val selection = CompletableDeferred<WakePlaylist?>()
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                WakePlaylist("unused", "Unused"),
                emptyList(),
            )
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory()

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        composeRule.runOnIdle { assertEquals(emptyList(), factory.requestedUris) }
        selection.completeExceptionally(IllegalStateException("database unavailable"))
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("Imported music failed · Playing default alarm sound")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(defaultUri), factory.requestedUris) }
    }

    @Test
    fun resolutionFailureWithoutADefaultAlarmReportsPlaybackFailure() {
        val selection = CompletableDeferred<WakePlaylist?>()
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                WakePlaylist("unused", "Unused"),
                emptyList(),
            )
        val factory = PreviewRecordingFactory()

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = null,
                    playerFactory = factory,
                )
            }
        }

        selection.completeExceptionally(IllegalStateException("database unavailable"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Unable to play preview audio").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList(), factory.requestedUris) }
    }

    @Test
    fun resolutionFailureWithFallbackCreationFailureReportsPlaybackFailure() {
        val selection = CompletableDeferred<WakePlaylist?>()
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                WakePlaylist("unused", "Unused"),
                emptyList(),
            )
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory(failAll = true)

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        selection.completeExceptionally(IllegalStateException("database unavailable"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Unable to play preview audio").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(defaultUri), factory.requestedUris) }
    }

    @Test
    fun resolutionFailureFallbackRuntimeErrorReportsPlaybackFailure() {
        val selection = CompletableDeferred<WakePlaylist?>()
        val store =
            PreviewDeferredPlaylistStore(
                selection,
                WakePlaylist("unused", "Unused"),
                emptyList(),
            )
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory()

        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    playlistStore = store,
                    legacyImportedPath = "/private/legacy",
                    isLocalFile = { true },
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        selection.completeExceptionally(IllegalStateException("database unavailable"))
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("Imported music failed · Playing default alarm sound")
            .assertIsDisplayed()

        composeRule.runOnIdle { factory.player.fail() }

        composeRule.onNodeWithText("Unable to play preview audio").assertIsDisplayed()
    }

    @Test
    fun routePlaysTheImportedMusicAndReleasesItWhenThePreviewCloses() {
        val importedUri = Uri.parse("file:///private/selected-audio")
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory()
        val visible = mutableStateOf(true)
        val progress = mutableFloatStateOf(0f)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (visible.value) {
                    GentleWakePreviewRoute(
                        progress = progress.floatValue,
                        onProgressChange = { progress.floatValue = it },
                        onAwake = { visible.value = false },
                        importedAudioUri = importedUri,
                        defaultAlarmUri = defaultUri,
                        playerFactory = factory,
                    )
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(listOf(importedUri), factory.requestedUris)
            assertEquals(WakeRamp.frameAt(0f).audioVolume, factory.player.initialVolume)
            progress.floatValue = 0.75f
        }
        composeRule.runOnIdle {
            assertEquals(WakeRamp.frameAt(0.75f).audioVolume, factory.player.lastVolume)
        }

        composeRule.onNodeWithText("I'm awake").performClick()

        composeRule.runOnIdle {
            assertTrue(factory.player.stopped)
            assertTrue(factory.player.released)
        }
    }

    @Test
    fun routeStopsAndReleasesPlaybackWhenItLeavesComposition() {
        val factory = PreviewRecordingFactory()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                if (visible.value) {
                    GentleWakePreviewRoute(
                        progress = 0f,
                        onAwake = {},
                        importedAudioUri = null,
                        defaultAlarmUri = Uri.parse("content://settings/system/alarm_alert"),
                        playerFactory = factory,
                    )
                }
            }
        }

        composeRule.runOnIdle { visible.value = false }

        composeRule.runOnIdle {
            assertTrue(factory.player.stopped)
            assertTrue(factory.player.released)
        }
    }

    @Test
    fun routeDisplaysTheLocalizedFallbackStateWhenImportedCreationFails() {
        val importedUri = Uri.parse("file:///private/selected-audio")
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory(failingUri = importedUri)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    importedAudioUri = importedUri,
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        composeRule
            .onNodeWithText("Imported music failed · Playing default alarm sound")
            .assertIsDisplayed()
        assertEquals(listOf(importedUri, defaultUri), factory.requestedUris)
    }

    @Test
    fun routeDisplaysTheLocalizedFailureStateWhenNoPlayerCanBeCreated() {
        val defaultUri = Uri.parse("content://settings/system/alarm_alert")
        val factory = PreviewRecordingFactory(failAll = true)
        composeRule.setContent {
            LuxAlarmTheme(dynamicColor = false) {
                GentleWakePreviewRoute(
                    progress = 0f,
                    onAwake = {},
                    importedAudioUri = null,
                    defaultAlarmUri = defaultUri,
                    playerFactory = factory,
                )
            }
        }

        composeRule.onNodeWithText("Unable to play preview audio").assertIsDisplayed()
    }

    private class PreviewRecordingFactory(
        private val failingUri: Uri? = null,
        private val failAll: Boolean = false,
    ) : GentleWakePreviewPlayerFactory {
        val requestedUris = mutableListOf<Uri>()
        val player = PreviewRecordingPlayer()

        override fun create(
            uri: Uri,
            initialVolume: Float,
            looping: Boolean,
            onCompletion: () -> Unit,
            onError: () -> Unit,
        ): GentleWakePreviewPlayer? {
            requestedUris += uri
            if (failAll || uri == failingUri) return null
            player.initialVolume = initialVolume
            player.onError = onError
            return player
        }
    }

    private class PreviewRecordingPlayer : GentleWakePreviewPlayer {
        var initialVolume = Float.NaN
        var lastVolume = Float.NaN
        var stopped = false
        var released = false
        var onError: () -> Unit = {}

        fun fail() = onError()

        override fun setVolume(volume: Float) {
            lastVolume = volume
        }

        override fun stop() {
            stopped = true
        }

        override fun release() {
            released = true
        }
    }
}

private class PreviewDeferredPlaylistStore(
    private val selection: CompletableDeferred<WakePlaylist?>,
    private val expectedPlaylist: WakePlaylist,
    private val entries: List<WakePlaylistEntry>,
) : WakePlaylistStore {
    var selectionCalls = 0
        private set

    override suspend fun selectedPlaylistForWake(): WakePlaylist? {
        selectionCalls++
        return selection.await()
    }

    override suspend fun listEntries(playlistId: String): List<WakePlaylistEntry> {
        assertEquals(expectedPlaylist.id, playlistId)
        return entries
    }

    override suspend fun createPlaylist(name: String): WakePlaylist = error("Not needed")

    override suspend fun listPlaylists(): List<WakePlaylist> = error("Not needed")

    override suspend fun renamePlaylist(playlistId: String, name: String) = error("Not needed")

    override suspend fun selectPlaylistForWake(playlistId: String) = error("Not needed")

    override suspend fun addTrackToLibrary(title: String, storedPath: String): WakeTrack =
        error("Not needed")

    override suspend fun registerTrackInPlaylist(
        playlistId: String,
        track: WakeTrack,
    ): WakePlaylistRegistration = error("Not needed")

    override suspend fun listLibraryTracks(): List<WakeTrack> = error("Not needed")

    override suspend fun addTrack(playlistId: String, trackId: String): WakePlaylistEntry =
        error("Not needed")

    override suspend fun removeTrack(playlistId: String, trackId: String) = error("Not needed")

    override suspend fun moveTrack(playlistId: String, trackId: String, position: Int) =
        error("Not needed")
}
