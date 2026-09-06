/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class LocalTrackImporterTest {
    @Test
    fun unsupportedDocumentDoesNotCreateOwnedBytes() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        var openCount = 0
        val store =
            WakeAudioStore(root) {
                openCount += 1
                ByteArrayInputStream("not audio".encodeToByteArray())
            }
        val importer = LocalTrackImporter(store) { "text/plain" }

        val result =
            assertIs<LocalTrackImportResult.Unsupported>(
                importer.importDocuments(listOf("content://text"))[0]
            )

        assertEquals("text/plain", result.mimeType)
        assertEquals(0, openCount)
        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
    }

    @Test
    fun failureDoesNotRollBackOtherSelectedDocuments() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val bytesByUri =
            mapOf(
                "content://first" to "first audio".encodeToByteArray(),
                "content://third" to "third audio".encodeToByteArray(),
            )
        val store = WakeAudioStore(root) { uri -> bytesByUri[uri]?.let(::ByteArrayInputStream) }
        val importer = LocalTrackImporter(store) { "audio/mpeg" }

        val results =
            importer.importDocuments(
                listOf("content://first", "content://missing", "content://third")
            )

        assertIs<LocalTrackImportResult.Added>(results[0])
        assertIs<LocalTrackImportResult.Failed>(results[1])
        assertIs<LocalTrackImportResult.Added>(results[2])
        assertEquals(2, File(root, "tracks").listFiles().orEmpty().size)
    }

    @Test
    fun identicalContentFromDifferentUrisIsReportedDuplicate() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val bytes = "same bytes despite names".encodeToByteArray()
        val store = WakeAudioStore(root) { ByteArrayInputStream(bytes) }
        val importer = LocalTrackImporter(store) { "audio/ogg" }

        val results =
            importer.importDocuments(listOf("content://song-a.ogg", "content://renamed.ogg"))

        val added = assertIs<LocalTrackImportResult.Added>(results[0])
        val duplicate = assertIs<LocalTrackImportResult.Duplicate>(results[1])
        assertEquals(added.track, duplicate.track)
    }

    @Test
    fun importsDocumentsInSelectionOrder() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val bytesByUri =
            mapOf(
                "content://first" to "first audio".encodeToByteArray(),
                "content://second" to "second audio".encodeToByteArray(),
            )
        val store = WakeAudioStore(root) { uri -> bytesByUri[uri]?.let(::ByteArrayInputStream) }
        val importer = LocalTrackImporter(store) { "audio/mpeg" }

        val results = importer.importDocuments(listOf("content://first", "content://second"))

        assertEquals(listOf("content://first", "content://second"), results.map { it.documentUri })
        assertIs<LocalTrackImportResult.Added>(results[0])
        assertIs<LocalTrackImportResult.Added>(results[1])
    }
}
