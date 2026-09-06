/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GentleWakePreviewPlaybackControllerTest {
    private val importedUri = Uri.parse("file:///private/selected-audio")
    private val defaultUri = Uri.parse("content://settings/system/alarm_alert")

    @Test
    fun selectedPlaylistPathsStayOrderedAndExcludeMissingSources() {
        val playlist = WakePlaylist(id = "playlist", name = "Morning")
        val entries =
            listOf(
                WakePlaylistEntry(
                    "third-entry",
                    playlist.id,
                    WakeTrack("third", "Third", "/third"),
                    2,
                ),
                WakePlaylistEntry(
                    "first-entry",
                    playlist.id,
                    WakeTrack("first", "First", "/first"),
                    0,
                ),
                WakePlaylistEntry(
                    "missing-entry",
                    playlist.id,
                    WakeTrack("missing", "Missing", "/missing"),
                    1,
                ),
            )

        val paths =
            previewAudioPaths(
                selectedPlaylist = playlist,
                entries = entries,
                legacyImportedPath = "/legacy",
                isLocalFile = { it != "/missing" },
            )

        assertEquals(listOf("/first", "/third"), paths)
    }

    @Test
    fun selectedPlaylistStartsInItsPersistedOrder() {
        val secondImportedUri = Uri.parse("file:///private/second-selected-audio")
        val factory = RecordingFactory()
        val controller =
            GentleWakePreviewPlaybackController(
                playlistAudioUris = listOf(importedUri, secondImportedUri),
                defaultAlarmUri = defaultUri,
                playerFactory = factory,
            )

        val state = controller.start(progress = 0.5f)

        assertEquals(GentleWakePreviewPlaybackState.PlayingImported, state)
        assertEquals(listOf(importedUri), factory.requestedUris)
    }

    @Test
    fun playlistCompletionAdvancesThroughThePreviewController() {
        val secondImportedUri = Uri.parse("file:///private/second-selected-audio")
        val factory = RecordingFactory()
        val states = mutableListOf<GentleWakePreviewPlaybackState>()
        val controller =
            GentleWakePreviewPlaybackController(
                playlistAudioUris = listOf(importedUri, secondImportedUri),
                defaultAlarmUri = defaultUri,
                playerFactory = factory,
                onStateChange = states::add,
            )
        controller.start(progress = 0.5f)

        factory.players.single().complete()

        assertEquals(listOf(importedUri, secondImportedUri), factory.requestedUris)
        assertEquals(GentleWakePreviewPlaybackState.PlayingImported, states.last())
    }

    @Test
    fun selectedImportedMusicStartsFirstAtTheRampVolume() {
        val factory = RecordingFactory()
        val controller = GentleWakePreviewPlaybackController(importedUri, defaultUri, factory)

        val state = controller.start(progress = 0.5f)

        assertEquals(GentleWakePreviewPlaybackState.PlayingImported, state)
        assertEquals(listOf(importedUri), factory.requestedUris)
        assertEquals(WakeRamp.frameAt(0.5f).audioVolume, factory.players.single().initialVolume)
    }

    @Test
    fun importedCreationFailureFallsBackToTheDefaultAlarm() {
        val factory = RecordingFactory(failingUris = setOf(importedUri))
        val controller = GentleWakePreviewPlaybackController(importedUri, defaultUri, factory)

        val state = controller.start(progress = 0.25f)

        assertEquals(GentleWakePreviewPlaybackState.PlayingFallback, state)
        assertEquals(listOf(importedUri, defaultUri), factory.requestedUris)
        assertEquals(
            WakeRamp.frameAt(0.25f).audioVolume,
            factory.players.single().initialVolume,
        )
    }

    @Test
    fun absentImportedMusicUsesTheDefaultWithoutReportingAFailure() {
        val factory = RecordingFactory()
        val controller = GentleWakePreviewPlaybackController(null, defaultUri, factory)

        val state = controller.start(progress = 0f)

        assertEquals(GentleWakePreviewPlaybackState.PlayingDefault, state)
        assertEquals(listOf(defaultUri), factory.requestedUris)
    }

    @Test
    fun everyCreationFailureIsReported() {
        val factory = RecordingFactory(failingUris = setOf(importedUri, defaultUri))
        val controller = GentleWakePreviewPlaybackController(importedUri, defaultUri, factory)

        val state = controller.start(progress = 0f)

        assertEquals(GentleWakePreviewPlaybackState.Failed, state)
    }

    @Test
    fun progressUpdatesUseTheWakeRampWithoutRecreatingThePlayer() {
        val factory = RecordingFactory()
        val controller = GentleWakePreviewPlaybackController(importedUri, defaultUri, factory)
        controller.start(progress = 0f)

        controller.updateProgress(0.75f)

        assertEquals(listOf(importedUri), factory.requestedUris)
        assertEquals(
            listOf(WakeRamp.frameAt(0.75f).audioVolume),
            factory.players.single().volumeUpdates,
        )
    }

    @Test
    fun closeStopsAndReleasesThePlayerOnlyOnce() {
        val factory = RecordingFactory()
        val controller = GentleWakePreviewPlaybackController(importedUri, defaultUri, factory)
        controller.start(progress = 0f)

        controller.close()
        controller.close()

        val player = factory.players.single()
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
    }

    private class RecordingFactory(private val failingUris: Set<Uri> = emptySet()) :
        GentleWakePreviewPlayerFactory {
        val requestedUris = mutableListOf<Uri>()
        val players = mutableListOf<RecordingPlayer>()

        override fun create(
            uri: Uri,
            initialVolume: Float,
            looping: Boolean,
            onCompletion: () -> Unit,
            onError: () -> Unit,
        ): GentleWakePreviewPlayer? {
            requestedUris += uri
            if (uri in failingUris) return null
            return RecordingPlayer(initialVolume, onCompletion, onError).also(players::add)
        }
    }

    private class RecordingPlayer(
        val initialVolume: Float,
        private val onCompletion: () -> Unit,
        private val onError: () -> Unit,
    ) : GentleWakePreviewPlayer {
        val volumeUpdates = mutableListOf<Float>()
        var stopCount = 0
        var releaseCount = 0

        fun complete() = onCompletion()

        fun fail() = onError()

        override fun setVolume(volume: Float) {
            volumeUpdates += volume
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            releaseCount++
        }
    }

    private lateinit var context: Context
    private val createdPlayers = mutableListOf<MediaPlayer>()

    @Before
    fun setUpAndroidFactory() {
        context = ApplicationProvider.getApplicationContext()
        ShadowMediaPlayer.setCreateListener { player, _ -> createdPlayers += player }
    }

    @After
    fun tearDownAndroidFactory() {
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test
    fun androidFactoryInstallsCallbacksOnTheStartedPlayer() {
        val importedFile =
            File(context.filesDir, "gentle-wake-audio/callback-track").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val fileUri = Uri.fromFile(importedFile)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, fileUri),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )
        var completionCount = 0
        var errorCount = 0

        val previewPlayer =
            AndroidGentleWakePreviewPlayerFactory(context)
                .create(
                    uri = fileUri,
                    initialVolume = 0.2f,
                    looping = false,
                    onCompletion = { completionCount++ },
                    onError = { errorCount++ },
                )

        assertNotNull(previewPlayer)
        val shadowPlayer = shadowOf(createdPlayers.single())
        assertNotNull(shadowPlayer.onCompletionListener)
        shadowPlayer.invokeCompletionListener()
        shadowPlayer.invokeErrorListener(1, 1)
        assertEquals(1, completionCount)
        assertEquals(1, errorCount)
        previewPlayer.release()
    }

    @Test
    fun androidFactoryCanCreateANonLoopingPlayerForPlaylistAdvance() {
        val importedFile =
            File(context.filesDir, "gentle-wake-audio/playlist-track").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val fileUri = Uri.fromFile(importedFile)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, fileUri),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )

        val previewPlayer =
            AndroidGentleWakePreviewPlayerFactory(context)
                .create(fileUri, initialVolume = 0.2f, looping = false)

        assertNotNull(previewPlayer)
        assertFalse(createdPlayers.single().isLooping)
        previewPlayer.stop()
        previewPlayer.release()
    }

    @Test
    fun androidFactoryLoopsTheFileAtPlayerGainWithoutChangingTheAlarmStream() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        val before = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val importedFile =
            File(context.filesDir, "gentle-wake-audio/selected-audio").apply {
                parentFile!!.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
        val fileUri = Uri.fromFile(importedFile)
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(context, fileUri),
            ShadowMediaPlayer.MediaInfo(5_000, 0),
        )

        val previewPlayer =
            AndroidGentleWakePreviewPlayerFactory(context).create(fileUri, initialVolume = 0.2f)

        assertNotNull(previewPlayer)
        val mediaPlayer = createdPlayers.single()
        assertEquals(fileUri, shadowOf(mediaPlayer).sourceUri)
        assertTrue(mediaPlayer.isLooping)
        assertTrue(mediaPlayer.isPlaying)
        assertEquals(0.2f, shadowOf(mediaPlayer).leftVolume)
        assertEquals(before, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        previewPlayer.stop()
        previewPlayer.release()
    }
}
