/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
