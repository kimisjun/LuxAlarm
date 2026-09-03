/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Deterministic test double whose directory identity survives visible parent renames/swaps. */
internal class AnchoredTestCapabilityFactory(
    private val afterRewriteOpen: (Path) -> Unit = {},
    private val afterAcquire: (Path) -> Path = { it },
) : LegacyAudioCapabilityFactory {
    override fun acquire(
        filesDir: File,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioAttemptCapability {
        val visible = filesDir.toPath().resolve(descriptor.targetStorageKey).parent
        createDirectoriesWithoutSymlinks(filesDir.toPath(), visible)
        val acquiredPath = afterAcquire(visible)
        val acquiredKey = directoryKey(acquiredPath)

        fun currentBacking(): Path {
            val siblings = Files.list(visible.parent).use { stream -> stream.toList() }
            return (listOf(visible) + siblings).firstOrNull { candidate ->
                runCatching { directoryKey(candidate) == acquiredKey }.getOrDefault(false)
            } ?: error("Captured test directory capability disappeared")
        }

        return object : LegacyAudioAttemptCapability {
            override fun identity(name: String): LegacyAudioFileIdentity? {
                val path = currentBacking().resolve(name)
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
                val attrs =
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                require(attrs.isRegularFile && !attrs.isSymbolicLink) {
                    "Test capability entry is not regular"
                }
                return JvmLegacyAudioFileIdentityPort.pathIdentity(path)
            }

            override fun openRead(name: String): LegacyAudioOpenedRead =
                JvmLegacyAudioFileIdentityPort.openReadOnly(currentBacking().resolve(name))

            override fun createNew(name: String): FileChannel =
                FileChannel.open(
                    currentBacking().resolve(name),
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )

            override fun openRewrite(name: String, expected: LegacyAudioFileIdentity): FileChannel {
                require(identity(name)?.sameFileObjectForBootstrap(expected) == true)
                val channel =
                    FileChannel.open(
                        currentBacking().resolve(name),
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                return try {
                    afterRewriteOpen(currentBacking().resolve(name))
                    require(identity(name)?.sameFileObjectForBootstrap(expected) == true)
                    channel.truncate(0)
                    channel.position(0)
                    channel
                } catch (failure: Throwable) {
                    try {
                        channel.close()
                    } catch (closeFailure: Throwable) {
                        failure.addSuppressed(closeFailure)
                    }
                    throw failure
                }
            }

            override fun forceDirectory(durability: LegacyAudioDurabilityPort) {
                FileChannel.open(currentBacking(), StandardOpenOption.READ).use {
                    durability.forceDirectory(it)
                }
            }

            override fun close() = Unit
        }
    }

    private fun createDirectoriesWithoutSymlinks(root: Path, destination: Path) {
        var current = root
        root.relativize(destination).forEach { component ->
            current = current.resolve(component)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current)
            val attrs =
                Files.readAttributes(
                    current,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            require(attrs.isDirectory && !attrs.isSymbolicLink)
        }
    }

    private fun directoryKey(path: Path): Any {
        val attrs =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink)
        return requireNotNull(attrs.fileKey())
    }
}

internal class RecordingLegacyAudioCapabilityFactory(
    private val events: MutableList<String>,
    private val failDirectoryForceNumber: Int? = null,
    private val directoryForceFailure: Throwable = IOException("directory force"),
    private val delegate: LegacyAudioCapabilityFactory = AnchoredTestCapabilityFactory(),
) : LegacyAudioCapabilityFactory {
    override fun acquire(
        filesDir: File,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioAttemptCapability {
        val acquired = delegate.acquire(filesDir, descriptor)
        var directoryForces = 0
        return object : LegacyAudioAttemptCapability {
            override fun identity(name: String) = acquired.identity(name)

            override fun openRead(name: String) = acquired.openRead(name)

            override fun createNew(name: String): FileChannel {
                events += "create:$name"
                return acquired.createNew(name)
            }

            override fun openRewrite(name: String, expected: LegacyAudioFileIdentity): FileChannel {
                events += "rewrite:$name"
                return acquired.openRewrite(name, expected)
            }

            override fun forceDirectory(durability: LegacyAudioDurabilityPort) {
                directoryForces++
                events += "forceDirectory:$directoryForces"
                if (directoryForces == failDirectoryForceNumber) throw directoryForceFailure
                acquired.forceDirectory(durability)
            }

            override fun close() = acquired.close()
        }
    }
}

internal fun legacyAudioTestReconciler(
    filesDir: File,
    state: LegacyAudioBootstrapStatePort,
    decoder: LegacyAudioDecoder,
    faults: LegacyAudioBootstrapFaultInjector = LegacyAudioBootstrapFaultInjector.NONE,
    durability: LegacyAudioDurabilityPort = JvmLegacyAudioDurability(),
    identities: LegacyAudioFileIdentityPort = JvmLegacyAudioFileIdentityPort,
    capabilities: LegacyAudioCapabilityFactory = AnchoredTestCapabilityFactory(),
) =
    LegacyAudioBootstrapReconciler(
        filesDir,
        state,
        decoder,
        faults,
        durability,
        identities,
        capabilities,
    )
