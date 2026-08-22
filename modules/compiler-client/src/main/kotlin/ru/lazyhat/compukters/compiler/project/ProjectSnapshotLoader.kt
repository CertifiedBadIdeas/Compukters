/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.project

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

class ProjectSnapshotException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProjectSnapshotLoader {
    fun load(
        projectDirectory: Path,
        limits: WorkerLimits,
    ): ProjectSnapshot {
        if (Files.isSymbolicLink(projectDirectory) || !Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw ProjectSnapshotException("project root must be a real directory")
        }
        val sources = mutableListOf<ProjectSource>()
        var totalBytes = 0L
        try {
            Files.walkFileTree(
                projectDirectory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (dir != projectDirectory && (attrs.isSymbolicLink || Files.isSymbolicLink(dir))) {
                            throw ProjectSnapshotException("project contains a symbolic-link directory")
                        }
                        if (!attrs.isDirectory) throw ProjectSnapshotException("project contains a non-directory entry")
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) {
                            throw ProjectSnapshotException("project contains a symbolic link")
                        }
                        if (!attrs.isRegularFile) throw ProjectSnapshotException("project contains a non-regular file")
                        val relative = projectDirectory.relativize(file).joinToString("/") { it.toString() }
                        if (!relative.endsWith(".kt")) return FileVisitResult.CONTINUE
                        if (sources.size >= limits.sourceFiles) throw ProjectSnapshotException("project source count exceeds limit")
                        val path =
                            try {
                                VirtualSourcePath.kotlin(relative)
                            } catch (exception: IllegalArgumentException) {
                                throw ProjectSnapshotException("invalid project source path: $relative", exception)
                            }
                        val declaredBytes = attrs.size()
                        if (declaredBytes < 0 || declaredBytes > limits.sourceFileBytes.toLong()) {
                            throw ProjectSnapshotException("project source exceeds per-file limit: $relative")
                        }
                        totalBytes = Math.addExact(totalBytes, declaredBytes)
                        if (totalBytes > limits.sourceBytes.toLong()) throw ProjectSnapshotException("project sources exceed total limit")
                        val content = readSourceBytes(file, limits.sourceFileBytes)
                        if (content.size.toLong() !=
                            declaredBytes
                        ) {
                            throw ProjectSnapshotException("project source changed while loading: $relative")
                        }
                        try {
                            StandardCharsets.UTF_8
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(content))
                        } catch (exception: Exception) {
                            throw ProjectSnapshotException("project source is not strict UTF-8: $relative", exception)
                        }
                        sources += ProjectSource(path, BinaryValue.of(content))
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (exception: ProjectSnapshotException) {
            throw exception
        } catch (exception: Exception) {
            throw ProjectSnapshotException("failed to load project snapshot", exception)
        }
        sources.sortWith { left, right -> ProjectSnapshot.compareUtf8(left.path.value, right.path.value) }
        return try {
            ProjectSnapshot.of(sources, limits)
        } catch (exception: IllegalArgumentException) {
            throw ProjectSnapshotException(exception.message ?: "invalid project snapshot", exception)
        }
    }

    internal fun readSourceBytes(
        file: Path,
        maximumBytes: Int,
    ): ByteArray =
        Channels
            .newInputStream(
                Files.newByteChannel(
                    file,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
                ),
            ).use { input ->
                val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_BYTES))
                val buffer = ByteArray(minOf(maximumBytes.coerceAtLeast(1), DEFAULT_BUFFER_BYTES))
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total = Math.addExact(total, count.toLong())
                    if (total > maximumBytes.toLong()) throw ProjectSnapshotException("project source grew beyond per-file limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }

    private const val DEFAULT_BUFFER_BYTES = 8192
}
