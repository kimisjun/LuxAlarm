/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File

internal interface GentleWakePreviewPlayer {
    fun setVolume(volume: Float)

    fun stop()

    fun release()
}

internal fun interface GentleWakePreviewPlayerFactory {
    fun create(uri: Uri, initialVolume: Float): GentleWakePreviewPlayer?
}

internal sealed interface GentleWakePreviewPlaybackState {
    data object PlayingImported : GentleWakePreviewPlaybackState

    data object PlayingDefault : GentleWakePreviewPlaybackState

    data object PlayingFallback : GentleWakePreviewPlaybackState

    data object Failed : GentleWakePreviewPlaybackState
}

/** Keeps source selection and player lifetime independent of Compose and Android construction. */
internal class GentleWakePreviewPlaybackController(
    private val importedAudioUri: Uri?,
    private val defaultAlarmUri: Uri?,
    private val playerFactory: GentleWakePreviewPlayerFactory,
) {
    private var player: GentleWakePreviewPlayer? = null
    private var closed = false

    fun start(progress: Float): GentleWakePreviewPlaybackState {
        check(player == null && !closed) { "Preview playback can only be started once" }
        val volume = WakeRamp.frameAt(progress).audioVolume

        if (importedAudioUri != null) {
            player = playerFactory.create(importedAudioUri, volume)
            if (player != null) return GentleWakePreviewPlaybackState.PlayingImported

            player = defaultAlarmUri?.let { playerFactory.create(it, volume) }
            return if (player != null) {
                GentleWakePreviewPlaybackState.PlayingFallback
            } else {
                GentleWakePreviewPlaybackState.Failed
            }
        }

        player = defaultAlarmUri?.let { playerFactory.create(it, volume) }
        return if (player != null) {
            GentleWakePreviewPlaybackState.PlayingDefault
        } else {
            GentleWakePreviewPlaybackState.Failed
        }
    }

    fun updateProgress(progress: Float) {
        player?.setVolume(WakeRamp.frameAt(progress).audioVolume)
    }

    fun close() {
        if (closed) return
        closed = true
        player?.let { activePlayer ->
            runCatching { activePlayer.stop() }
            runCatching { activePlayer.release() }
        }
        player = null
    }
}

/**
 * Creates a looping player at local gain only; it never writes to an [android.media.AudioManager].
 */
internal class AndroidGentleWakePreviewPlayerFactory(context: Context) :
    GentleWakePreviewPlayerFactory {
    private val appContext = context.applicationContext

    override fun create(uri: Uri, initialVolume: Float): GentleWakePreviewPlayer? {
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
                    isLooping = true
                    prepare()
                    val volume = initialVolume.coerceIn(0f, 1f)
                    setVolume(volume, volume)
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val importedAudioPath = AppContainer.settingsManager.getWakeProfile().importedAudioPath
    val importedAudioUri =
        remember(importedAudioPath) {
            importedAudioPath?.let(::File)?.takeIf { it.isFile }?.let(Uri::fromFile)
        }
    val defaultAlarmUri = remember { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
    val playerFactory = remember(context) { AndroidGentleWakePreviewPlayerFactory(context) }

    GentleWakePreviewRoute(
        progress = progress,
        onProgressChange = onProgressChange,
        onAwake = onAwake,
        importedAudioUri = importedAudioUri,
        defaultAlarmUri = defaultAlarmUri,
        playerFactory = playerFactory,
        modifier = modifier,
    )
}

/** Injectable boundary used by tests and by the production route above. */
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
    val controller =
        remember(importedAudioUri, defaultAlarmUri, playerFactory) {
            GentleWakePreviewPlaybackController(
                importedAudioUri = importedAudioUri,
                defaultAlarmUri = defaultAlarmUri,
                playerFactory = playerFactory,
            )
        }
    var playbackState by
        remember(controller) {
            mutableStateOf<GentleWakePreviewPlaybackState?>(null)
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

private fun GentleWakePreviewPlaybackState?.statusText(): String? =
    when (this) {
        GentleWakePreviewPlaybackState.PlayingFallback -> "가져온 음악 재생 실패 · 기본 알람 소리 재생 중"
        GentleWakePreviewPlaybackState.Failed -> "미리보기 소리를 재생할 수 없어요"
        else -> null
    }
