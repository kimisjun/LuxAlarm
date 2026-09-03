/*
 * Copyright (C) 2026 김은준
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

sealed interface WakeAudioSource {
    data object Default : WakeAudioSource

    data class Imported(val path: String) : WakeAudioSource
}

/** Owns a durable copy so playback never depends on the document provider remaining available. */
class WakeAudioStore(
    private val storageDirectory: File,
    private val openDocument: (String) -> InputStream?,
) {
    fun importDocument(documentUri: String): WakeAudioSource.Imported {
        check(storageDirectory.mkdirs() || storageDirectory.isDirectory) {
            "Cannot create wake audio storage"
        }
        val temporary = File.createTempFile("selected-audio-", ".tmp", storageDirectory)
        try {
            val input = openDocument(documentUri) ?: throw IOException("Cannot open $documentUri")
            input.use { source -> temporary.outputStream().use(source::copyTo) }
            val destination = File(storageDirectory, IMPORTED_FILE_NAME)
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            return WakeAudioSource.Imported(destination.canonicalPath)
        } finally {
            temporary.delete()
        }
    }

    fun playbackSource(storedPath: String?): WakeAudioSource {
        val imported = storedPath?.let(::File)?.takeIf { it.isFile }
        return imported?.let { WakeAudioSource.Imported(it.canonicalPath) }
            ?: WakeAudioSource.Default
    }

    private companion object {
        const val IMPORTED_FILE_NAME = "selected-audio"
    }
}
