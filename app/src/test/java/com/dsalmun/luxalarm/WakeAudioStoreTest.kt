/*
 * This file is part of Lux Alarm, authored by Daniel Salmun, and was modified
 * for GentleWake in 2026.
 *
 * Lux Alarm is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Lux Alarm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Lux Alarm.  If not, see <https://www.gnu.org/licenses/>.
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
