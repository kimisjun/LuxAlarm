/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/**
 * A retained token-directory capability. Every mutable entry operation is relative to this handle.
 */
internal interface LegacyAudioAttemptCapability : AutoCloseable {
    fun identity(name: String): LegacyAudioFileIdentity?

    fun openRead(name: String): LegacyAudioOpenedRead

    fun createNew(name: String): FileChannel

    fun openRewrite(name: String, expected: LegacyAudioFileIdentity): FileChannel

    fun forceDirectory(durability: LegacyAudioDurabilityPort)
}

internal fun interface LegacyAudioCapabilityFactory {
    fun acquire(
        filesDir: File,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioAttemptCapability
}

internal object PlatformLegacyAudioCapabilityFactory : LegacyAudioCapabilityFactory {
    private val delegate: LegacyAudioCapabilityFactory by lazy {
        if (System.getProperty("java.vm.name").equals("Dalvik", ignoreCase = true)) {
            AndroidFdLegacyAudioCapabilityFactory()
        } else {
            SecureDirectoryLegacyAudioCapabilityFactory
        }
    }

    override fun acquire(filesDir: File, descriptor: LegacyAudioBootstrapDescriptor) =
        delegate.acquire(filesDir, descriptor)
}

/**
 * Android API 28+ implementation. All namespace traversal after the root open is through
 * `/proc/self/fd/<retained-fd>/<relative>`; Android's proc-fd mount is therefore a required
 * production dependency and acquisition fails before mkdir if it is unavailable.
 */
internal class AndroidFdLegacyAudioCapabilityFactory(
    private val os: AndroidOsFacade = PlatformAndroidOsFacade
) : LegacyAudioCapabilityFactory {
    override fun acquire(
        filesDir: File,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioAttemptCapability {
        validateStorageKey(descriptor)
        var root: AndroidFd? = null
        var bootstrap: AndroidFd? = null
        var token: AndroidFd? = null
        try {
            root = os.open(filesDir.absolutePath, DIRECTORY_OPEN_FLAGS, 0)
            require(root.stat().isDirectory) { "filesDir descriptor is not a directory" }

            // Prove proc-fd traversal works against the retained root before any namespace
            // mutation.
            os.open(procPath(root, "."), DIRECTORY_OPEN_FLAGS, 0).use { probe ->
                require(probe.stat().sameObject(root.stat())) { "proc-fd root identity mismatch" }
            }

            mkdirOrRequireDirectory(root, "bootstrap")
            bootstrap = os.open(procPath(root, "bootstrap"), DIRECTORY_OPEN_FLAGS, 0)
            require(bootstrap.stat().isDirectory) { "bootstrap is not a directory" }
            root.fsync()

            mkdirOrRequireDirectory(bootstrap, descriptor.attemptToken)
            token = os.open(procPath(bootstrap, descriptor.attemptToken), DIRECTORY_OPEN_FLAGS, 0)
            require(token.stat().isDirectory) { "attempt token is not a directory" }
            bootstrap.fsync()

            val retained = token
            var releaseFailure: Throwable? = null
            val closingBootstrap = bootstrap
            bootstrap = null
            try {
                closingBootstrap.close()
            } catch (failure: Throwable) {
                releaseFailure = failure
            }
            val closingRoot = root
            root = null
            try {
                closingRoot.close()
            } catch (failure: Throwable) {
                val primary = releaseFailure
                if (primary == null) releaseFailure = failure else primary.addSuppressed(failure)
            }
            releaseFailure?.let { throw it }
            token = null
            return AndroidAttemptCapability(os, retained)
        } catch (failure: Throwable) {
            closeAllSuppressing(failure, token, bootstrap, root)
            throw failure
        }
    }

    private fun mkdirOrRequireDirectory(parent: AndroidFd, name: String) {
        val path = procPath(parent, safeName(name))
        try {
            os.mkdir(path, DIRECTORY_MODE)
        } catch (failure: Throwable) {
            if (!os.isAlreadyExists(failure)) throw failure
        }
        val opened = os.open(path, DIRECTORY_OPEN_FLAGS, 0)
        opened.use { require(it.stat().isDirectory) { "$name is not a directory" } }
    }

    private class AndroidAttemptCapability(
        private val os: AndroidOsFacade,
        private val token: AndroidFd,
    ) : LegacyAudioAttemptCapability {
        override fun identity(name: String): LegacyAudioFileIdentity? {
            val path = procPath(token, safeName(name))
            val stat =
                try {
                    os.lstat(path)
                } catch (failure: Throwable) {
                    if (os.isNoEntry(failure)) return null
                    throw failure
                }
            require(stat.isRegular) { "Capability entry is not a regular file" }
            return stat.identity
        }

        override fun openRead(name: String): LegacyAudioOpenedRead {
            val path = procPath(token, safeName(name))
            val before = requireNotNull(identity(name)) { "Missing capability entry" }
            val fd = os.open(path, READ_OPEN_FLAGS, 0)
            try {
                val opened = fd.stat()
                require(opened.isRegular && opened.identity.sameFileObjectForBootstrap(before)) {
                    "Capability entry identity changed while opening"
                }
                require(os.lstat(path).identity.sameFileObjectForBootstrap(before)) {
                    "Capability entry identity changed while opening"
                }
                return fd.takeRead()
            } catch (failure: Throwable) {
                closeAllSuppressing(failure, fd)
                throw failure
            }
        }

        override fun createNew(name: String): FileChannel {
            val path = procPath(token, safeName(name))
            val fd =
                try {
                    os.open(path, WRITE_CREATE_FLAGS, FILE_MODE)
                } catch (failure: Throwable) {
                    if (os.isAlreadyExists(failure)) throw FileAlreadyExistsException(name)
                    throw failure
                }
            try {
                require(fd.stat().isRegular) { "Created capability entry is not regular" }
                return fd.takeWrite()
            } catch (failure: Throwable) {
                closeAllSuppressing(failure, fd)
                throw failure
            }
        }

        override fun openRewrite(name: String, expected: LegacyAudioFileIdentity): FileChannel {
            val path = procPath(token, safeName(name))
            require(identity(name)?.sameFileObjectForBootstrap(expected) == true) {
                "Owned slot identity changed before rewrite"
            }
            val fd = os.open(path, WRITE_OPEN_FLAGS, 0)
            try {
                val opened = fd.stat()
                require(opened.isRegular && opened.identity.sameFileObjectForBootstrap(expected)) {
                    "Owned slot identity changed while opening for rewrite"
                }
                require(os.lstat(path).identity.sameFileObjectForBootstrap(expected)) {
                    "Owned slot name changed while opening for rewrite"
                }
                val channel = fd.takeWrite()
                return try {
                    channel.truncate(0)
                    channel.position(0)
                    channel
                } catch (failure: Throwable) {
                    closeAllSuppressing(failure, channel)
                    throw failure
                }
            } catch (failure: Throwable) {
                closeAllSuppressing(failure, fd)
                throw failure
            }
        }

        override fun forceDirectory(durability: LegacyAudioDurabilityPort) = token.fsync()

        override fun close() = token.close()
    }

    private companion object {
        const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        // Linux UAPI O_DIRECTORY (octal 00200000); absent from the public Android SDK constants.
        const val ANDROID_O_DIRECTORY = 0x10000
        val DIRECTORY_OPEN_FLAGS =
            OsConstants.O_RDONLY or
                ANDROID_O_DIRECTORY or
                OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW
        val READ_OPEN_FLAGS =
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW
        val WRITE_OPEN_FLAGS =
            OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW
        val WRITE_CREATE_FLAGS = WRITE_OPEN_FLAGS or OsConstants.O_CREAT or OsConstants.O_EXCL
    }
}

internal data class AndroidFdStat(
    val identity: LegacyAudioFileIdentity,
    val isDirectory: Boolean,
    val isRegular: Boolean,
) {
    fun sameObject(other: AndroidFdStat) = identity.sameFileObjectForBootstrap(other.identity)
}

internal interface AndroidFd : AutoCloseable {
    val number: Int

    fun stat(): AndroidFdStat

    fun fsync()

    fun takeRead(): LegacyAudioOpenedRead

    fun takeWrite(): FileChannel
}

internal interface AndroidOsFacade {
    fun open(path: String, flags: Int, mode: Int): AndroidFd

    fun mkdir(path: String, mode: Int)

    fun lstat(path: String): AndroidFdStat

    fun isNoEntry(failure: Throwable): Boolean

    fun isAlreadyExists(failure: Throwable): Boolean
}

internal object PlatformAndroidOsFacade : AndroidOsFacade {
    override fun open(path: String, flags: Int, mode: Int): AndroidFd {
        val raw = Os.open(path, flags, mode)
        val pfd =
            try {
                ParcelFileDescriptor.dup(raw)
            } catch (failure: Throwable) {
                try {
                    Os.close(raw)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        try {
            Os.close(raw)
        } catch (failure: Throwable) {
            try {
                pfd.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return ParcelAndroidFd(pfd)
    }

    override fun mkdir(path: String, mode: Int) = Os.mkdir(path, mode)

    override fun lstat(path: String) = Os.lstat(path).toAndroidFdStat()

    override fun isNoEntry(failure: Throwable) =
        failure is ErrnoException && failure.errno == OsConstants.ENOENT

    override fun isAlreadyExists(failure: Throwable) =
        failure is ErrnoException && failure.errno == OsConstants.EEXIST

    private class ParcelAndroidFd(private val pfd: ParcelFileDescriptor) : AndroidFd {
        private var transferred = false

        override val number: Int
            get() = pfd.fd

        override fun stat() = Os.fstat(pfd.fileDescriptor).toAndroidFdStat()

        override fun fsync() = Os.fsync(pfd.fileDescriptor)

        override fun takeRead(): LegacyAudioOpenedRead {
            check(!transferred) { "Descriptor ownership already transferred" }
            val identity = stat().identity
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            transferred = true
            return LegacyAudioOpenedRead(stream.channel, identity) { stream.close() }
        }

        override fun takeWrite(): FileChannel {
            check(!transferred) { "Descriptor ownership already transferred" }
            val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
            transferred = true
            return stream.channel
        }

        override fun close() {
            if (!transferred) pfd.close()
        }
    }

    private fun android.system.StructStat.toAndroidFdStat() =
        AndroidFdStat(
            LegacyAudioFileIdentity(st_dev, st_ino, null, st_size, st_mtime * 1000L),
            OsConstants.S_ISDIR(st_mode),
            OsConstants.S_ISREG(st_mode),
        )
}

/** Host-JVM fallback. It never creates directories; tests needing mutation inject a capability. */
internal object SecureDirectoryLegacyAudioCapabilityFactory : LegacyAudioCapabilityFactory {
    override fun acquire(
        filesDir: File,
        descriptor: LegacyAudioBootstrapDescriptor,
    ): LegacyAudioAttemptCapability {
        validateStorageKey(descriptor)
        val rootPath = filesDir.toPath().toAbsolutePath().normalize()
        requireDirectory(rootPath)
        val root = openSecure(rootPath)
        var bootstrap: SecureDirectoryStream<Path>? = null
        var token: SecureDirectoryStream<Path>? = null
        var directoryChannel: FileChannel? = null
        try {
            bootstrap = root.newDirectoryStream(Paths.get("bootstrap"), LinkOption.NOFOLLOW_LINKS)
            token =
                bootstrap.newDirectoryStream(
                    Paths.get(descriptor.attemptToken),
                    LinkOption.NOFOLLOW_LINKS,
                )
            requireSecureDirectory(token, Paths.get("."))
            directoryChannel =
                FileChannel.open(
                    rootPath.resolve("bootstrap").resolve(descriptor.attemptToken),
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                )
            return SecureAttemptCapability(root, bootstrap, token, directoryChannel)
        } catch (failure: Throwable) {
            closeAllSuppressing(failure, directoryChannel, token, bootstrap, root)
            throw failure
        }
    }

    private fun openSecure(path: Path): SecureDirectoryStream<Path> {
        val stream: DirectoryStream<Path> = Files.newDirectoryStream(path)
        if (stream !is SecureDirectoryStream<Path>) {
            stream.close()
            throw UnsupportedOperationException("Filesystem provider lacks SecureDirectoryStream")
        }
        return stream
    }

    private fun requireDirectory(path: Path) {
        val attrs =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isDirectory && !attrs.isSymbolicLink && attrs.fileKey() != null) {
            "Path component must be a stable real directory"
        }
    }

    private fun requireSecureDirectory(directory: SecureDirectoryStream<Path>, name: Path) {
        val attrs =
            directory
                .getFileAttributeView(
                    name,
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                .readAttributes()
        require(attrs.isDirectory && !attrs.isSymbolicLink && attrs.fileKey() != null) {
            "Token capability must reference a stable real directory"
        }
    }

    private class SecureAttemptCapability(
        private val root: SecureDirectoryStream<Path>,
        private val bootstrap: SecureDirectoryStream<Path>,
        private val token: SecureDirectoryStream<Path>,
        private val directoryChannel: FileChannel,
    ) : LegacyAudioAttemptCapability {
        override fun identity(name: String): LegacyAudioFileIdentity? =
            try {
                val attrs =
                    token
                        .getFileAttributeView(
                            safePath(name),
                            BasicFileAttributeView::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        .readAttributes()
                require(attrs.isRegularFile && !attrs.isSymbolicLink) {
                    "Capability entry is not regular"
                }
                LegacyAudioFileIdentity(
                    null,
                    null,
                    attrs.fileKey(),
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                )
            } catch (_: NoSuchFileException) {
                null
            }

        override fun openRead(name: String): LegacyAudioOpenedRead {
            val before = requireNotNull(identity(name)) { "Missing capability entry" }
            val raw =
                token.newByteChannel(
                    safePath(name),
                    setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                )
            val channel = requireFileChannel(raw)
            try {
                require(identity(name)?.sameFileObjectForBootstrap(before) == true) {
                    "Capability entry changed while opening"
                }
                return LegacyAudioOpenedRead(channel, before) { channel.close() }
            } catch (failure: Throwable) {
                closeAllSuppressing(failure, channel)
                throw failure
            }
        }

        override fun createNew(name: String): FileChannel {
            val raw =
                token.newByteChannel(
                    safePath(name),
                    setOf<OpenOption>(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS,
                    ),
                )
            return requireFileChannel(raw)
        }

        override fun openRewrite(name: String, expected: LegacyAudioFileIdentity): FileChannel {
            require(identity(name)?.sameFileObjectForBootstrap(expected) == true) {
                "Owned slot identity changed before rewrite"
            }
            val raw =
                token.newByteChannel(
                    safePath(name),
                    setOf<OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                )
            val channel = requireFileChannel(raw)
            try {
                require(identity(name)?.sameFileObjectForBootstrap(expected) == true) {
                    "Owned slot identity changed while opening for rewrite"
                }
                channel.truncate(0)
                channel.position(0)
                return channel
            } catch (failure: Throwable) {
                closeAllSuppressing(failure, channel)
                throw failure
            }
        }

        override fun forceDirectory(durability: LegacyAudioDurabilityPort) =
            durability.forceDirectory(directoryChannel)

        override fun close() = closeAllOrThrow(directoryChannel, token, bootstrap, root)
    }
}

private fun requireFileChannel(channel: SeekableByteChannel): FileChannel {
    if (channel is FileChannel) return channel
    try {
        channel.close()
    } catch (failure: Throwable) {
        throw UnsupportedOperationException(
            "Secure provider does not expose a FileChannel",
            failure,
        )
    }
    throw UnsupportedOperationException("Secure provider does not expose a FileChannel")
}

private fun validateStorageKey(descriptor: LegacyAudioBootstrapDescriptor) {
    val key = Paths.get(descriptor.targetStorageKey)
    require(!key.isAbsolute && key.normalize() == key && key.nameCount == 3) {
        "Invalid storage key path"
    }
    require(key.getName(0).toString() == "bootstrap") { "Invalid storage key root" }
    require(key.getName(1).toString() == descriptor.attemptToken) { "Storage key token conflict" }
    require(key.fileName.toString() == "legacy-audio") { "Invalid storage key file" }
}

private fun safeName(name: String): String {
    val path = Paths.get(name)
    require(!path.isAbsolute && path.nameCount == 1 && path.toString() !in setOf("", ".", "..")) {
        "Capability entry name must be a single relative component"
    }
    return path.toString()
}

private fun safePath(name: String): Path = Paths.get(safeName(name))

private fun procPath(parent: AndroidFd, name: String) = "/proc/self/fd/${parent.number}/$name"

private fun closeAllSuppressing(primary: Throwable, vararg resources: AutoCloseable?) {
    resources.forEach { resource ->
        try {
            resource?.close()
        } catch (failure: Throwable) {
            primary.addSuppressed(failure)
        }
    }
}

private fun closeAllOrThrow(vararg resources: AutoCloseable?) {
    var failure: Throwable? = null
    resources.forEach { resource ->
        try {
            resource?.close()
        } catch (closeFailure: Throwable) {
            val primary = failure
            if (primary == null) failure = closeFailure else primary.addSuppressed(closeFailure)
        }
    }
    failure?.let { throw it }
}

internal fun LegacyAudioFileIdentity.sameFileObjectForBootstrap(
    other: LegacyAudioFileIdentity
): Boolean =
    if (device != null && inode != null && other.device != null && other.inode != null) {
        device == other.device && inode == other.inode
    } else {
        fileKey != null && fileKey == other.fileKey
    }
