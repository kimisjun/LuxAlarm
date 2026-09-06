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
        try {
            check(commitImportedAudioPath(imported.path)) {
                "Wake audio settings commit failed"
            }
            pending.commit()
            imported
        } catch (cause: Exception) {
            val referenced = runCatching { referencedPath() == imported.path }
            runCatching {
                    referenced.fold(
                        onSuccess = pending::rollback,
                        onFailure = { pending.deferToReconciliation() },
                    )
                }
                .onFailure(cause::addSuppressed)
            throw cause
        }
    }
}
