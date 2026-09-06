/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

internal interface WakePlaylistPlayer {
    fun setGain(gain: Float)

    fun stop()

    fun release()
}

internal interface WakePlaylistPlayerFactory<Source> {
    fun create(
        source: Source,
        initialGain: Float,
        onCompletion: () -> Unit,
        onError: () -> Unit,
    ): WakePlaylistPlayer?
}

internal sealed interface WakePlaylistPlaybackState {
    data class PlayingTrack(val index: Int) : WakePlaylistPlaybackState

    data object PlayingFallback : WakePlaylistPlaybackState

    data object Failed : WakePlaylistPlaybackState
}

/** Platform-neutral ordered playlist player. */
internal class WakePlaylistPlayback<Source>(
    private val trackSources: List<Source?>,
    private val fallbackSource: Source?,
    private val playerFactory: WakePlaylistPlayerFactory<Source>,
    private val onStateChange: (WakePlaylistPlaybackState) -> Unit = {},
) {
    private var player: WakePlaylistPlayer? = null
    private var gain = 0f
    private var activeToken: Any? = null
    private var started = false
    private var closed = false

    var state: WakePlaylistPlaybackState = WakePlaylistPlaybackState.Failed
        private set(value) {
            field = value
            onStateChange(value)
        }

    fun start(initialGain: Float): WakePlaylistPlaybackState {
        check(!started && !closed) { "Playlist playback can only be started once" }
        started = true
        gain = initialGain
        state = playFrom(startIndex = 0)
        return state
    }

    fun updateGain(gain: Float) {
        this.gain = gain
        player?.setGain(gain)
    }

    fun close() {
        if (closed) return
        closed = true
        activeToken = null
        player?.let { activePlayer ->
            runCatching { activePlayer.stop() }
            runCatching { activePlayer.release() }
        }
        player = null
    }

    private fun playFrom(
        startIndex: Int,
        scanCount: Int = trackSources.size,
    ): WakePlaylistPlaybackState {
        repeat(scanCount) { offset ->
            val index = (startIndex + offset) % trackSources.size
            val source = trackSources[index] ?: return@repeat
            val token = Any()
            val created =
                runCatching {
                        playerFactory.create(
                            source = source,
                            initialGain = gain,
                            onCompletion = { advanceAfter(index, token, trackSources.size) },
                            onError = {
                                advanceAfter(
                                    index,
                                    token,
                                    (trackSources.size - 1).coerceAtLeast(0),
                                )
                            },
                        )
                    }
                    .getOrNull() ?: return@repeat
            activeToken = token
            player = created
            return WakePlaylistPlaybackState.PlayingTrack(index)
        }
        return playFallback()
    }

    private fun playFallback(): WakePlaylistPlaybackState {
        val source = fallbackSource ?: return WakePlaylistPlaybackState.Failed
        val token = Any()
        val created =
            runCatching {
                    playerFactory.create(
                        source = source,
                        initialGain = gain,
                        onCompletion = {},
                        onError = { failFallback(token) },
                    )
                }
                .getOrNull() ?: return WakePlaylistPlaybackState.Failed
        activeToken = token
        player = created
        return WakePlaylistPlaybackState.PlayingFallback
    }

    private fun failFallback(token: Any) {
        if (activeToken !== token) return
        player?.release()
        player = null
        activeToken = null
        state = WakePlaylistPlaybackState.Failed
    }

    private fun advanceAfter(index: Int, token: Any, scanCount: Int) {
        if (activeToken !== token) return
        player?.release()
        player = null
        activeToken = null
        state = playFrom(index + 1, scanCount)
    }
}
