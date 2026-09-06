/*
 * Warmly is a 2026 modification of Lux Alarm, originally authored by Daniel Salmun.
 * Additional Warmly code and modifications Copyright (C) 2026 김은준.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest

class WakeAudioDocumentContract : ActivityResultContract<Unit, String?>() {
    private val delegate = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: Unit): Intent =
        delegate
            .createIntent(context, arrayOf("audio/*"))
            .setType("audio/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        delegate.parseResult(resultCode, intent)?.toString()
}

/** Multi-document picker contract kept separate until playlist UI integration. */
class WakeAudioDocumentsContract : ActivityResultContract<Unit, List<String>>() {
    private val delegate = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: Unit): Intent =
        delegate
            .createIntent(context, arrayOf("audio/*"))
            .setType("audio/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

    override fun parseResult(resultCode: Int, intent: Intent?): List<String> =
        delegate.parseResult(resultCode, intent).map { it.toString() }
}

sealed interface WakeAudioSource {
    data object Default : WakeAudioSource

    data class Imported(val path: String) : WakeAudioSource
}

/** Owns a durable copy so playback never depends on the document provider remaining available. */
class WakeAudioStore(
    private val storageDirectory: File,
    private val openDocument: (String) -> InputStream?,
) {
    data class OwnedTrack(
        val id: String,
        val sha256: String,
        val path: String,
    )

    sealed interface ImportResult {
        val track: OwnedTrack

        data class Added(override val track: OwnedTrack) : ImportResult

        data class Duplicate(override val track: OwnedTrack) : ImportResult
    }

    sealed interface TrackAvailability {
        val track: OwnedTrack

        data class Available(override val track: OwnedTrack) : TrackAvailability

        data class Missing(override val track: OwnedTrack) : TrackAvailability
    }

    fun availability(track: OwnedTrack): TrackAvailability =
        if (File(track.path).isFile) {
            TrackAvailability.Available(track)
        } else {
            TrackAvailability.Missing(track)
        }

    /** Deletes only the app-owned bytes; callers retain any independent track metadata. */
    fun deleteOwnedBytes(track: OwnedTrack): Boolean {
        val expected = File(File(storageDirectory, TRACKS_DIRECTORY_NAME), track.id).canonicalFile
        require(track.id == track.sha256 && track.path == expected.path) {
            "Track is not owned by this store"
        }
        return expected.delete()
    }

    fun importDocument(documentUri: String): WakeAudioSource.Imported {
        val track = storeDocument(documentUri).track
        return WakeAudioSource.Imported(track.path)
    }

    fun storeDocument(documentUri: String): ImportResult {
        val tracksDirectory = File(storageDirectory, TRACKS_DIRECTORY_NAME)
        check(tracksDirectory.mkdirs() || tracksDirectory.isDirectory) {
            "Cannot create wake audio storage"
        }
        val temporary = File.createTempFile("track-", ".tmp", tracksDirectory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = openDocument(documentUri) ?: throw IOException("Cannot open $documentUri")
            input.use { source ->
                FileOutputStream(temporary).use { fileOutput ->
                    val hashingOutput = DigestOutputStream(fileOutput, digest)
                    source.copyTo(hashingOutput)
                    hashingOutput.flush()
                    fileOutput.fd.sync()
                }
            }
            val trackId = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            val destination = File(tracksDirectory, trackId)
            val added = publishWithoutOverwrite(temporary, destination)
            val track = OwnedTrack(trackId, trackId, destination.canonicalPath)
            return if (added) ImportResult.Added(track) else ImportResult.Duplicate(track)
        } finally {
            temporary.delete()
        }
    }

    private fun publishWithoutOverwrite(temporary: File, destination: File): Boolean =
        try {
            Files.createLink(destination.toPath(), temporary.toPath())
            true
        } catch (_: FileAlreadyExistsException) {
            false
        }

    fun playbackSource(storedPath: String?): WakeAudioSource {
        val imported = storedPath?.let(::File)?.takeIf { it.isFile }
        return imported?.let { WakeAudioSource.Imported(it.canonicalPath) }
            ?: WakeAudioSource.Default
    }

    private companion object {
        const val TRACKS_DIRECTORY_NAME = "tracks"
    }
}
