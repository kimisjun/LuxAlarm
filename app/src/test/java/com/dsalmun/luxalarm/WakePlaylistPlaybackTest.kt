/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import kotlin.test.assertEquals
import org.junit.Test

class WakePlaylistPlaybackTest {
    @Test
    fun startsTheFirstPlayableTrackInPlaylistOrder() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.2f)

        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 0), state)
        assertEquals(listOf("first"), factory.requests.map { it.source })
        assertEquals(0.2f, factory.requests.single().initialGain)
    }

    @Test
    fun skipsMissingSourcesWithoutChangingTheirPlaylistPositions() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf(null, "second", null, "fourth"),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.1f)

        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), state)
        assertEquals(listOf("second"), factory.requests.map { it.source })
    }

    @Test
    fun playerCreationFailureAdvancesToTheNextTrack() {
        val factory = RecordingFactory(failingSources = setOf("first"))
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.1f)

        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), state)
        assertEquals(listOf("first", "second"), factory.requests.map { it.source })
    }

    @Test
    fun thrownPlayerCreationFailureAlsoAdvances() {
        val factory = RecordingFactory(throwingSources = setOf("first"))
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.1f)

        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), state)
        assertEquals(listOf("first", "second"), factory.requests.map { it.source })
    }

    @Test
    fun normalCompletionReleasesTheFinishedPlayerAndAdvances() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.3f)

        factory.players.single().complete()

        assertEquals(1, factory.players.first().releaseCount)
        assertEquals(listOf("first", "second"), factory.requests.map { it.source })
        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), playback.state)
    }

    @Test
    fun completionOfTheFinalTrackWrapsToTheFirstPlayableTrack() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf(null, "second", "third"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.3f)
        factory.players[0].complete()

        factory.players[1].complete()

        assertEquals(listOf("second", "third", "second"), factory.requests.map { it.source })
        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), playback.state)
    }

    @Test
    fun runtimeErrorReleasesTheBrokenPlayerAndAdvances() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.3f)

        factory.players.single().fail()

        assertEquals(1, factory.players.first().releaseCount)
        assertEquals(listOf("first", "second"), factory.requests.map { it.source })
        assertEquals(WakePlaylistPlaybackState.PlayingTrack(index = 1), playback.state)
    }

    @Test
    fun runtimeErrorDoesNotRetryTheSameTrackBeforeFallback() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("only"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.3f)

        factory.players.single().fail()

        assertEquals(listOf("only", "default"), factory.requests.map { it.source })
        assertEquals(WakePlaylistPlaybackState.PlayingFallback, playback.state)
    }

    @Test
    fun fullScanWithoutAPlayableLocalTrackStartsTheFallback() {
        val factory = RecordingFactory(failingSources = setOf("first", "second"))
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", null, "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.4f)

        assertEquals(WakePlaylistPlaybackState.PlayingFallback, state)
        assertEquals(listOf("first", "second", "default"), factory.requests.map { it.source })
    }

    @Test
    fun thrownFallbackCreationFailureReportsFailed() {
        val factory = RecordingFactory(throwingSources = setOf("default"))
        val playback =
            WakePlaylistPlayback(
                trackSources = emptyList(),
                fallbackSource = "default",
                playerFactory = factory,
            )

        val state = playback.start(initialGain = 0.4f)

        assertEquals(WakePlaylistPlaybackState.Failed, state)
    }

    @Test
    fun fallbackRuntimeFailureReleasesItAndReportsFailed() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = emptyList(),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.4f)

        factory.players.single().fail()

        assertEquals(1, factory.players.single().releaseCount)
        assertEquals(WakePlaylistPlaybackState.Failed, playback.state)
    }

    @Test
    fun gainUpdatesOnlyTheActivePlayer() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.1f)
        factory.players.single().complete()

        playback.updateGain(0.7f)

        assertEquals(emptyList(), factory.players[0].gainUpdates)
        assertEquals(listOf(0.7f), factory.players[1].gainUpdates)
    }

    @Test
    fun closeStopsAndReleasesTheActivePlayerOnlyOnce() {
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first"),
                fallbackSource = "default",
                playerFactory = factory,
            )
        playback.start(initialGain = 0.1f)

        playback.close()
        playback.close()

        assertEquals(1, factory.players.single().stopCount)
        assertEquals(1, factory.players.single().releaseCount)
    }

    @Test
    fun reportsPlaybackStateChangesAsTracksAdvance() {
        val states = mutableListOf<WakePlaylistPlaybackState>()
        val factory = RecordingFactory()
        val playback =
            WakePlaylistPlayback(
                trackSources = listOf("first", "second"),
                fallbackSource = "default",
                playerFactory = factory,
                onStateChange = states::add,
            )
        playback.start(initialGain = 0.1f)

        factory.players.single().complete()

        assertEquals(
            listOf<WakePlaylistPlaybackState>(
                WakePlaylistPlaybackState.PlayingTrack(index = 0),
                WakePlaylistPlaybackState.PlayingTrack(index = 1),
            ),
            states,
        )
    }

    private data class Request(val source: String, val initialGain: Float)

    private class RecordingFactory(
        private val failingSources: Set<String> = emptySet(),
        private val throwingSources: Set<String> = emptySet(),
    ) : WakePlaylistPlayerFactory<String> {
        val requests = mutableListOf<Request>()
        val players = mutableListOf<RecordingPlayer>()

        override fun create(
            source: String,
            initialGain: Float,
            onCompletion: () -> Unit,
            onError: () -> Unit,
        ): WakePlaylistPlayer? {
            requests += Request(source, initialGain)
            if (source in throwingSources) error("creation failed")
            if (source in failingSources) return null
            return RecordingPlayer(onCompletion, onError).also(players::add)
        }
    }

    private class RecordingPlayer(
        private val onCompletion: () -> Unit,
        private val onError: () -> Unit,
    ) : WakePlaylistPlayer {
        val gainUpdates = mutableListOf<Float>()
        var stopCount = 0
        var releaseCount = 0

        fun complete() = onCompletion()

        fun fail() = onError()

        override fun setGain(gain: Float) {
            gainUpdates += gain
        }

        override fun stop() {
            stopCount++
        }

        override fun release() {
            releaseCount++
        }
    }
}
