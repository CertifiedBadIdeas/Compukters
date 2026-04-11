/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.core.computer.vm

import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class ComputerWorkspaceHost(
    private val rootPath: Path,
    private val defaultDiskQuotaBytes: Long = Long.MAX_VALUE,
) : ComputerWorkspace {
    private val diskQuotaOverrides = ConcurrentHashMap<Int, Long>()

    override fun list(
        computerId: Int,
        path: String,
    ): List<ComputerWorkspaceEntry> {
        val target = resolve(computerId, path)
        if (!target.exists()) return emptyList()

        if (!target.isDirectory()) {
            return listOf(entryFor(target, computerRoot(computerId)))
        }

        Files.createDirectories(target)

        return Files
            .list(target)
            .use { stream ->
                stream
                    .sorted(compareBy(Path::name))
                    .map { entryFor(it, computerRoot(computerId)) }
                    .toList()
            }
    }

    override fun readDocument(
        computerId: Int,
        path: String,
    ): ComputerWorkspaceDocument? {
        val target = resolve(computerId, path)
        if (!target.exists() || target.isDirectory()) return null
        return ComputerWorkspaceDocument(normalizeDisplayPath(target, computerRoot(computerId)), target.readText(), versionOf(target))
    }

    override fun isDirectory(
        computerId: Int,
        path: String,
    ): Boolean = resolve(computerId, path).isDirectory()

    override fun writeDocument(
        computerId: Int,
        path: String,
        text: String,
    ): ComputerWorkspaceDocument {
        val target = resolve(computerId, path)
        ensureWithinDiskQuota(computerId, target, text.toByteArray(StandardCharsets.UTF_8).size.toLong())
        target.parent?.createDirectories()
        target.writeText(text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return ComputerWorkspaceDocument(normalizeDisplayPath(target, computerRoot(computerId)), text, versionOf(target))
    }

    override fun makeDirectory(
        computerId: Int,
        path: String,
    ): Boolean {
        val target = resolve(computerId, path)
        if (target.exists()) return target.isDirectory()
        target.createDirectories()
        return true
    }

    override fun deleteDocument(
        computerId: Int,
        path: String,
    ): Boolean {
        val target = resolve(computerId, path)
        return Files.deleteIfExists(target)
    }

    fun setDiskQuota(
        computerId: Int,
        diskQuotaBytes: Long,
    ) {
        diskQuotaOverrides[computerId] = diskQuotaBytes
    }

    fun computerRoot(computerId: Int): Path = rootPath.resolve(computerId.toString()).normalize()

    private fun resolve(
        computerId: Int,
        path: String,
    ): Path {
        val root = computerRoot(computerId)
        root.createDirectories()
        val candidate = root.resolve(path.trimStart('/')).normalize()
        require(candidate.startsWith(root)) { "Path escapes computer workspace: $path" }
        return candidate
    }

    private fun entryFor(
        path: Path,
        root: Path,
    ): ComputerWorkspaceEntry =
        ComputerWorkspaceEntry(
            path = normalizeDisplayPath(path, root),
            directory = path.isDirectory(),
            size = if (path.isDirectory()) 0 else Files.size(path).toInt(),
            version = versionOf(path),
        )

    private fun normalizeDisplayPath(
        path: Path,
        root: Path,
    ): String = path.relativeTo(root).toString().replace('\\', '/')

    private fun versionOf(path: Path): Long = Files.getLastModifiedTime(path).toMillis()

    private fun ensureWithinDiskQuota(
        computerId: Int,
        target: Path,
        newSizeBytes: Long,
    ) {
        val quota = diskQuotaOverrides[computerId] ?: defaultDiskQuotaBytes
        if (quota == Long.MAX_VALUE) return

        val existingSize = if (target.exists() && !target.isDirectory()) Files.size(target) else 0L
        val usedBytes = currentDiskUsage(computerId)
        val nextUsage = usedBytes - existingSize + newSizeBytes
        check(nextUsage <= quota) { "Disk quota exceeded: $nextUsage > $quota" }
    }

    private fun currentDiskUsage(computerId: Int): Long {
        val root = computerRoot(computerId)
        if (!root.exists()) return 0L

        return Files
            .walk(root)
            .use { stream ->
                stream
                    .filter(Files::isRegularFile)
                    .mapToLong(Files::size)
                    .sum()
            }
    }
}
