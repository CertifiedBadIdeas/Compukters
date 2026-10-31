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
import java.nio.file.FileSystemException
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

/**
 * Windows handle-relative stream. Every operation retains the complete pinned ancestor chain.
 * It deliberately supports only no-follow reads and exclusive new-file writes used by projects.
 * Directory handles deny deletion but admit writes: all entry lookup, I/O, enumeration and mutation use the open handle.
 */
internal class PinnedDirectoryStream private constructor(
    private val path: Path,
    private val chain: PinChain,
) : ProjectDirectoryStream {
    private var closed = false
    private var iterated = false

    private class PinChain(
        val pin: WindowsEntryPinning.Handle,
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
        validateName(name)
        return path.resolve(name)
    }

    override fun newDirectoryStream(
        name: Path,
        vararg options: LinkOption,
    ): SecureDirectoryStream<Path> =
        pinned {
            require(options.all { it == LinkOption.NOFOLLOW_LINKS })
            val childPath = entry(name)
            val child = WindowsEntryPinning.child(chain.pin, name, directory = true)
            try {
                if (!WindowsEntryPinning.attributes(child).isDirectory) throw NotDirectoryException(childPath.toString())
                PinnedDirectoryStream(childPath, PinChain(child, chain.retain()))
            } catch (failure: Throwable) {
                child.close()
                throw failure
            }
        }

    override fun newByteChannel(
        name: Path,
        options: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>,
    ): SeekableByteChannel =
        pinned {
            val target = entry(name)
            val access = options - LinkOption.NOFOLLOW_LINKS
            require(attrs.isEmpty()) { "custom file creation attributes are unsupported" }
            val writing =
                when (access) {
                    setOf(StandardOpenOption.READ), emptySet<OpenOption>() -> false
                    setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW) -> true
                    else -> throw UnsupportedOperationException("project channels support read or exclusive creation only")
                }
            val handle = WindowsEntryPinning.child(chain.pin, name, directory = false, create = writing, writing = writing)
            try {
                if (!WindowsEntryPinning
                        .attributes(
                            handle,
                        ).isRegularFile
                ) {
                    throw FileSystemException(target.toString(), null, "expected a regular file")
                }
                WindowsProjectFileChannel(handle, writing)
            } catch (failure: Throwable) {
                handle.close()
                throw failure
            }
        }

    override fun createDirectory(name: Path) =
        pinned {
            entry(name)
            WindowsEntryPinning.child(chain.pin, name, directory = true, create = true).close()
        }

    override fun deleteFile(name: Path) =
        pinned {
            entry(name)
            WindowsEntryPinning.child(chain.pin, name, deleting = true).use { handle ->
                if (WindowsEntryPinning
                        .attributes(
                            handle,
                        ).isDirectory
                ) {
                    throw FileSystemException(path.resolve(name).toString(), null, "expected a file")
                }
                WindowsEntryPinning.delete(handle)
            }
        }

    override fun deleteDirectory(name: Path) =
        pinned {
            entry(name)
            WindowsEntryPinning.child(chain.pin, name, deleting = true).use { handle ->
                val attributes = WindowsEntryPinning.attributes(handle)
                if (!attributes.isDirectory && !attributes.isOther) throw NotDirectoryException(path.resolve(name).toString())
                WindowsEntryPinning.delete(handle)
            }
        }

    override fun move(
        source: Path,
        targetDirectory: SecureDirectoryStream<Path>,
        target: Path,
    ) = move(source, targetDirectory, target, false)

    override fun atomicReplace(
        source: Path,
        target: Path,
    ) = move(source, this, target, true)

    private fun move(
        source: Path,
        targetDirectory: SecureDirectoryStream<Path>,
        target: Path,
        replace: Boolean,
    ) {
        val destination = targetDirectory as? PinnedDirectoryStream ?: throw ProviderMismatchException()
        pinned {
            destination.pinned {
                entry(source)
                destination.entry(target)
                WindowsEntryPinning
                    .child(
                        chain.pin,
                        source,
                        deleting = true,
                    ).use { WindowsEntryPinning.move(it, destination.chain.pin, target, replace) }
            }
        }
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
                        if (name == null) {
                            WindowsEntryPinning.attributes(chain.pin)
                        } else {
                            entry(name)
                            WindowsEntryPinning.child(chain.pin, name).use(WindowsEntryPinning::attributes)
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
        check(!iterated) { "directory iterator already obtained" }
        iterated = true
        return object : MutableIterator<Path> {
            private var batch: Iterator<String> = emptyList<String>().iterator()
            private var started = false
            private var ended = false

            override fun hasNext(): Boolean =
                pinned {
                    while (!batch.hasNext() && !ended) {
                        val names = WindowsEntryPinning.names(chain.pin, !started)
                        started = true
                        ended = names == null
                        batch = names.orEmpty().iterator()
                    }
                    batch.hasNext()
                }

            override fun next(): Path {
                if (!hasNext()) throw NoSuchElementException()
                return path.resolve(batch.next())
            }

            override fun remove(): Unit = throw UnsupportedOperationException()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        chain.release()
    }

    companion object {
        fun validateName(name: Path) {
            val text = name.toString()
            require(Charsets.UTF_8.newEncoder().canEncode(text)) { "malformed Unicode project entry" }
            require(!name.isAbsolute && name.nameCount == 1 && text != "." && text != ".." && text.isNotEmpty()) {
                "expected a direct project entry"
            }
            require(text.none { it.code < 32 || it in "<>:\"/\\|?*" } && !text.endsWith('.') && !text.endsWith(' ')) {
                "ambiguous Windows project entry"
            }
            require(!DEVICE_NAME.matches(text.substringBefore('.').trimEnd(' '))) { "Windows device names are not project entries" }
        }

        private val DEVICE_NAME = Regex("(?iu)(CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|COM[0-9¹²³]|LPT[0-9¹²³])")

        fun open(path: Path): PinnedDirectoryStream {
            val pin = WindowsEntryPinning.openRoot(path)
            try {
                if (!WindowsEntryPinning.attributes(pin).isDirectory) throw NotDirectoryException(path.toString())
                return PinnedDirectoryStream(path, PinChain(pin, null))
            } catch (failure: Throwable) {
                pin.close()
                throw failure
            }
        }
    }
}
