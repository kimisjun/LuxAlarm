/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.AppContainer
import com.dsalmun.luxalarm.WakePlaylistRegistration
import com.dsalmun.luxalarm.WakeTrack
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomWakePlaylistStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "warmly-playlist-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun multipleNamedPlaylistsSurviveDatabaseCloseAndReopen() = runTest {
        val firstDatabase = open()
        val firstStore = RoomWakePlaylistStore(firstDatabase)
        val calm = firstStore.createPlaylist("Calm")
        val bright = firstStore.createPlaylist("Bright")
        firstDatabase.close()

        val secondDatabase = open()
        val playlists = RoomWakePlaylistStore(secondDatabase).listPlaylists()
        secondDatabase.close()

        assertEquals(listOf(calm, bright), playlists)
        assertNotEquals(calm.id, bright.id)
        UUID.fromString(calm.id)
        UUID.fromString(bright.id)
    }

    @Test
    fun playlistCanBeRenamed() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Old name")

        store.renamePlaylist(playlist.id, "Morning favorites")

        assertEquals(listOf(playlist.copy(name = "Morning favorites")), store.listPlaylists())
        database.close()
    }

    @Test
    fun onePlaylistCanBeSelectedForWakeWithoutASleepPlanOrTracks() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val calm = store.createPlaylist("Calm")
        val bright = store.createPlaylist("Bright")
        assertEquals(null, RoomSleepPlanStore(database.sleepPlanDao()).load())
        assertEquals(null, store.selectedPlaylistForWake())

        store.selectPlaylistForWake(calm.id)
        assertEquals(calm, store.selectedPlaylistForWake())

        store.selectPlaylistForWake(bright.id)
        assertEquals(bright, store.selectedPlaylistForWake())
        database.close()
    }

    @Test
    fun libraryTracksAreSeparateFromOrderedPlaylistEntries() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val birds = store.addTrackToLibrary("Birds", "/audio/birds.mp3")
        val piano = store.addTrackToLibrary("Piano", "/audio/piano.mp3")
        assertEquals(listOf(birds, piano), store.listLibraryTracks())
        assertEquals(emptyList(), store.listEntries(playlist.id))

        val firstEntry = store.addTrack(playlist.id, piano.id)
        val secondEntry = store.addTrack(playlist.id, birds.id)

        assertEquals(
            listOf(
                firstEntry.copy(position = 0, track = piano),
                secondEntry.copy(position = 1, track = birds),
            ),
            store.listEntries(playlist.id),
        )
        UUID.fromString(birds.id)
        UUID.fromString(firstEntry.id)
        database.close()
    }

    @Test
    fun importedTrackRegistrationUsesTheStableContentId() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val imported =
            WakeTrack(
                id = "stable-sha-256",
                title = "Birdsong.mp3",
                storedPath = "/owned/tracks/stable-sha-256",
            )

        val registration = store.registerTrackInPlaylist(playlist.id, imported)

        assertIs<WakePlaylistRegistration.Added>(registration)
        assertEquals(imported, registration.entry.track)
        assertEquals(listOf(imported), store.listLibraryTracks())
        assertEquals(listOf(imported), store.listEntries(playlist.id).map { it.track })
        database.close()
    }

    @Test
    fun repeatedStableTrackRegistrationReportsExistingMembership() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val original = WakeTrack("same-content", "Original name", "/owned/same-content")
        val renamedDocument = original.copy(title = "Renamed document")
        val first = store.registerTrackInPlaylist(playlist.id, original)

        val repeated = store.registerTrackInPlaylist(playlist.id, renamedDocument)

        assertIs<WakePlaylistRegistration.AlreadyPresent>(repeated)
        assertEquals(first.entry, repeated.entry)
        assertEquals(listOf(original), store.listLibraryTracks())
        assertEquals(listOf(original), store.listEntries(playlist.id).map { it.track })
        database.close()
    }

    @Test
    fun failedMembershipInsertRollsBackNewLibraryMetadata() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val imported = WakeTrack("stable-id", "Birdsong", "/owned/stable-id")

        assertFails { store.registerTrackInPlaylist("missing-playlist", imported) }

        assertEquals(emptyList(), store.listLibraryTracks())
        database.close()
    }

    @Test
    fun trackCannotAppearTwiceInOnePlaylistButCanAppearInAnother() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val calm = store.createPlaylist("Calm")
        val bright = store.createPlaylist("Bright")
        val birds = store.addTrackToLibrary("Birds", "/audio/birds.mp3")
        store.addTrack(calm.id, birds.id)

        assertFailsWith<IllegalArgumentException> { store.addTrack(calm.id, birds.id) }
        store.addTrack(bright.id, birds.id)

        assertEquals(listOf(birds), store.listEntries(calm.id).map { it.track })
        assertEquals(listOf(birds), store.listEntries(bright.id).map { it.track })
        database.close()
    }

    @Test
    fun removingAnEntryCompactsPositionsAndLeavesTheTrackInTheLibrary() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val first = store.addTrackToLibrary("First", "/audio/first.mp3")
        val removed = store.addTrackToLibrary("Removed", "/audio/removed.mp3")
        val last = store.addTrackToLibrary("Last", "/audio/last.mp3")
        store.addTrack(playlist.id, first.id)
        store.addTrack(playlist.id, removed.id)
        store.addTrack(playlist.id, last.id)

        store.removeTrack(playlist.id, removed.id)

        assertEquals(listOf(first, last), store.listEntries(playlist.id).map { it.track })
        assertEquals(listOf(0, 1), store.listEntries(playlist.id).map { it.position })
        assertEquals(listOf(first, removed, last), store.listLibraryTracks())
        database.close()
    }

    @Test
    fun reorderMovesEntriesAndKeepsPositionsContiguous() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val first = store.addTrackToLibrary("First", "/audio/first.mp3")
        val second = store.addTrackToLibrary("Second", "/audio/second.mp3")
        val third = store.addTrackToLibrary("Third", "/audio/third.mp3")
        store.addTrack(playlist.id, first.id)
        store.addTrack(playlist.id, second.id)
        store.addTrack(playlist.id, third.id)

        store.moveTrack(playlist.id, third.id, 0)
        assertEquals(
            listOf(third, first, second),
            store.listEntries(playlist.id).map { it.track },
        )

        store.moveTrack(playlist.id, third.id, 2)
        assertEquals(
            listOf(first, second, third),
            store.listEntries(playlist.id).map { it.track },
        )
        assertEquals(listOf(0, 1, 2), store.listEntries(playlist.id).map { it.position })
        database.close()
    }

    @Test
    fun removingAfterReorderStillCompactsWithoutAUniquePositionCollision() = runTest {
        val database = open()
        val store = RoomWakePlaylistStore(database)
        val playlist = store.createPlaylist("Morning")
        val first = store.addTrackToLibrary("First", "/audio/first.mp3")
        val second = store.addTrackToLibrary("Second", "/audio/second.mp3")
        val third = store.addTrackToLibrary("Third", "/audio/third.mp3")
        store.addTrack(playlist.id, first.id)
        store.addTrack(playlist.id, second.id)
        store.addTrack(playlist.id, third.id)
        store.moveTrack(playlist.id, first.id, 2)

        store.removeTrack(playlist.id, second.id)

        assertEquals(listOf(third, first), store.listEntries(playlist.id).map { it.track })
        assertEquals(listOf(0, 1), store.listEntries(playlist.id).map { it.position })
        database.close()
    }

    @Test
    @Config(application = AppContainer::class)
    fun appContainerProvidesTheDurablePlaylistStore() {
        ApplicationProvider.getApplicationContext<AppContainer>()

        kotlin.test.assertIs<RoomWakePlaylistStore>(AppContainer.wakePlaylistStore)
    }

    private fun open(): WarmlyDatabase =
        Room.databaseBuilder(context, WarmlyDatabase::class.java, databaseName).build()
}
