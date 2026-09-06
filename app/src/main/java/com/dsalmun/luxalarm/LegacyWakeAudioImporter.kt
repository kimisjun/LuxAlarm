/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

/** Crash-safe adapter for the legacy single-track setting. */
class LegacyWakeAudioImporter(
    private val audioStore: WakeAudioStore,
    private val settingsManager: SettingsManager,
    private val transaction:
        suspend (suspend () -> WakeAudioSource.Imported) -> WakeAudioSource.Imported =
        { operation ->
            operation()
        },
    private val referencedPath: () -> String? = {
        settingsManager.getWakeProfile().importedAudioPath
    },
    private val commitImportedAudioPath: suspend (String) -> Boolean =
        settingsManager::commitImportedAudioPath,
) {
    suspend fun importDocument(documentUri: String): WakeAudioSource.Imported = transaction {
        val pending = audioStore.prepareDocument(documentUri)
        val imported = WakeAudioSource.Imported(pending.result.track.path)
        val priorReference =
            try {
                referencedPath()
            } catch (cause: Exception) {
                deferUncertainImport(pending, cause)
                throw cause
            }

        val committed =
            try {
                commitImportedAudioPath(imported.path)
            } catch (cause: Exception) {
                deferUncertainImport(pending, cause)
                throw cause
            }
        if (!committed) {
            val cause = IllegalStateException("Wake audio settings commit failed")
            runCatching {
                    pending.rollback(publishedBytesAreReferenced = priorReference == imported.path)
                }
                .onFailure(cause::addSuppressed)
            throw cause
        }

        try {
            pending.commit()
            imported
        } catch (cause: Exception) {
            deferUncertainImport(pending, cause)
            throw cause
        }
    }

    /**
     * The durable reference state cannot be proven; retain all evidence for startup reconciliation.
     */
    private fun deferUncertainImport(
        pending: WakeAudioStore.PreparedImport,
        cause: Exception,
    ) {
        runCatching(pending::deferToReconciliation).onFailure(cause::addSuppressed)
    }
}
