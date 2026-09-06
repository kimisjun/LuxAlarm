/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class WakeAudioStoreTest {
    @Test
    fun importUsesSha256IdentityUnderImmutableTracksDirectory() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val documentBytes = "same audio bytes".encodeToByteArray()
        val store = WakeAudioStore(root) { ByteArrayInputStream(documentBytes) }

        val imported = store.importDocument("content://documents/audio/42")

        assertEquals(
            File(
                    root,
                    "tracks/aafe9f6cb200b33109672a43c8ea1e40835484abeb0520632cdc9362ce1f58a1",
                )
                .canonicalPath,
            imported.path,
        )
        assertContentEquals(documentBytes, File(imported.path).readBytes())
    }

    @Test
    fun identicalBytesReuseOwnedTrackAsDuplicate() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val bytes = "identical".encodeToByteArray()
        val store = WakeAudioStore(root) { ByteArrayInputStream(bytes) }

        val added =
            assertIs<WakeAudioStore.ImportResult.Added>(store.storeDocument("content://first"))
        val duplicate =
            assertIs<WakeAudioStore.ImportResult.Duplicate>(
                store.storeDocument("content://renamed")
            )

        assertEquals(added.track, duplicate.track)
        assertEquals(1, File(root, "tracks").listFiles().orEmpty().size)
    }

    @Test
    fun deletedOwnedBytesAreReportedMissingWithoutChangingTrackIdentity() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val store = WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) }
        val track =
            assertIs<WakeAudioStore.ImportResult.Added>(store.storeDocument("content://audio"))
                .track
        assertTrue(store.deleteOwnedBytes(track))

        assertEquals(
            WakeAudioStore.TrackAvailability.Missing(track),
            store.availability(track),
        )
    }

    @Test
    fun reconciliationRemovesLeftoverPendingStagingAndPublishedBytesWithoutMetadata() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val store = WakeAudioStore(root) { ByteArrayInputStream("orphan".encodeToByteArray()) }
        val prepared = store.prepareDocument("content://orphan")
        val published = prepared.result.track
        assertEquals(
            setOf(".import.pending", ".import.staging", published.id),
            File(root, "tracks").listFiles().orEmpty().map(File::getName).toSet(),
        )

        val report = WakeAudioStore(root) { null }.reconcile(emptySet())

        assertTrue(report.removedStaging)
        assertEquals(setOf(published.id), report.removedUnreferencedTrackIds)
        assertFalse(File(published.path).exists())
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
    }

    @Test
    fun reconciliationPreservesReferencedFinalAndSurfacesMissingMetadataFile() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val store = WakeAudioStore(root) { ByteArrayInputStream("kept".encodeToByteArray()) }
        val kept = store.storeDocument("content://kept").track
        val missing = "0".repeat(64)

        val report = store.reconcile(setOf(kept.id, missing))

        assertTrue(File(kept.path).isFile)
        assertEquals(emptySet(), report.removedUnreferencedTrackIds)
        assertEquals(setOf(missing), report.missingReferencedTrackIds)
    }

    @Test
    fun reconciliationRemovesLegacyUniqueTempButPreservesUnrelatedFiles() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val tracks = File(root, "tracks").apply { mkdirs() }
        val stale = File(tracks, "track-dead-process.tmp").apply { writeText("partial") }
        val unrelated = File(tracks, "notes.tmp").apply { writeText("keep") }

        WakeAudioStore(root) { null }.reconcile(emptySet())

        assertFalse(stale.exists())
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun danglingControlSymlinksNeverCreateOrTruncateTheirTargets() {
        listOf(".import.pending", ".import.staging").forEach { controlName ->
            val root = File("build/test-audio/${UUID.randomUUID()}")
            val tracks = File(root, "tracks").apply { mkdirs() }
            val outside = File(root, "outside")
            Files.createSymbolicLink(
                File(tracks, controlName).toPath(),
                outside.toPath().toAbsolutePath(),
            )

            runCatching {
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) }
                    .prepareDocument("content://audio")
            }

            assertFalse(outside.exists())
        }
    }

    @Test
    fun importSyncsTracksDirectoryAroundMarkerPublicationAndCompletion() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val synced = mutableListOf<File>()
        val store =
            WakeAudioStore(root, syncDirectory = { synced += it }) {
                ByteArrayInputStream("audio".encodeToByteArray())
            }

        store.storeDocument("content://audio")

        assertEquals(3, synced.size)
        assertTrue(synced.all { it == File(root, "tracks") })
    }

    @Test
    fun fileSyncFailureBlocksPublicationAndCleansBoundedPendingState() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        var syncCalls = 0
        val store =
            WakeAudioStore(
                root,
                syncFile = { output: FileChannel ->
                    syncCalls += 1
                    if (syncCalls == 2) error("simulated file sync failure")
                    output.force(true)
                },
            ) {
                ByteArrayInputStream("audio".encodeToByteArray())
            }

        val failure = runCatching { store.prepareDocument("content://audio") }.exceptionOrNull()

        assertEquals("simulated file sync failure", failure?.message)
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
    }

    @Test
    fun publicationDirectorySyncFailureRemovesOnlyTheNewlyPublishedFinal() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        var syncCalls = 0
        val store =
            WakeAudioStore(
                root,
                syncDirectory = {
                    syncCalls += 1
                    if (syncCalls == 2) error("simulated directory sync failure")
                },
            ) {
                ByteArrayInputStream("audio".encodeToByteArray())
            }

        val failure = runCatching { store.prepareDocument("content://audio") }.exceptionOrNull()

        assertEquals("simulated directory sync failure", failure?.message)
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
    }

    @Test
    fun deletionRejectsTraversalAbsoluteAndMalformedIdentityWithoutTouchingUnrelatedFiles() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val store = WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) }
        val unrelated =
            File(root, "unrelated.txt").apply {
                parentFile.mkdirs()
                writeText("keep")
            }
        val valid = store.storeDocument("content://audio").track
        val attacks =
            listOf(
                valid.copy(id = "../${valid.id}", sha256 = "../${valid.id}"),
                valid.copy(id = "/${valid.id}", sha256 = "/${valid.id}"),
                valid.copy(id = valid.id.uppercase(), sha256 = valid.id.uppercase()),
                valid.copy(id = "abc", sha256 = "abc"),
                valid.copy(sha256 = "1".repeat(64)),
                valid.copy(path = "../${valid.id}"),
                valid.copy(path = File("/tmp", valid.id).path),
                valid.copy(path = File(root, "tracks/../tracks/${valid.id}").absolutePath),
                valid.copy(path = unrelated.absolutePath),
            )

        attacks.forEach { assertFalse(store.deleteOwnedBytes(it)) }

        assertTrue(File(valid.path).isFile)
        assertEquals("keep", unrelated.readText())
    }

    @Test
    fun deletionRejectsSymlinkEvenWhenItsNameAndIdentityLookOwned() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val tracks = File(root, "tracks").apply { mkdirs() }
        val outside = File(root, "outside").apply { writeText("keep") }
        val id = "1".repeat(64)
        val link = File(tracks, id)
        Files.createSymbolicLink(link.toPath(), outside.toPath().toAbsolutePath())
        val store = WakeAudioStore(root) { null }

        assertFalse(store.deleteOwnedBytes(WakeAudioStore.OwnedTrack(id, id, link.absolutePath)))
        assertTrue(link.exists())
        assertEquals("keep", outside.readText())
    }

    @Test
    fun deletionRejectsASymlinkedTracksDirectory() {
        val root = File("build/test-audio/${UUID.randomUUID()}").apply { mkdirs() }
        val outsideTracks = File(root, "outside-tracks").apply { mkdirs() }
        val id = "2".repeat(64)
        val outside = File(outsideTracks, id).apply { writeText("keep") }
        val tracksLink = File(root, "tracks")
        Files.createSymbolicLink(tracksLink.toPath(), outsideTracks.toPath().toAbsolutePath())
        val store = WakeAudioStore(root) { null }

        assertFalse(
            store.deleteOwnedBytes(
                WakeAudioStore.OwnedTrack(id, id, File(tracksLink, id).absolutePath)
            )
        )
        assertEquals("keep", outside.readText())
    }

    @Test
    fun aMissingCopyFallsBackToDefault() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val documentBytes = byteArrayOf(0x47, 0x57, 0x41, 0x4B, 0x45)
        val store = WakeAudioStore(root) { ByteArrayInputStream(documentBytes) }
        val imported = store.importDocument("content://documents/audio/42")

        assertTrue(imported.path.startsWith(root.canonicalPath))
        assertEquals(imported, store.playbackSource(imported.path))
        assertEquals(
            WakeAudioSource.Default,
            store.playbackSource(File(root, "missing-audio").path),
        )
    }
}
