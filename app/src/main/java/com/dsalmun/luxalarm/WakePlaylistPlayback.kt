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
    private var drainingTransitions = false
    private val pendingTransitions = ArrayDeque<Transition>()

    var state: WakePlaylistPlaybackState = WakePlaylistPlaybackState.Failed
        private set(value) {
            field = value
            onStateChange(value)
        }

    fun start(initialGain: Float): WakePlaylistPlaybackState {
        check(!started && !closed) { "Playlist playback can only be started once" }
        started = true
        gain = initialGain
        enqueue(Transition.Advance(startIndex = 0, failedIndices = emptySet()))
        return state
    }

    fun updateGain(gain: Float) {
        this.gain = gain
        player?.setGain(gain)
    }

    fun close() {
        if (closed) return
        closed = true
        pendingTransitions.clear()
        releaseActivePlayer(stopFirst = true)
    }

    /**
     * Drains reentrant player events iteratively. An external callback starts a new drain, while
     * callbacks raised inside that drain share one index-attempt budget and settle on fallback (or
     * [WakePlaylistPlaybackState.Failed]) instead of wrapping recursively.
     */
    private fun enqueue(transition: Transition) {
        if (closed) return
        pendingTransitions.addLast(transition)
        if (drainingTransitions) return

        drainingTransitions = true
        val attemptedIndices = mutableSetOf<Int>()
        var settledState = state
        try {
            while (!closed) {
                while (pendingTransitions.isNotEmpty() && !closed) {
                    when (val next = pendingTransitions.removeFirst()) {
                        is Transition.Advance -> {
                            if (next.token != null && activeToken !== next.token) continue
                            if (next.token != null) releaseActivePlayer()
                            settledState =
                                playFrom(
                                    startIndex = next.startIndex,
                                    failedIndices = next.failedIndices,
                                    attemptedIndices = attemptedIndices,
                                )
                        }
                        is Transition.Fail -> {
                            if (activeToken !== next.token) continue
                            releaseActivePlayer()
                            settledState = WakePlaylistPlaybackState.Failed
                        }
                    }
                }
                if (closed) break
                state = settledState
                if (pendingTransitions.isEmpty()) break
            }
        } finally {
            drainingTransitions = false
        }
    }

    private fun playFrom(
        startIndex: Int,
        failedIndices: Set<Int>,
        attemptedIndices: MutableSet<Int>,
    ): WakePlaylistPlaybackState {
        val unavailableIndices = failedIndices.toMutableSet()
        repeat(trackSources.size) { offset ->
            val index = (startIndex + offset) % trackSources.size
            if (index in unavailableIndices || !attemptedIndices.add(index)) return@repeat
            unavailableIndices += index
            val source = trackSources[index] ?: return@repeat
            val failureHistory = unavailableIndices.toSet()
            val activated =
                activate(
                    source = source,
                    playingState = WakePlaylistPlaybackState.PlayingTrack(index),
                    onCompletion = {
                        Transition.Advance(
                            startIndex = index + 1,
                            failedIndices = emptySet(),
                            token = it,
                        )
                    },
                    onError = {
                        Transition.Advance(
                            startIndex = index + 1,
                            failedIndices = failureHistory,
                            token = it,
                        )
                    },
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
            onError = { Transition.Fail(it) },
        ) ?: WakePlaylistPlaybackState.Failed
    }

    private fun activate(
        source: Source,
        playingState: WakePlaylistPlaybackState,
        onCompletion: ((Any) -> Transition)?,
        onError: (Any) -> Transition,
    ): WakePlaylistPlaybackState? {
        val token = Any()
        var committed = false
        var consumed = false
        var pendingEvent: PlayerEvent? = null

        fun signal(event: PlayerEvent) {
            if (event == PlayerEvent.COMPLETION && onCompletion == null) return
            if (consumed) return
            if (!committed) {
                if (pendingEvent == null) pendingEvent = event
                return
            }
            if (activeToken !== token) return
            consumed = true
            enqueue(
                when (event) {
                    PlayerEvent.COMPLETION -> checkNotNull(onCompletion).invoke(token)
                    PlayerEvent.ERROR -> onError(token)
                }
            )
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

        pendingEvent?.let { signal(it) }
        return playingState
    }

    private fun releaseActivePlayer(stopFirst: Boolean = false) {
        val activePlayer = player
        player = null
        activeToken = null
        if (activePlayer == null) return
        if (stopFirst) runCatching { activePlayer.stop() }
        runCatching { activePlayer.release() }
    }

    private sealed interface Transition {
        data class Advance(
            val startIndex: Int,
            val failedIndices: Set<Int>,
            val token: Any? = null,
        ) : Transition

        data class Fail(val token: Any) : Transition
    }

    private enum class PlayerEvent {
        COMPLETION,
        ERROR,
    }
}
