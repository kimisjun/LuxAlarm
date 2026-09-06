/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LegacyWakeAudioImporterTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("lux_alarm_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        root = File("build/test-audio/${UUID.randomUUID()}")
    }

    @Test
    fun failedDurableSettingsCommitRollsBackOnlyNewlyPublishedBytes() = runTest {
        val settings = SettingsManager(context, commitEditor = { false })
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("new audio".encodeToByteArray()) },
                settings,
            )

        assertFailsWith<IllegalStateException> {
            importer.importDocument("content://new")
        }

        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
        assertEquals(null, SettingsManager(context).getWakeProfile().importedAudioPath)
    }

    @Test
    fun failedCommitUsesKnownPriorReferenceInsteadOfMutatedPreferencesCache() = runTest {
        val prefs = context.getSharedPreferences("lux_alarm_settings", Context.MODE_PRIVATE)
        val settings = SettingsManager(context)
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("new audio".encodeToByteArray()) },
                settings,
                commitImportedAudioPath = { path ->
                    prefs.edit().putString("wake_imported_audio_path", path).commit()
                    false
                },
            )

        assertFailsWith<IllegalStateException> {
            importer.importDocument("content://new")
        }

        assertEquals(emptyList(), File(root, "tracks").listFiles().orEmpty().toList())
    }

    @Test
    fun failedDurableSettingsCommitPreservesPreexistingDuplicateBytes() = runTest {
        val bytes = "shared audio".encodeToByteArray()
        val store = WakeAudioStore(root) { ByteArrayInputStream(bytes) }
        val existing = store.storeDocument("content://existing").track
        val settings = SettingsManager(context, commitEditor = { false })
        val importer = LegacyWakeAudioImporter(store, settings)

        assertFailsWith<IllegalStateException> {
            importer.importDocument("content://duplicate")
        }

        assertTrue(File(existing.path).isFile)
        assertEquals(bytes.toList(), File(existing.path).readBytes().toList())
    }

    @Test
    fun settingsCommitResponseLossRestoresMemoryAndRetainsPendingEvidence() = runTest {
        val settings =
            SettingsManager(
                context,
                commitEditor = { editor ->
                    assertTrue(editor.commit())
                    error("simulated process death after settings commit")
                },
            )
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) },
                settings,
            )

        assertFailsWith<IllegalStateException> {
            importer.importDocument("content://audio")
        }

        assertEquals(null, settings.getWakeProfile().importedAudioPath)
        assertEquals(null, settings.wakeProfile.value.importedAudioPath)
        assertEquals(
            1,
            File(root, "tracks").listFiles().orEmpty().count {
                it.name.matches(Regex("[0-9a-f]{64}"))
            },
        )
        assertTrue(File(root, "tracks/.import.pending").isFile)
    }

    @Test
    fun uncertainReferenceLookupDefersCleanupToAuthoritativeReconciliation() = runTest {
        val settings = SettingsManager(context, commitEditor = { false })
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) },
                settings,
                referencedPath = { error("settings unavailable") },
            )

        assertFailsWith<IllegalStateException> {
            importer.importDocument("content://audio")
        }

        assertTrue(File(root, "tracks/.import.pending").isFile)
    }

    @Test
    fun reconciliationCannotEnterWhileLegacyImportTransactionIsPending() = runTest {
        val settings = SettingsManager(context)
        val coordinator = WakeAudioTransactionCoordinator()
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) },
                settings,
                transaction = { operation -> coordinator.withTransaction { operation() } },
                commitImportedAudioPath = { path ->
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                    settings.commitImportedAudioPath(path)
                },
            )
        val import = async { importer.importDocument("content://audio") }
        writerEntered.await()

        var reconciled = false
        val reconciliation = async {
            coordinator.withTransaction { reconciled = true }
        }
        yield()

        assertFalse(reconciled)
        releaseWriter.complete(Unit)
        import.await()
        reconciliation.await()
        assertTrue(reconciled)
    }

    @Test
    fun concurrentDismissalChangeDuringImportPreservesDismissalAndImportedPath() = runTest {
        val settings = SettingsManager(context)
        settings.updateWakeProfile(WakeProfile(dismissal = WakeDismissal.CONFIRM))
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) },
                settings,
                commitImportedAudioPath = { path ->
                    commitEntered.complete(Unit)
                    releaseCommit.await()
                    settings.commitImportedAudioPath(path)
                },
            )
        val import = async {
            importer.importDocument("content://audio")
        }
        commitEntered.await()

        settings.setWakeDismissal(WakeDismissal.LUX)
        releaseCommit.complete(Unit)
        val imported = import.await()

        assertEquals(WakeDismissal.LUX, settings.getWakeProfile().dismissal)
        assertEquals(imported.path, settings.getWakeProfile().importedAudioPath)
    }

    @Test
    fun cancellationDuringSettingsCommitIsRethrownWithPendingEvidencePreserved() = runTest {
        val settings = SettingsManager(context)
        val importer =
            LegacyWakeAudioImporter(
                WakeAudioStore(root) { ByteArrayInputStream("audio".encodeToByteArray()) },
                settings,
                commitImportedAudioPath = { throw CancellationException("cancel import") },
            )

        val cancellation =
            assertFailsWith<CancellationException> {
                importer.importDocument("content://audio")
            }

        assertEquals("cancel import", cancellation.message)
        assertTrue(File(root, "tracks/.import.pending").isFile)
        assertEquals(
            1,
            File(root, "tracks").listFiles().orEmpty().count {
                it.name.matches(Regex("[0-9a-f]{64}"))
            },
        )
        assertEquals(null, settings.getWakeProfile().importedAudioPath)
    }
}
