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

sealed interface LocalTrackPreparation {
    val documentUri: String

    data class Ready(
        override val documentUri: String,
        val pending: WakeAudioStore.PreparedImport,
    ) : LocalTrackPreparation

    data class Unsupported(
        override val documentUri: String,
        val mimeType: String?,
    ) : LocalTrackPreparation

    data class Failed(
        override val documentUri: String,
        val cause: Throwable,
    ) : LocalTrackPreparation
}

class LocalTrackImporter(
    private val store: WakeAudioStore,
    private val mimeTypeFor: (String) -> String?,
) {
    fun importDocuments(documentUris: List<String>): List<LocalTrackImportResult> =
        documentUris.map(::importDocument)

    fun importDocument(documentUri: String): LocalTrackImportResult =
        when (val preparation = prepareDocument(documentUri)) {
            is LocalTrackPreparation.Ready -> {
                preparation.pending.commit()
                when (val result = preparation.pending.result) {
                    is WakeAudioStore.ImportResult.Added ->
                        LocalTrackImportResult.Added(documentUri, result.track)
                    is WakeAudioStore.ImportResult.Duplicate ->
                        LocalTrackImportResult.Duplicate(documentUri, result.track)
                }
            }
            is LocalTrackPreparation.Unsupported ->
                LocalTrackImportResult.Unsupported(documentUri, preparation.mimeType)
            is LocalTrackPreparation.Failed ->
                LocalTrackImportResult.Failed(documentUri, preparation.cause)
        }

    fun prepareDocument(documentUri: String): LocalTrackPreparation =
        try {
            val mimeType = mimeTypeFor(documentUri)
            if (mimeType?.startsWith("audio/") != true) {
                LocalTrackPreparation.Unsupported(documentUri, mimeType)
            } else {
                LocalTrackPreparation.Ready(documentUri, store.prepareDocument(documentUri))
            }
        } catch (cause: Exception) {
            LocalTrackPreparation.Failed(documentUri, cause)
        }
}
