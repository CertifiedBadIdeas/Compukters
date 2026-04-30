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

package ru.lazyhat.compukterkraft.core.device.vm

import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry
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

class DeviceWorkspaceHost(
    private val rootPath: Path,
    private val defaultDiskQuotaBytes: Long = Long.MAX_VALUE,
) : DeviceWorkspace {
    private val diskQuotaOverrides = ConcurrentHashMap<Int, Long>()

    override fun list(
        deviceId: Int,
        path: String,
    ): List<DeviceWorkspaceEntry> {
        val target = resolve(deviceId, path)
        if (!target.exists()) return emptyList()

        if (!target.isDirectory()) {
            return listOf(entryFor(target, computerRoot(deviceId)))
        }

        Files.createDirectories(target)

        return Files
            .list(target)
            .use { stream ->
                stream
                    .sorted(compareBy(Path::name))
                    .map { entryFor(it, computerRoot(deviceId)) }
                    .toList()
            }
    }

    override fun readDocument(
        deviceId: Int,
        path: String,
    ): DeviceWorkspaceDocument? {
        val target = resolve(deviceId, path)
        if (!target.exists() || target.isDirectory()) return null
        return DeviceWorkspaceDocument(normalizeDisplayPath(target, computerRoot(deviceId)), target.readText(), versionOf(target))
    }

    override fun isDirectory(
        deviceId: Int,
        path: String,
    ): Boolean = resolve(deviceId, path).isDirectory()

    override fun writeDocument(
        deviceId: Int,
        path: String,
        text: String,
    ): DeviceWorkspaceDocument {
        val target = resolve(deviceId, path)
        ensureWithinDiskQuota(deviceId, target, text.toByteArray(StandardCharsets.UTF_8).size.toLong())
        target.parent?.createDirectories()
        target.writeText(text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return DeviceWorkspaceDocument(normalizeDisplayPath(target, computerRoot(deviceId)), text, versionOf(target))
    }

    override fun makeDirectory(
        deviceId: Int,
        path: String,
    ): Boolean {
        val target = resolve(deviceId, path)
        if (target.exists()) return target.isDirectory()
        target.createDirectories()
        return true
    }

    override fun deleteDocument(
        deviceId: Int,
        path: String,
    ): Boolean {
        val target = resolve(deviceId, path)
        return Files.deleteIfExists(target)
    }

    fun setDiskQuota(
        deviceId: Int,
        diskQuotaBytes: Long,
    ) {
        diskQuotaOverrides[deviceId] = diskQuotaBytes
    }

    fun computerRoot(deviceId: Int): Path = rootPath.resolve(deviceId.toString()).normalize()

    private fun resolve(
        deviceId: Int,
        path: String,
    ): Path {
        val root = computerRoot(deviceId)
        root.createDirectories()
        val candidate = root.resolve(path.trimStart('/')).normalize()
        require(candidate.startsWith(root)) { "Path escapes computer workspace: $path" }
        return candidate
    }

    private fun entryFor(
        path: Path,
        root: Path,
    ): DeviceWorkspaceEntry =
        DeviceWorkspaceEntry(
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
        deviceId: Int,
        target: Path,
        newSizeBytes: Long,
    ) {
        val quota = diskQuotaOverrides[deviceId] ?: defaultDiskQuotaBytes
        if (quota == Long.MAX_VALUE) return

        val existingSize = if (target.exists() && !target.isDirectory()) Files.size(target) else 0L
        val usedBytes = currentDiskUsage(deviceId)
        val nextUsage = usedBytes - existingSize + newSizeBytes
        check(nextUsage <= quota) { "Disk quota exceeded: $nextUsage > $quota" }
    }

    private fun currentDiskUsage(deviceId: Int): Long {
        val root = computerRoot(deviceId)
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
