/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.FileDescriptor
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.Test

class LegacyAudioFileIdentityPortTest {
    @Test
    fun androidFstatFailureRemainsPrimaryWhenTransferredStreamCloseFails() {
        val primary = IdentityFailure()
        val close = CloseFailure()
        val channel = FileChannel.open(createTempFile(), StandardOpenOption.READ)

        val thrown =
            assertFailsWith<IdentityFailure> {
                openAndroidLegacyAudioRead(
                    FileDescriptor(),
                    streamFactory = { TestReadStream(channel) { throw close } },
                    descriptorIdentity = { throw primary },
                    descriptorClose = { error("descriptor ownership transferred to stream") },
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(close), thrown.suppressed.toList())
    }

    @Test
    fun jvmPathIdentityFailureRemainsPrimaryWhenChannelCloseFails() {
        val primary = IdentityFailure()
        val close = CloseFailure()
        val channel =
            object : FileChannel() {
                override fun implCloseChannel() = throw close

                override fun read(dst: java.nio.ByteBuffer?) = error("not used")

                override fun read(dst: java.nio.ByteBuffer?, position: Long) = error("not used")

                override fun read(dsts: Array<out java.nio.ByteBuffer>?, offset: Int, length: Int) =
                    error("not used")

                override fun write(src: java.nio.ByteBuffer?) = error("not used")

                override fun write(src: java.nio.ByteBuffer?, position: Long) = error("not used")

                override fun write(
                    srcs: Array<out java.nio.ByteBuffer>?,
                    offset: Int,
                    length: Int,
                ) = error("not used")

                override fun position() = error("not used")

                override fun position(newPosition: Long) = error("not used")

                override fun size() = error("not used")

                override fun truncate(size: Long) = error("not used")

                override fun force(metaData: Boolean) = error("not used")

                override fun transferTo(
                    position: Long,
                    count: Long,
                    target: java.nio.channels.WritableByteChannel?,
                ) = error("not used")

                override fun transferFrom(
                    src: java.nio.channels.ReadableByteChannel?,
                    position: Long,
                    count: Long,
                ) = error("not used")

                override fun map(mode: MapMode?, position: Long, size: Long) = error("not used")

                override fun lock(position: Long, size: Long, shared: Boolean) = error("not used")

                override fun tryLock(position: Long, size: Long, shared: Boolean) =
                    error("not used")
            }

        val thrown =
            assertFailsWith<IdentityFailure> {
                openJvmLegacyAudioRead(
                    Path.of("unused"),
                    openChannel = { channel },
                    pathIdentity = { throw primary },
                )
            }

        assertSame(primary, thrown)
        assertEquals(listOf(close), thrown.suppressed.toList())
    }

    private class TestReadStream(
        override val channel: FileChannel,
        private val closeAction: () -> Unit,
    ) : LegacyAudioReadStream {
        override fun close() = closeAction()
    }

    private class IdentityFailure : RuntimeException()

    private class CloseFailure : RuntimeException()
}
