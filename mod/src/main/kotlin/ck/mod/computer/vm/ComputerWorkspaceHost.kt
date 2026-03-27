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

package ck.mod.computer.vm

import ck.lang.runtime.ComputerProgramFiles
import ck.lang.runtime.ComputerWorkspace
import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

class FileComputerWorkspace(
    private val rootPath: Path,
    private val initialBundledScripts: Set<String> = setOf(ComputerProgramFiles.BIOS_SCRIPT_NAME),
    private val bundledScriptLoader: (String) -> String? = { null },
) : ComputerWorkspace {
    fun ensureInitialized(computerId: Int): Path {
        val root = computerRoot(computerId)
        root.createDirectories()
        initialBundledScripts.forEach { relativePath ->
            seedBundledScript(root, relativePath)
        }
        return root
    }

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

    fun computerRoot(computerId: Int): Path = rootPath.resolve(computerId.toString()).normalize()

    private fun resolve(
        computerId: Int,
        path: String,
    ): Path {
        val root = ensureInitialized(computerId)
        val candidate = root.resolve(path.trimStart('/')).normalize()
        require(candidate.startsWith(root)) { "Path escapes computer workspace: $path" }
        return candidate
    }

    private fun seedBundledScript(
        root: Path,
        relativePath: String,
    ) {
        val target = root.resolve(relativePath.trimStart('/')).normalize()
        require(target.startsWith(root)) { "Path escapes computer workspace: $relativePath" }
        if (target.exists()) return

        val bundledScript = bundledScriptLoader(relativePath) ?: return
        target.parent?.createDirectories()
        target.writeText(bundledScript, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
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
}
