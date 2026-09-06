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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
    fun stalePlaylistIsRejectedBeforeAnyDocumentIsOpenedOrCopied() = runTest {
        val database = open()
        val playlistStore = RoomWakePlaylistStore(database)
        var opened = false
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) {
                        opened = true
                        ByteArrayInputStream("audio".encodeToByteArray())
                    }
                ) {
                    "audio/mpeg"
                },
                playlistStore,
            ) {
                "Track.mp3"
            }

        val result = importer.importIntoPlaylist("deleted-playlist", listOf("content://track"))

        assertIs<WakePlaylistImportResult.Failed>(result.single())
        assertFalse(opened)
        assertFalse(File(root, "tracks").exists())
        database.close()
    }

    @Test
    fun eachDocumentIsRegisteredBeforeTheNextDocumentIsCopied() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val events = mutableListOf<String>()
        val recordingStore =
            object : WakePlaylistStore by roomStore {
                override suspend fun registerTrackInPlaylist(
                    playlistId: String,
                    track: WakeTrack,
                ): WakePlaylistRegistration {
                    events += "register:${track.title}"
                    return roomStore.registerTrackInPlaylist(playlistId, track)
                }
            }
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { uri ->
                        events += "open:${uri.substringAfterLast('/')}"
                        ByteArrayInputStream(uri.encodeToByteArray())
                    }
                ) {
                    "audio/mpeg"
                },
                recordingStore,
            ) { uri ->
                uri.substringAfterLast('/')
            }

        importer.importIntoPlaylist(playlist.id, listOf("content://first", "content://second"))

        assertEquals(
            listOf("open:first", "register:first", "open:second", "register:second"),
            events,
        )
        database.close()
    }

    @Test
    fun registrationFailureRemovesNewlyPublishedBytes() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val failingStore =
            object : WakePlaylistStore by roomStore {
                override suspend fun registerTrackInPlaylist(
                    playlistId: String,
                    track: WakeTrack,
                ): WakePlaylistRegistration = error("registration failed")
            }
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { ByteArrayInputStream("new audio".encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                failingStore,
            ) {
                "Track.mp3"
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://track"))

        assertIs<WakePlaylistImportResult.Failed>(result.single())
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
        database.close()
    }

    @Test
    fun registrationFailureNeverDeletesPreexistingDuplicateBytes() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val bytes = "shared audio".encodeToByteArray()
        val audioStore = WakeAudioStore(root) { ByteArrayInputStream(bytes) }
        val preexisting = audioStore.storeDocument("content://existing").track
        val failingStore =
            object : WakePlaylistStore by roomStore {
                override suspend fun registerTrackInPlaylist(
                    playlistId: String,
                    track: WakeTrack,
                ): WakePlaylistRegistration = error("registration failed")
            }
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(audioStore) { "audio/mpeg" },
                failingStore,
            ) {
                "Track.mp3"
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://duplicate"))

        assertIs<WakePlaylistImportResult.Failed>(result.single())
        assertEquals(bytes.toList(), File(preexisting.path).readBytes().toList())
        database.close()
    }

    @Test
    fun registrationFailureForOneDocumentDoesNotRollbackTheNextSuccess() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        var registrations = 0
        val firstFailingStore =
            object : WakePlaylistStore by roomStore {
                override suspend fun registerTrackInPlaylist(
                    playlistId: String,
                    track: WakeTrack,
                ): WakePlaylistRegistration {
                    registrations += 1
                    if (registrations == 1) error("first registration failed")
                    return roomStore.registerTrackInPlaylist(playlistId, track)
                }
            }
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { uri -> ByteArrayInputStream(uri.encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                firstFailingStore,
            ) {
                it.substringAfterLast('/')
            }

        val results =
            importer.importIntoPlaylist(
                playlist.id,
                listOf("content://first", "content://second"),
            )

        assertIs<WakePlaylistImportResult.Failed>(results[0])
        val added = assertIs<WakePlaylistImportResult.Added>(results[1])
        assertEquals(listOf(added.entry), roomStore.listEntries(playlist.id))
        assertEquals(
            listOf(File(added.ownedTrack.path)),
            File(root, "tracks").listFiles()?.map(File::getAbsoluteFile),
        )
        database.close()
    }

    @Test
    fun referenceQueryFailureRetainsRecoveryEvidenceUntilReconciliationCanDecide() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val unavailableStore =
            object : WakePlaylistStore by roomStore {
                override suspend fun registerTrackInPlaylist(
                    playlistId: String,
                    track: WakeTrack,
                ): WakePlaylistRegistration = error("registration unavailable")

                override suspend fun listLibraryTracks(): List<WakeTrack> =
                    error("reference query unavailable")
            }
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) { ByteArrayInputStream("orphan".encodeToByteArray()) }
                ) {
                    "audio/mpeg"
                },
                unavailableStore,
            ) {
                "Track.mp3"
            }

        assertIs<WakePlaylistImportResult.Failed>(
            importer.importIntoPlaylist(playlist.id, listOf("content://track")).single()
        )
        assertTrue(File(root, "tracks/.import.pending").isFile)

        val report = WakeAudioStore(root) { null }.reconcile(emptySet())

        assertEquals(1, report.removedUnreferencedTrackIds.size)
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
        database.close()
    }

    @Test
    fun reconciliationAfterMetadataCommitPreservesFinalAndRemovesPendingResidue() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val store = WakeAudioStore(root) { ByteArrayInputStream("committed".encodeToByteArray()) }
        val pending = store.prepareDocument("content://track")
        val owned = pending.result.track
        roomStore.registerTrackInPlaylist(
            playlist.id,
            WakeTrack(owned.id, "Track.mp3", owned.path),
        )

        val report = WakeAudioStore(root) { null }.reconcile(setOf(owned.id))

        assertTrue(report.removedStaging)
        assertTrue(File(owned.path).isFile)
        assertEquals(setOf(owned.id), File(root, "tracks").listFiles()?.map(File::getName)?.toSet())
        database.close()
    }

    @Test
    fun retryReconciliationRunsBeforeOpeningADocument() = runTest {
        val database = open()
        val roomStore = RoomWakePlaylistStore(database)
        val playlist = roomStore.createPlaylist("Morning")
        val store = WakeAudioStore(root) { ByteArrayInputStream("stale".encodeToByteArray()) }
        store.prepareDocument("content://stale")
        var reconciled = false
        val importer =
            WakePlaylistImporter(
                LocalTrackImporter(
                    WakeAudioStore(root) {
                        assertTrue(reconciled)
                        ByteArrayInputStream("fresh".encodeToByteArray())
                    }
                ) {
                    "audio/mpeg"
                },
                roomStore,
                beforeImport = {
                    WakeAudioStore(root) { null }.reconcile(emptySet())
                    reconciled = true
                },
            ) {
                "Track.mp3"
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://fresh"))

        assertIs<WakePlaylistImportResult.Added>(result.single())
        database.close()
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
                fallbackTitle = "Imported audio",
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
                fallbackTitle = "Imported audio",
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
                fallbackTitle = "Imported audio",
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
                fallbackTitle = "가져온 오디오",
            ) {
                error("display name provider unavailable")
            }

        val result = importer.importIntoPlaylist(playlist.id, listOf("content://birds")).single()

        val added = assertIs<WakePlaylistImportResult.Added>(result)
        assertEquals("가져온 오디오", added.entry.track.title)
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
                fallbackTitle = "Imported audio",
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
