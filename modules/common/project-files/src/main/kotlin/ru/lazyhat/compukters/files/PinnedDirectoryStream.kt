/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.files

import java.nio.channels.SeekableByteChannel
import java.nio.file.ClosedDirectoryStreamException
import java.nio.file.DirectoryStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NotDirectoryException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime

internal interface EntryPin : AutoCloseable {
    val directory: Boolean
    val reparse: Boolean get() = false
    val identity: Any? get() = null
}

internal fun interface EntryPinning {
    /** Opens the entry itself without following reparse points; denies write/delete until close. */
    fun open(path: Path): EntryPin
}

/**
 * Windows direct-entry stream. Every operation retains the complete pinned ancestor chain.
 * It deliberately supports only no-follow reads and exclusive new-file writes used by projects.
 * There is no path-check-then-open fallback when native pinning fails.
 */
internal class PinnedDirectoryStream private constructor(
    private val path: Path,
    private val pinning: EntryPinning,
    private val chain: PinChain,
) : SecureDirectoryStream<Path> {
    private var closed = false
    private var listing: DirectoryStream<Path>? = null

    private class PinChain(
        private val pin: EntryPin,
        private val parent: PinChain?,
    ) {
        private var references = 1

        @Synchronized
        fun retain(): PinChain {
            check(references > 0) { "directory pin is closed" }
            references++
            return this
        }

        fun release() {
            val dispose =
                synchronized(this) {
                    check(references > 0)
                    --references == 0
                }
            if (dispose) {
                try {
                    pin.close()
                } finally {
                    parent?.release()
                }
            }
        }
    }

    private fun <T> pinned(action: () -> T): T {
        synchronized(this) {
            if (closed) throw ClosedDirectoryStreamException()
            chain.retain()
        }
        try {
            return action()
        } finally {
            chain.release()
        }
    }

    private fun entry(name: Path): Path {
        if (name.fileSystem != path.fileSystem) throw ProviderMismatchException()
        val text = name.toString()
        require(!name.isAbsolute && name.nameCount == 1 && text != "." && text != ".." && text.isNotEmpty()) {
            "expected a direct project entry"
        }
        require(text.none { it.code < 32 || it in "<>:\"/\\|?*" } && !text.endsWith('.') && !text.endsWith(' ')) {
            "ambiguous Windows project entry"
        }
        require(!DEVICE_NAME.matches(text.substringBefore('.').trimEnd(' '))) { "Windows device names are not project entries" }
        return path.resolve(name)
    }

    override fun newDirectoryStream(
        name: Path,
        vararg options: LinkOption,
    ): SecureDirectoryStream<Path> =
        pinned {
            require(options.all { it == LinkOption.NOFOLLOW_LINKS })
            val childPath = entry(name)
            val child = pinning.open(childPath)
            if (!child.directory || child.reparse) {
                child.close()
                throw NotDirectoryException(childPath.toString())
            }
            PinnedDirectoryStream(childPath, pinning, PinChain(child, chain.retain()))
        }

    override fun newByteChannel(
        name: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel =
        pinned {
            val target = entry(name)
            val access = options - LinkOption.NOFOLLOW_LINKS
            when (access) {
                setOf(StandardOpenOption.READ), emptySet<OpenOption>() -> {
                    pinning.open(target).use { pin ->
                        if (pin.directory || pin.reparse) throw FileSystemException(target.toString(), null, "expected a regular file")
                        Files.newByteChannel(target, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), *attrs)
                    }
                }

                setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW) -> {
                    Files.newByteChannel(target, options + LinkOption.NOFOLLOW_LINKS, *attrs)
                }

                else -> {
                    throw UnsupportedOperationException("project channels support read or exclusive creation only")
                }
            }
        }

    override fun deleteFile(name: Path) =
        pinned {
            val target = entry(name)
            pinning.open(target).use { pin ->
                if (pin.directory && !pin.reparse) throw FileSystemException(target.toString(), null, "expected a file")
            }
            // Delete the directory entry itself; never follow it, even if it changes after inspection.
            Files.delete(target)
        }

    override fun deleteDirectory(name: Path) =
        pinned {
            val target = entry(name)
            pinning.open(target).use { pin -> if (!pin.directory) throw NotDirectoryException(target.toString()) }
            Files.delete(target)
        }

    override fun move(
        source: Path,
        targetDirectory: SecureDirectoryStream<Path>,
        target: Path,
    ) {
        val destination = targetDirectory as? PinnedDirectoryStream ?: throw ProviderMismatchException()
        pinned { destination.pinned { Files.move(entry(source), destination.entry(target)) } }
    }

    override fun <V : FileAttributeView?> getFileAttributeView(type: Class<V>): V? = view(type, null)

    override fun <V : FileAttributeView?> getFileAttributeView(
        name: Path,
        type: Class<V>,
        vararg options: LinkOption,
    ): V? {
        require(options.all { it == LinkOption.NOFOLLOW_LINKS })
        return view(type, name)
    }

    private fun <V : FileAttributeView?> view(
        type: Class<V>,
        name: Path?,
    ): V? {
        if (type != BasicFileAttributeView::class.java) return null
        return type.cast(
            object : BasicFileAttributeView {
                override fun name(): String = "basic"

                override fun readAttributes(): BasicFileAttributes =
                    pinned {
                        val target = if (name == null) path else entry(name)
                        pinning.open(target).use { pin ->
                            val attributes = Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            object : BasicFileAttributes by attributes {
                                // Windows junctions are reparse directories, not Java symlinks.
                                // Expose them as special entries: scans reject them, while
                                // cleanup can delete the link itself without entering it.
                                override fun isDirectory(): Boolean = attributes.isDirectory && !pin.reparse

                                override fun isRegularFile(): Boolean = attributes.isRegularFile && !pin.reparse

                                override fun isOther(): Boolean = attributes.isOther || pin.reparse

                                override fun fileKey(): Any? = pin.identity ?: attributes.fileKey()
                            }
                        }
                    }

                override fun setTimes(
                    lastModifiedTime: FileTime?,
                    lastAccessTime: FileTime?,
                    createTime: FileTime?,
                ): Unit = throw UnsupportedOperationException("project attribute views are read-only")
            },
        )
    }

    @Synchronized
    override fun iterator(): MutableIterator<Path> {
        if (closed) throw ClosedDirectoryStreamException()
        check(listing == null) { "directory iterator already obtained" }
        val directory = Files.newDirectoryStream(path)
        listing = directory
        return directory.iterator()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            listing?.close()
        } finally {
            chain.release()
        }
    }

    companion object {
        private val DEVICE_NAME = Regex("(?iu)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9¹²³]|LPT[0-9¹²³])")

        fun open(
            path: Path,
            pinning: EntryPinning,
        ): PinnedDirectoryStream {
            val pin = pinning.open(path)
            if (!pin.directory || pin.reparse) {
                pin.close()
                throw NotDirectoryException(path.toString())
            }
            return PinnedDirectoryStream(path, pinning, PinChain(pin, null))
        }
    }
}
