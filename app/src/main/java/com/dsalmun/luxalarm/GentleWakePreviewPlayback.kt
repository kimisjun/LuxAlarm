/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.io.File
import kotlinx.coroutines.CancellationException

internal interface GentleWakePreviewPlayer {
    fun setVolume(volume: Float)

    fun stop()

    fun release()
}

internal interface GentleWakePreviewPlayerFactory {
    fun create(
        uri: Uri,
        initialVolume: Float,
        looping: Boolean = true,
        onCompletion: () -> Unit = {},
        onError: () -> Unit = {},
    ): GentleWakePreviewPlayer?
}

internal sealed interface GentleWakePreviewPlaybackState {
    data object PlayingImported : GentleWakePreviewPlaybackState

    data object PlayingDefault : GentleWakePreviewPlaybackState

    data object PlayingFallback : GentleWakePreviewPlaybackState

    data object Failed : GentleWakePreviewPlaybackState
}

private sealed interface PreviewAudioResolution {
    data object Loading : PreviewAudioResolution

    data class Ready(val audioUris: List<Uri>) : PreviewAudioResolution

    data object Failed : PreviewAudioResolution
}

internal fun previewAudioPaths(
    selectedPlaylist: WakePlaylist?,
    entries: List<WakePlaylistEntry>,
    legacyImportedPath: String?,
    isLocalFile: (String) -> Boolean,
): List<String?> =
    if (selectedPlaylist == null) {
        listOf(legacyImportedPath?.takeIf(isLocalFile))
    } else {
        entries
            .filter { it.playlistId == selectedPlaylist.id }
            .sortedBy(WakePlaylistEntry::position)
            .mapNotNull { it.track.storedPath.takeIf(isLocalFile) }
    }

/** Keeps source selection and player lifetime independent of Compose and Android construction. */
internal class GentleWakePreviewPlaybackController(
    private val playlistAudioUris: List<Uri?>,
    private val defaultAlarmUri: Uri?,
    playerFactory: GentleWakePreviewPlayerFactory,
    private val onStateChange: (GentleWakePreviewPlaybackState) -> Unit = {},
) {
    constructor(
        importedAudioUri: Uri?,
        defaultAlarmUri: Uri?,
        playerFactory: GentleWakePreviewPlayerFactory,
    ) : this(listOf(importedAudioUri), defaultAlarmUri, playerFactory)

    private val playback =
        WakePlaylistPlayback(
            trackSources = playlistAudioUris,
            fallbackSource = defaultAlarmUri,
            playerFactory =
                object : WakePlaylistPlayerFactory<Uri> {
                    override fun create(
                        source: Uri,
                        initialGain: Float,
                        onCompletion: () -> Unit,
                        onError: () -> Unit,
                    ): WakePlaylistPlayer? =
                        playerFactory
                            .create(
                                uri = source,
                                initialVolume = initialGain,
                                looping = source == defaultAlarmUri,
                                onCompletion = onCompletion,
                                onError = onError,
                            )
                            ?.let { previewPlayer ->
                                object : WakePlaylistPlayer {
                                    override fun setGain(gain: Float) =
                                        previewPlayer.setVolume(gain)

                                    override fun stop() = previewPlayer.stop()

                                    override fun release() = previewPlayer.release()
                                }
                            }
                },
            onStateChange = { onStateChange(it.toPreviewState()) },
        )

    fun start(progress: Float): GentleWakePreviewPlaybackState =
        playback.start(WakeRamp.frameAt(progress).audioVolume).toPreviewState()

    fun updateProgress(progress: Float) {
        playback.updateGain(WakeRamp.frameAt(progress).audioVolume)
    }

    fun close() = playback.close()

    private fun WakePlaylistPlaybackState.toPreviewState(): GentleWakePreviewPlaybackState =
        when (this) {
            is WakePlaylistPlaybackState.PlayingTrack ->
                GentleWakePreviewPlaybackState.PlayingImported
            WakePlaylistPlaybackState.PlayingFallback ->
                if (playlistAudioUris.any { it != null }) {
                    GentleWakePreviewPlaybackState.PlayingFallback
                } else {
                    GentleWakePreviewPlaybackState.PlayingDefault
                }
            WakePlaylistPlaybackState.Failed -> GentleWakePreviewPlaybackState.Failed
        }
}

/**
 * Creates a looping player at local gain only; it never writes to an [android.media.AudioManager].
 */
internal class AndroidGentleWakePreviewPlayerFactory(context: Context) :
    GentleWakePreviewPlayerFactory {
    private val appContext = context.applicationContext

    override fun create(
        uri: Uri,
        initialVolume: Float,
        looping: Boolean,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ): GentleWakePreviewPlayer? {
        var mediaPlayer: MediaPlayer? = null
        return try {
            mediaPlayer =
                MediaPlayer().apply {
                    setDataSource(appContext, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    isLooping = looping
                    prepare()
                    val volume = initialVolume.coerceIn(0f, 1f)
                    setVolume(volume, volume)
                    setOnCompletionListener { onCompletion() }
                    setOnErrorListener { _, _, _ ->
                        onError()
                        true
                    }
                    start()
                }
            AndroidGentleWakePreviewPlayer(mediaPlayer)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create preview player for $uri", e)
            mediaPlayer?.release()
            null
        }
    }

    private companion object {
        const val TAG = "GentleWakePreview"
    }
}

private class AndroidGentleWakePreviewPlayer(private val mediaPlayer: MediaPlayer) :
    GentleWakePreviewPlayer {
    override fun setVolume(volume: Float) {
        val gain = volume.coerceIn(0f, 1f)
        mediaPlayer.setVolume(gain, gain)
    }

    override fun stop() {
        if (mediaPlayer.isPlaying) mediaPlayer.stop()
    }

    override fun release() {
        mediaPlayer.release()
    }
}

/** Production route: resolves persisted app-private music and owns the Android player factory. */
@Composable
internal fun GentleWakePreviewRoute(
    progress: Float,
    onProgressChange: (Float) -> Unit = {},
    onAwake: () -> Unit,
    playlistStore: WakePlaylistStore,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val importedAudioPath = AppContainer.settingsManager.getWakeProfile().importedAudioPath
    val defaultAlarmUri = remember { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
    val playerFactory = remember(context) { AndroidGentleWakePreviewPlayerFactory(context) }

    GentleWakePreviewRoute(
        progress = progress,
        onProgressChange = onProgressChange,
        onAwake = onAwake,
        playlistStore = playlistStore,
        legacyImportedPath = importedAudioPath,
        isLocalFile = { File(it).isFile },
        defaultAlarmUri = defaultAlarmUri,
        playerFactory = playerFactory,
        modifier = modifier,
    )
}

/** Resolves durable selection completely before delegating to the playback-owning route. */
@Composable
internal fun GentleWakePreviewRoute(
    progress: Float,
    onProgressChange: (Float) -> Unit = {},
    onAwake: () -> Unit,
    playlistStore: WakePlaylistStore,
    legacyImportedPath: String?,
    isLocalFile: (String) -> Boolean,
    defaultAlarmUri: Uri?,
    playerFactory: GentleWakePreviewPlayerFactory,
    modifier: Modifier = Modifier,
) {
    var resolution by
        remember(playlistStore, legacyImportedPath) {
            mutableStateOf<PreviewAudioResolution>(PreviewAudioResolution.Loading)
        }

    LaunchedEffect(playlistStore, legacyImportedPath) {
        resolution =
            try {
                val selected = playlistStore.selectedPlaylistForWake()
                val entries = selected?.let { playlistStore.listEntries(it.id) }.orEmpty()
                val paths = previewAudioPaths(selected, entries, legacyImportedPath, isLocalFile)
                PreviewAudioResolution.Ready(
                    paths.mapNotNull { path -> path?.let(::File)?.let(Uri::fromFile) }
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                PreviewAudioResolution.Failed
            }
    }

    when (val current = resolution) {
        PreviewAudioResolution.Loading ->
            GentleWakePreview(
                progress = progress,
                onProgressChange = onProgressChange,
                onAwake = onAwake,
                playbackStatus = stringResource(R.string.warmly_preview_loading),
                modifier = modifier,
            )
        is PreviewAudioResolution.Ready ->
            GentleWakePreviewRoute(
                progress = progress,
                onProgressChange = onProgressChange,
                onAwake = onAwake,
                playlistAudioUris = current.audioUris,
                defaultAlarmUri = defaultAlarmUri,
                playerFactory = playerFactory,
                modifier = modifier,
            )
        PreviewAudioResolution.Failed ->
            GentleWakePreviewRoute(
                progress = progress,
                onProgressChange = onProgressChange,
                onAwake = onAwake,
                playlistAudioUris = emptyList(),
                defaultAlarmUri = defaultAlarmUri,
                playerFactory = playerFactory,
                modifier = modifier,
            )
    }
}

/** Legacy injectable boundary retained for single imported-file previews. */
@Composable
internal fun GentleWakePreviewRoute(
    progress: Float,
    onProgressChange: (Float) -> Unit = {},
    onAwake: () -> Unit,
    importedAudioUri: Uri?,
    defaultAlarmUri: Uri?,
    playerFactory: GentleWakePreviewPlayerFactory,
    modifier: Modifier = Modifier,
) {
    GentleWakePreviewRoute(
        progress = progress,
        onProgressChange = onProgressChange,
        onAwake = onAwake,
        playlistAudioUris = listOf(importedAudioUri),
        defaultAlarmUri = defaultAlarmUri,
        playerFactory = playerFactory,
        modifier = modifier,
    )
}

/** Injectable boundary for an ordered playlist preview. */
@Composable
internal fun GentleWakePreviewRoute(
    progress: Float,
    onProgressChange: (Float) -> Unit = {},
    onAwake: () -> Unit,
    playlistAudioUris: List<Uri?>,
    defaultAlarmUri: Uri?,
    playerFactory: GentleWakePreviewPlayerFactory,
    modifier: Modifier = Modifier,
) {
    var playbackState by
        remember(playlistAudioUris, defaultAlarmUri, playerFactory) {
            mutableStateOf<GentleWakePreviewPlaybackState?>(null)
        }
    val controller =
        remember(playlistAudioUris, defaultAlarmUri, playerFactory) {
            GentleWakePreviewPlaybackController(
                playlistAudioUris = playlistAudioUris,
                defaultAlarmUri = defaultAlarmUri,
                playerFactory = playerFactory,
                onStateChange = { playbackState = it },
            )
        }

    DisposableEffect(controller) {
        playbackState = controller.start(progress)
        onDispose { controller.close() }
    }
    SideEffect { controller.updateProgress(progress) }

    GentleWakePreview(
        progress = progress,
        onProgressChange = onProgressChange,
        onAwake = {
            controller.close()
            onAwake()
        },
        playbackStatus = playbackState.statusText(),
        modifier = modifier,
    )
}

@Composable
private fun GentleWakePreviewPlaybackState?.statusText(): String? =
    when (this) {
        GentleWakePreviewPlaybackState.PlayingFallback ->
            stringResource(R.string.warmly_preview_fallback)
        GentleWakePreviewPlaybackState.Failed -> stringResource(R.string.warmly_preview_failed)
        else -> null
    }
