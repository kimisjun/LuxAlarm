/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dsalmun.luxalarm.data.RoomWakePlaylistStore
import com.dsalmun.luxalarm.data.WarmlyDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WakePlaylistImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "warmly-playlist-importer-test.db"
    private val root = File("build/test-audio/${UUID.randomUUID()}")

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test
    fun batchKeepsSuccessesAroundFailedAndUnsupportedDocumentsInSelectionOrder() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        val playlist = playlistStore.createPlaylist("Morning")
        val bytesByUri =
            mapOf(
                "content://first" to "first audio".encodeToByteArray(),
                "content://third" to "third audio".encodeToByteArray(),
            )
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { uri -> bytesByUri[uri]?.let(::ByteArrayInputStream) }
                ) { uri ->
                    if (uri == "content://unsupported") "text/plain" else "audio/mpeg"
                },
                playlistStore,
            ) { uri ->
                if (uri == "content://third") "Third.mp3" else "  "
            }
        val uris =
            listOf(
                "content://first",
                "content://missing",
                "content://unsupported",
                "content://third",
            )

        val results = importer.importIntoPlaylist(playlist.id, uris)

        assertEquals(uris, results.map { it.documentUri })
        assertIs<WakePlaylistImportResult.Added>(results[0])
        assertIs<WakePlaylistImportResult.Failed>(results[1])
        assertIs<WakePlaylistImportResult.Unsupported>(results[2])
        assertIs<WakePlaylistImportResult.Added>(results[3])
        assertEquals(
            listOf("Imported audio", "Third.mp3"),
            playlistStore.listEntries(playlist.id).map { it.track.title },
        )
        database.close()
    }

    @Test
    fun duplicateContentReusesLibraryReportsMembershipAndCanJoinAnotherPlaylist() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        val calm = playlistStore.createPlaylist("Calm")
        val bright = playlistStore.createPlaylist("Bright")
        val bytes = "identical audio bytes".encodeToByteArray()
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(WakeAudioStore(root) { ByteArrayInputStream(bytes) }) {
                    "audio/ogg"
                },
                playlistStore,
            ) { uri ->
                if (uri.endsWith("original")) "Original.ogg" else "Renamed.ogg"
            }

        val calmResults =
            importer.importIntoPlaylist(
                calm.id,
                listOf("content://original", "content://renamed"),
            )
        val brightResult =
            importer.importIntoPlaylist(bright.id, listOf("content://another-name")).single()

        val first = assertIs<WakePlaylistImportResult.Added>(calmResults[0])
        assertEquals(false, first.duplicateContent)
        val duplicateMembership =
            assertIs<WakePlaylistImportResult.AlreadyInPlaylist>(calmResults[1])
        assertEquals(true, duplicateMembership.duplicateContent)
        assertEquals(first.entry, duplicateMembership.entry)
        val addedElsewhere = assertIs<WakePlaylistImportResult.Added>(brightResult)
        assertEquals(true, addedElsewhere.duplicateContent)
        assertEquals(first.entry.track, addedElsewhere.entry.track)
        assertEquals(listOf(first.entry.track), playlistStore.listLibraryTracks())
        assertEquals(listOf(first.entry.track), playlistStore.listEntries(calm.id).map { it.track })
        assertEquals(
            listOf(first.entry.track),
            playlistStore.listEntries(bright.id).map { it.track },
        )
        database.close()
    }

    @Test
    fun missingOwnedBytesDoNotDeleteLibraryMetadataOrPlaylistEntries() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        val playlist = playlistStore.createPlaylist("Morning")
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { ByteArrayInputStream("bird audio".encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                playlistStore,
            ) {
                "Birdsong.mp3"
            }
        val added =
            assertIs<WakePlaylistImportResult.Added>(
                importer.importIntoPlaylist(playlist.id, listOf("content://birds")).single()
            )

        File(added.ownedTrack.path).delete()

        assertEquals(listOf(added.entry.track), playlistStore.listLibraryTracks())
        assertEquals(listOf(added.entry), playlistStore.listEntries(playlist.id))
        database.close()
    }

    @Test
    fun titleLookupFailureUsesSafeFallbackWithoutLosingTheImport() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        val playlist = playlistStore.createPlaylist("Morning")
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { ByteArrayInputStream("bird audio".encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                playlistStore,
            ) {
                error("display name provider unavailable")
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://birds")).single()

        val added = assertIs<WakePlaylistImportResult.Added>(result)
        assertEquals("Imported audio", added.entry.track.title)
        database.close()
    }

    @Test
    fun importedBytesAreRegisteredWithStableIdTitleAndOwnedPath() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        val playlist = playlistStore.createPlaylist("Morning")
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { ByteArrayInputStream("bird audio".encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                playlistStore,
            ) {
                "Birdsong.mp3"
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://birds")).single()

        val added = assertIs<WakePlaylistImportResult.Added>(result)
        assertEquals("Birdsong.mp3", added.entry.track.title)
        assertEquals(added.ownedTrack.id, added.entry.track.id)
        assertEquals(added.ownedTrack.path, added.entry.track.storedPath)
        assertEquals(listOf(added.entry), playlistStore.listEntries(playlist.id))
        database.close()
    }

    private fun open(): WarmlyDatabase =
        Room.databaseBuilder(context, WarmlyDatabase::class.java, databaseName).build()
}
