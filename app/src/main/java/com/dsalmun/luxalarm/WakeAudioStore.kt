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
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
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
    private val syncDirectory: (File) -> Unit = ::forceDirectory,
    private val syncFile: (FileChannel) -> Unit = { it.force(true) },
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

    class PreparedImport
    internal constructor(
        private val owner: WakeAudioStore,
        val result: ImportResult,
    ) {
        private var finished = false

        fun commit() {
            check(!finished) { "Import is already finished" }
            owner.finishImport(result, deletePublished = false)
            finished = true
        }

        fun rollback(publishedBytesAreReferenced: Boolean) {
            check(!finished) { "Import is already finished" }
            owner.finishImport(
                result,
                deletePublished = result is ImportResult.Added && !publishedBytesAreReferenced,
            )
            finished = true
        }

        /** Leaves durable marker, staging, and final evidence for a later authoritative pass. */
        fun deferToReconciliation() {
            check(!finished) { "Import is already finished" }
            finished = true
        }
    }

    data class ReconciliationReport(
        val removedStaging: Boolean,
        val removedUnreferencedTrackIds: Set<String>,
        val missingReferencedTrackIds: Set<String>,
    )

    /** Extracts an owned hash from metadata without requiring the referenced file to exist. */
    fun ownedTrackId(storedPath: String): String? {
        val supplied = runCatching { File(storedPath) }.getOrNull() ?: return null
        if (!supplied.isAbsolute) return null
        val normalized = supplied.absoluteFile.normalize()
        if (storedPath != normalized.path) return null
        val tracks = tracksDirectory().absoluteFile.normalize()
        if (normalized.parentFile != tracks || !LOWERCASE_SHA256.matches(normalized.name))
            return null
        return normalized.name
    }

    fun availability(track: OwnedTrack): TrackAvailability =
        if (safeOwnedRegularFile(track) != null) {
            TrackAvailability.Available(track)
        } else {
            TrackAvailability.Missing(track)
        }

    /** Deletes only bytes whose identity and exact app-private location are validated below. */
    fun deleteOwnedBytes(track: OwnedTrack): Boolean {
        val target = safeOwnedRegularFile(track) ?: return false
        val tracks = tracksDirectory()
        // Android exposes only path-based deletion here, not an openat-style directory capability.
        // A namespace replacement remains possible between this NOFOLLOW validation and delete.
        // The directory is app-private and the exact child is an unguessable SHA-256 name, which
        // bounds that platform limitation without claiming a SecureDirectoryStream guarantee.
        val deleted = Files.deleteIfExists(target.toPath())
        if (deleted) syncDirectory(tracks)
        return deleted
    }

    fun importDocument(documentUri: String): WakeAudioSource.Imported {
        val track = storeDocument(documentUri).track
        return WakeAudioSource.Imported(track.path)
    }

    /** Convenience API for imports that do not coordinate with metadata. */
    fun storeDocument(documentUri: String): ImportResult {
        val prepared = prepareDocument(documentUri)
        prepared.commit()
        return prepared.result
    }

    /**
     * Creates one durable pending marker and one deterministic staging file, then publishes and
     * directory-syncs a content-addressed final. The marker/staging pair remains until commit or
     * rollback so startup reconciliation can distinguish and converge every crash boundary.
     */
    @Synchronized
    fun prepareDocument(documentUri: String): PreparedImport {
        val tracks = ensureTracksDirectory()
        val marker = File(tracks, PENDING_FILE_NAME)
        val staging = File(tracks, STAGING_FILE_NAME)
        check(!marker.exists() && !staging.exists()) { "A wake audio import needs reconciliation" }
        var newlyPublished: File? = null

        try {
            FileChannel.open(
                    marker.toPath(),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                )
                .use { channel ->
                    val output = Channels.newOutputStream(channel)
                    output.write(PENDING_VERSION)
                    output.flush()
                    syncFile(channel)
                }
            syncDirectory(tracks)

            val digest = MessageDigest.getInstance("SHA-256")
            val input = openDocument(documentUri) ?: throw IOException("Cannot open $documentUri")
            input.use { source ->
                FileChannel.open(
                        staging.toPath(),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    .use { channel ->
                        val hashingOutput =
                            DigestOutputStream(Channels.newOutputStream(channel), digest)
                        source.copyTo(hashingOutput)
                        hashingOutput.flush()
                        syncFile(channel)
                    }
            }
            val trackId = digest.digest().toHex()
            val destination = File(tracks, trackId)
            val added = publishWithoutOverwrite(staging, destination)
            if (added) newlyPublished = destination
            syncDirectory(tracks)
            val track = OwnedTrack(trackId, trackId, destination.absoluteFile.normalize().path)
            return PreparedImport(
                this,
                if (added) ImportResult.Added(track) else ImportResult.Duplicate(track),
            )
        } catch (cause: Exception) {
            newlyPublished?.delete()
            staging.delete()
            marker.delete()
            runCatching { syncDirectory(tracks) }.onFailure(cause::addSuppressed)
            throw cause
        }
    }

    /** Conservatively removes bounded crash residue and only unreferenced regular final files. */
    @Synchronized
    fun reconcile(referencedTrackIds: Set<String>): ReconciliationReport {
        require(referencedTrackIds.all(LOWERCASE_SHA256::matches)) {
            "Malformed referenced track id"
        }
        val tracks = ensureTracksDirectory()
        val marker = File(tracks, PENDING_FILE_NAME)
        val staging = File(tracks, STAGING_FILE_NAME)
        var removedStaging =
            Files.deleteIfExists(staging.toPath()) or Files.deleteIfExists(marker.toPath())
        val removed = mutableSetOf<String>()

        tracks.listFiles().orEmpty().forEach { candidate ->
            val name = candidate.name
            if (isLegacyTemporary(candidate)) {
                if (Files.deleteIfExists(candidate.toPath())) removedStaging = true
            } else if (
                LOWERCASE_SHA256.matches(name) &&
                    name !in referencedTrackIds &&
                    Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(candidate.toPath())
            ) {
                if (Files.deleteIfExists(candidate.toPath())) removed += name
            }
        }
        if (removedStaging || removed.isNotEmpty()) syncDirectory(tracks)
        val missing =
            referencedTrackIds.filterTo(mutableSetOf()) { id ->
                val candidate = File(tracks, id)
                !Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(candidate.toPath())
            }
        return ReconciliationReport(removedStaging, removed, missing)
    }

    @Synchronized
    private fun finishImport(result: ImportResult, deletePublished: Boolean) {
        val tracks = tracksDirectory()
        if (deletePublished) {
            val target = safeOwnedRegularFile(result.track)
            if (target != null) Files.deleteIfExists(target.toPath())
        }
        Files.deleteIfExists(File(tracks, STAGING_FILE_NAME).toPath())
        Files.deleteIfExists(File(tracks, PENDING_FILE_NAME).toPath())
        syncDirectory(tracks)
    }

    private fun safeOwnedRegularFile(track: OwnedTrack): File? {
        if (!LOWERCASE_SHA256.matches(track.id) || track.sha256 != track.id) return null
        if (track.id.contains('/') || track.id.contains('\\') || track.id == "..") return null
        val tracks = tracksDirectory().absoluteFile.normalize()
        val tracksPath = tracks.toPath()
        if (
            Files.isSymbolicLink(tracksPath) ||
                !Files.isDirectory(tracksPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        val supplied = runCatching { File(track.path) }.getOrNull() ?: return null
        if (!supplied.isAbsolute) return null
        val normalizedSupplied = supplied.absoluteFile.normalize()
        val target = File(tracks, track.id).absoluteFile.normalize()
        if (
            normalizedSupplied != target ||
                track.path != normalizedSupplied.path ||
                target.parentFile != tracks
        ) {
            return null
        }
        val path = target.toPath()
        val attributes =
            runCatching {
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                }
                .getOrNull() ?: return null
        if (attributes.isSymbolicLink || !attributes.isRegularFile) return null
        val resolvedParent = runCatching { path.toRealPath().parent }.getOrNull() ?: return null
        val resolvedTracks = runCatching { tracksPath.toRealPath() }.getOrNull() ?: return null
        if (resolvedParent != resolvedTracks) return null
        return target
    }

    private fun ensureTracksDirectory(): File =
        tracksDirectory().also { directory ->
            val path = directory.toPath()
            check(!Files.isSymbolicLink(path)) { "Wake audio storage cannot be a symlink" }
            check(directory.mkdirs() || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "Cannot create wake audio storage"
            }
        }

    private fun tracksDirectory() = File(storageDirectory, TRACKS_DIRECTORY_NAME)

    private fun isLegacyTemporary(candidate: File): Boolean =
        candidate.name.startsWith("track-") &&
            candidate.name.endsWith(".tmp") &&
            Files.isRegularFile(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(candidate.toPath())

    private fun publishWithoutOverwrite(staging: File, destination: File): Boolean =
        try {
            Files.createLink(destination.toPath(), staging.toPath())
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
        const val PENDING_FILE_NAME = ".import.pending"
        const val STAGING_FILE_NAME = ".import.staging"
        val PENDING_VERSION = byteArrayOf(1)
        val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")

        fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

        fun forceDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
