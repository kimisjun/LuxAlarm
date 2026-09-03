/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class WakeAudioStoreTest {
    @Test
    fun importCopiesIntoAppStorageAndAMissingCopyFallsBackToDefault() {
        val root = File("build/test-audio/${UUID.randomUUID()}")
        val documentBytes = byteArrayOf(0x47, 0x57, 0x41, 0x4B, 0x45)
        val store = WakeAudioStore(root) { ByteArrayInputStream(documentBytes) }

        val imported = store.importDocument("content://documents/audio/42")

        assertTrue(imported.path.startsWith(root.canonicalPath))
        assertContentEquals(documentBytes, File(imported.path).readBytes())
        assertEquals(imported, store.playbackSource(imported.path))
        assertEquals(
            WakeAudioSource.Default,
            store.playbackSource(File(root, "missing-audio").path),
        )
    }
}
