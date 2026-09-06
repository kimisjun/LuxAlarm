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
        failedIndices: Set<Int> = emptySet(),
    ): WakePlaylistPlaybackState {
        var attemptedIndices = failedIndices
        repeat(trackSources.size) { offset ->
            val index = (startIndex + offset) % trackSources.size
            if (index in attemptedIndices) return@repeat
            attemptedIndices = attemptedIndices + index
            val source = trackSources[index] ?: return@repeat
            val failureHistory = attemptedIndices
            val activated =
                activate(
                    source = source,
                    playingState = WakePlaylistPlaybackState.PlayingTrack(index),
                    onCompletion = { playFrom(index + 1) },
                    onError = { playFrom(index + 1, failureHistory) },
                ) ?: return@repeat
            return activated
        }
        return playFallback()
    }

    private fun playFallback(): WakePlaylistPlaybackState {
        val source = fallbackSource ?: return WakePlaylistPlaybackState.Failed
        return activate(
            source = source,
            playingState = WakePlaylistPlaybackState.PlayingFallback,
            onCompletion = null,
            onError = { WakePlaylistPlaybackState.Failed },
        ) ?: WakePlaylistPlaybackState.Failed
    }

    private fun activate(
        source: Source,
        playingState: WakePlaylistPlaybackState,
        onCompletion: (() -> WakePlaylistPlaybackState)?,
        onError: () -> WakePlaylistPlaybackState,
    ): WakePlaylistPlaybackState? {
        val token = Any()
        var committed = false
        var consumed = false
        var pendingEvent: PlayerEvent? = null

        fun transition(event: PlayerEvent): WakePlaylistPlaybackState {
            if (activeToken === token) {
                player?.release()
                player = null
                activeToken = null
            }
            return when (event) {
                PlayerEvent.COMPLETION -> checkNotNull(onCompletion).invoke()
                PlayerEvent.ERROR -> onError()
            }
        }

        fun signal(event: PlayerEvent) {
            if (event == PlayerEvent.COMPLETION && onCompletion == null) return
            if (consumed) return
            if (!committed) {
                if (pendingEvent == null) pendingEvent = event
                return
            }
            if (activeToken !== token) return
            consumed = true
            state = transition(event)
        }

        val created =
            runCatching {
                    playerFactory.create(
                        source = source,
                        initialGain = gain,
                        onCompletion = { signal(PlayerEvent.COMPLETION) },
                        onError = { signal(PlayerEvent.ERROR) },
                    )
                }
                .getOrNull() ?: return null
        activeToken = token
        player = created
        committed = true

        val deferredEvent = pendingEvent ?: return playingState
        consumed = true
        return transition(deferredEvent)
    }

    private enum class PlayerEvent {
        COMPLETION,
        ERROR,
    }
}
