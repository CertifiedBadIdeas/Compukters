/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.createDirectories

/** The request root is free; every child file or directory is one entry, and only regular-file payload contributes bytes. */
data class TemporaryUsage(
    val files: Int,
    val bytes: Long,
)

class TemporaryBudgetException(
    message: String,
) : IllegalStateException(message)

class TemporaryBudget(
    workerRoot: Path,
    private val limits: WorkerLimits,
) {
    private val root = workerRoot.toAbsolutePath().normalize()

    fun <T> useRequestDirectory(action: (Path) -> T): T {
        root.createDirectories()
        val request = root.resolve("request-${UUID.randomUUID()}")
        request.createDirectories()
        return try {
            val result = action(request)
            inspect(request)
            result
        } finally {
            deleteExact(request)
        }
    }

    fun inspect(request: Path): TemporaryUsage {
        val normalized = request.toAbsolutePath().normalize()
        if (normalized.parent != root || !Files.isDirectory(normalized)) {
            throw TemporaryBudgetException("request directory is outside the worker root")
        }
        var files = 0
        var bytes = 0L
        Files.walkFileTree(
            normalized,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (directory != normalized) {
                        files = Math.addExact(files, 1)
                        requireFileCapacity(files)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                        throw TemporaryBudgetException("request storage contains a link or special file")
                    }
                    files = Math.addExact(files, 1)
                    bytes = Math.addExact(bytes, attributes.size())
                    requireFileCapacity(files)
                    if (bytes > limits.temporaryBytes) throw TemporaryBudgetException("request bytes exceed limit")
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return TemporaryUsage(files, bytes)
    }

    fun requireCapacity(usage: TemporaryUsage) {
        requireFileCapacity(usage.files)
        if (usage.bytes > limits.temporaryBytes) throw TemporaryBudgetException("request bytes exceed limit")
    }

    private fun requireFileCapacity(files: Int) {
        if (files > limits.temporaryFiles) throw TemporaryBudgetException("request file count exceeds limit")
    }

    private fun deleteExact(request: Path) {
        val normalized = request.toAbsolutePath().normalize()
        check(normalized.parent == root) { "refusing to delete outside the worker root" }
        if (!Files.exists(normalized)) return
        Files.walk(normalized).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
