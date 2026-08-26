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

package ru.lazyhat.compukters.ide.project.fs

import ru.lazyhat.compukters.ide.project.TomlSupport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class SecureProjectFileException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal object SecureProjectFiles {
    fun identity(directory: Path): ProjectRootIdentity =
        withDirectory(directory) { _, attributes ->
            val fileKey = attributes.fileKey() ?: throw SecureProjectFileException("filesystem does not expose stable file identity")
            ProjectRootIdentity(directory.toRealPath(LinkOption.NOFOLLOW_LINKS), fileKey)
        }

    fun isValid(identity: ProjectRootIdentity): Boolean =
        try {
            identity(identity.canonicalPath) == identity
        } catch (_: Exception) {
            false
        }

    fun readText(
        directory: SecureDirectoryStream<Path>,
        name: String,
        maximumBytes: Int,
    ): String = TomlSupport.decodeStrictUtf8(readBytes(directory, Path.of(name), maximumBytes))

    fun writeNew(
        identity: ProjectRootIdentity,
        name: String,
        content: ByteArray,
    ) = publish(identity, ProjectPath.direct(name), content, replace = false)

    fun replace(
        identity: ProjectRootIdentity,
        name: String,
        content: ByteArray,
    ) = publish(identity, ProjectPath.direct(name), content, replace = true)

    fun <T> withDirectory(
        directory: Path,
        action: (SecureDirectoryStream<Path>, BasicFileAttributes) -> T,
    ): T {
        val absolute = directory.toAbsolutePath().normalize()
        val filesystemRoot = absolute.root ?: throw SecureProjectFileException("directory has no filesystem root")
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            var current = requireSecure(Files.newDirectoryStream(filesystemRoot))
            opened += current
            var currentAttributes = Files.readAttributes(filesystemRoot, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            filesystemRoot.relativize(absolute).forEach { component ->
                validateFilename(component)
                currentAttributes = attributes(current, component)
                if (currentAttributes.isSymbolicLink || !currentAttributes.isDirectory) {
                    throw SecureProjectFileException("directory path must contain only real directories")
                }
                current = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)
                opened += current
            }
            return action(current, currentAttributes)
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    fun attributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes =
        directory
            .getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.readAttributes()
            ?: throw SecureProjectFileException("filesystem cannot securely read entry attributes")

    fun attributesOrNull(
        directory: SecureDirectoryStream<Path>,
        name: Path,
    ): BasicFileAttributes? =
        try {
            attributes(directory, name)
        } catch (_: NoSuchFileException) {
            null
        }

    fun validateFilename(name: Path) {
        val decoded = name.toString()
        if (name.fileSystem.getPath(decoded) != name) throw SecureProjectFileException("filename is not strict UTF-8")
        try {
            TomlSupport.strictUtf8(decoded)
        } catch (exception: Exception) {
            throw SecureProjectFileException("filename is not strict UTF-8", exception)
        }
    }

    private fun publish(
        identity: ProjectRootIdentity,
        path: ProjectPath,
        content: ByteArray,
        replace: Boolean,
    ) {
        synchronized(identity) {
            withDirectory(identity.canonicalPath) { directory, attributes ->
                if (attributes.fileKey() != identity.fileKey) throw SecureProjectFileException("project root was invalidated")
                val target = Path.of(path.components.single())
                val existing = attributesOrNull(directory, target)
                if (replace) {
                    check(existing != null) { "lock does not exist" }
                    if (existing.isSymbolicLink || !existing.isRegularFile) {
                        throw SecureProjectFileException("lock target must be a real regular file")
                    }
                } else {
                    check(existing == null) { "lock already exists" }
                }
                val temporary = Path.of(".compukter-lock-${UUID.randomUUID()}.tmp")
                try {
                    directory.newByteChannel(temporary, WRITE_NEW_OPTIONS).use { channel ->
                        writeFully(channel, content)
                        (channel as? FileChannel)?.force(true)
                    }
                    if (!isValid(identity)) throw SecureProjectFileException("project root was invalidated")
                    if (replace) {
                        try {
                            Files.move(
                                identity.canonicalPath.resolve(temporary.toString()),
                                identity.canonicalPath.resolve(target.toString()),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } catch (exception: AtomicMoveNotSupportedException) {
                            throw SecureProjectFileException("filesystem does not support atomic lock replacement", exception)
                        }
                    } else {
                        directory.move(temporary, directory, target)
                    }
                } finally {
                    runCatching { directory.deleteFile(temporary) }
                }
            }
        }
    }

    private fun readBytes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        maximumBytes: Int,
    ): ByteArray {
        val attributes = attributes(directory, name)
        if (attributes.isSymbolicLink || !attributes.isRegularFile) {
            throw SecureProjectFileException("project entry must be a real regular file")
        }
        if (attributes.size() > maximumBytes.toLong()) throw SecureProjectFileException("project entry exceeds byte limit")
        return java.nio.channels.Channels
            .newInputStream(directory.newByteChannel(name, READ_OPTIONS))
            .use { input ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, 8192))
                val buffer = ByteArray(minOf(maximumBytes.coerceAtLeast(1), 8192))
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count)
                    if (total > maximumBytes) throw SecureProjectFileException("project entry grew beyond byte limit")
                    output.write(buffer, 0, count)
                }
                if (total.toLong() != attributes.size()) throw SecureProjectFileException("project entry changed while reading")
                output.toByteArray()
            }
    }

    private fun writeFully(
        channel: SeekableByteChannel,
        content: ByteArray,
    ) {
        val buffer = ByteBuffer.wrap(content)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun requireSecure(directory: DirectoryStream<Path>): SecureDirectoryStream<Path> {
        if (directory is SecureDirectoryStream<Path>) return directory
        directory.close()
        throw SecureProjectFileException("filesystem does not support secure project traversal")
    }

    private val READ_OPTIONS: Set<OpenOption> = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    private val WRITE_NEW_OPTIONS: Set<OpenOption> =
        setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)
}
