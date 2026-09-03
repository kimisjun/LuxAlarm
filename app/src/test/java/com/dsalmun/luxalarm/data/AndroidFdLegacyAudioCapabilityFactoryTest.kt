/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.nio.channels.FileChannel
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class AndroidFdLegacyAudioCapabilityFactoryTest {
    @Test
    fun acquisitionAndDirectoryFsyncStayAnchoredToRetainedDescriptorsAfterVisibleSwap() {
        val root = createTempDirectory("android-fd-root-").toFile()
        val source = File(root, "source.mp3").apply { writeText("audio") }
        val descriptor = descriptor(source)
        val os = RecordingAndroidOsFacade(root.absolutePath)

        AndroidFdLegacyAudioCapabilityFactory(os).acquire(root, descriptor).use { capability ->
            capability.forceDirectory(JvmLegacyAudioDurability())
        }

        assertTrue(os.mkdirPaths.all { it.startsWith("/proc/self/fd/") })
        assertEquals(2, os.mkdirPaths.size)
        assertTrue(os.openPaths.drop(1).all { it.startsWith("/proc/self/fd/") })
        assertEquals(os.tokenFd, os.fsynced.last())
        assertEquals("outside sentinel", os.outsideSentinel)
        assertEquals(os.openedFds.sorted(), os.closedFds.sorted())
    }

    @Test
    fun partialRootCloseFailureNeverClosesAnyDescriptorTwice() {
        val root = createTempDirectory("android-fd-close-").toFile()
        val source = File(root, "source.mp3").apply { writeText("audio") }
        val os = RecordingAndroidOsFacade(root.absolutePath, failCloseFds = setOf(13))

        val failure =
            assertFailsWith<CloseFailure> {
                AndroidFdLegacyAudioCapabilityFactory(os).acquire(root, descriptor(source))
            }

        assertSame(os.closeFailures.single(), failure)
        assertEquals(os.openedFds.sorted(), os.closedFds.sorted())
        assertTrue(os.closedFds.groupingBy { it }.eachCount().values.all { it == 1 })
    }

    @Test
    fun rootReleaseKeepsFirstCloseFailureAndSuppressesLaterFailuresInCloseOrder() {
        val root = createTempDirectory("android-fd-close-order-").toFile()
        val source = File(root, "source.mp3").apply { writeText("audio") }
        val os = RecordingAndroidOsFacade(root.absolutePath, failCloseFds = setOf(10, 13, 15))

        val failure =
            assertFailsWith<CloseFailure> {
                AndroidFdLegacyAudioCapabilityFactory(os).acquire(root, descriptor(source))
            }

        assertEquals(13, failure.fd)
        assertEquals(listOf(10, 15), failure.suppressed.map { (it as CloseFailure).fd })
        assertTrue(os.closedFds.groupingBy { it }.eachCount().values.all { it == 1 })
    }

    private fun descriptor(source: File): LegacyAudioBootstrapDescriptor {
        val fingerprint = "legacy-canonical-v1:${"a".repeat(64)}:${"b".repeat(64)}"
        val token = legacyDiscoveryAttemptToken("install-A", fingerprint)
        return LegacyAudioBootstrapDescriptorFixtureFactory.create(
            LegacyAudioBootstrapEvidence(
                "LEGACY",
                "install-A",
                fingerprint,
                token,
                "bootstrap/$token/legacy-audio",
                BootstrapPhase.DISCOVERED.name,
            ),
            source.path,
        ) {
            LegacyAudioSourceSnapshot(source.path, fingerprint)
        }
    }

    private class RecordingAndroidOsFacade(
        private val rootPath: String,
        private val failCloseFds: Set<Int> = emptySet(),
    ) : AndroidOsFacade {
        val openPaths = mutableListOf<String>()
        val mkdirPaths = mutableListOf<String>()
        val openedFds = mutableListOf<Int>()
        val closedFds = mutableListOf<Int>()
        val fsynced = mutableListOf<Int>()
        val closeFailures = mutableListOf<CloseFailure>()
        var outsideSentinel = "outside sentinel"
        var tokenFd = -1
        private var nextFd = 10
        private val rootIdentity = identity(1)
        private val bootstrapIdentity = identity(2)
        private val tokenIdentity = identity(3)

        override fun open(path: String, flags: Int, mode: Int): AndroidFd {
            openPaths += path
            val stat =
                when {
                    path == rootPath -> rootIdentity
                    path.endsWith("/.") -> rootIdentity
                    path.endsWith("/bootstrap") -> bootstrapIdentity
                    else -> tokenIdentity
                }
            val fd = nextFd++
            if (stat == tokenIdentity) tokenFd = fd
            openedFds += fd
            return object : AndroidFd {
                override val number = fd

                override fun stat() = stat

                override fun fsync() {
                    fsynced += fd
                }

                override fun takeRead(): LegacyAudioOpenedRead = error("not used")

                override fun takeWrite(): FileChannel = error("not used")

                override fun close() {
                    closedFds += fd
                    if (fd in failCloseFds) {
                        val failure = CloseFailure(fd)
                        closeFailures += failure
                        throw failure
                    }
                }
            }
        }

        override fun mkdir(path: String, mode: Int) {
            mkdirPaths += path
            // Model a visible root/bootstrap swap: only a raw path would touch the sentinel.
            if (!path.startsWith("/proc/self/fd/")) outsideSentinel = "modified"
        }

        override fun lstat(path: String): AndroidFdStat = error("not used")

        override fun isNoEntry(failure: Throwable) = false

        override fun isAlreadyExists(failure: Throwable) = false

        private fun identity(inode: Long) =
            AndroidFdStat(
                LegacyAudioFileIdentity(7, inode, null, 0, 0),
                isDirectory = true,
                isRegular = false,
            )
    }

    private class CloseFailure(val fd: Int) : RuntimeException()
}
