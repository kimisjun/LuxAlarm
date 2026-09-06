/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

sealed interface LocalTrackImportResult {
    val documentUri: String

    data class Added(
        override val documentUri: String,
        val track: WakeAudioStore.OwnedTrack,
    ) : LocalTrackImportResult

    data class Duplicate(
        override val documentUri: String,
        val track: WakeAudioStore.OwnedTrack,
    ) : LocalTrackImportResult

    data class Unsupported(
        override val documentUri: String,
        val mimeType: String?,
    ) : LocalTrackImportResult

    data class Failed(
        override val documentUri: String,
        val cause: Throwable,
    ) : LocalTrackImportResult
}

class LocalTrackImporter(
    private val store: WakeAudioStore,
    private val mimeTypeFor: (String) -> String?,
) {
    fun importDocuments(documentUris: List<String>): List<LocalTrackImportResult> =
        documentUris.map(::importDocument)

    private fun importDocument(documentUri: String): LocalTrackImportResult =
        try {
            val mimeType = mimeTypeFor(documentUri)
            if (mimeType?.startsWith("audio/") != true) {
                LocalTrackImportResult.Unsupported(documentUri, mimeType)
            } else {
                when (val result = store.storeDocument(documentUri)) {
                    is WakeAudioStore.ImportResult.Added ->
                        LocalTrackImportResult.Added(documentUri, result.track)
                    is WakeAudioStore.ImportResult.Duplicate ->
                        LocalTrackImportResult.Duplicate(documentUri, result.track)
                }
            }
        } catch (cause: Exception) {
            LocalTrackImportResult.Failed(documentUri, cause)
        }
}
