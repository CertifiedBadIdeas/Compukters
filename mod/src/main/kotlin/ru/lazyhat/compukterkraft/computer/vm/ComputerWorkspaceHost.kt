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

package ru.lazyhat.compukterkraft.computer.vm

import ru.lazyhat.compukterkraft.machine.ComputerCompletionRequest
import ru.lazyhat.compukterkraft.machine.ComputerCompletionResponse
import ru.lazyhat.compukterkraft.machine.ComputerDefinitionRequest
import ru.lazyhat.compukterkraft.machine.ComputerDefinitionResponse
import ru.lazyhat.compukterkraft.machine.ComputerHoverRequest
import ru.lazyhat.compukterkraft.machine.ComputerHoverResponse
import ru.lazyhat.compukterkraft.machine.ComputerIdeHost
import ru.lazyhat.compukterkraft.machine.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.machine.ComputerWorkspace
import ru.lazyhat.compukterkraft.machine.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.machine.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingEnvironmentHolder
import ru.lazyhat.compukterkraft.scripting.runtime.ScriptingPaths
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
    private val rootPath: Path = ScriptingPaths.scriptsDirectory().toPath().resolve("computers"),
) : ComputerWorkspace {
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

    override fun deleteDocument(
        computerId: Int,
        path: String,
    ): Boolean {
        val target = resolve(computerId, path)
        return Files.deleteIfExists(target)
    }

    fun computerRoot(computerId: Int): Path = rootPath.resolve(computerId.toString())

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
}

class EnvironmentComputerIdeHost(
    private val workspace: ComputerWorkspace,
) : ComputerIdeHost {
    override fun snapshot(
        computerId: Int,
        path: String,
    ): ComputerIdeSnapshot? {
        val document = workspace.readDocument(computerId, path) ?: return null
        val ide = ScriptingEnvironmentHolder.environment?.ide ?: return null
        return ComputerIdeSnapshot(
            document = document,
            diagnostics = ide.analyze(document.path, document.text),
            highlights = ide.highlight(document.path, document.text),
        )
    }

    override fun complete(
        computerId: Int,
        request: ComputerCompletionRequest,
    ): ComputerCompletionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val ide = ScriptingEnvironmentHolder.environment?.ide
        val items =
            if (document == null || ide == null) {
                emptyList()
            } else {
                ide.complete(document.path, document.text, request.line, request.column)
            }
        return ComputerCompletionResponse(items)
    }

    override fun hover(
        computerId: Int,
        request: ComputerHoverRequest,
    ): ComputerHoverResponse {
        val document = workspace.readDocument(computerId, request.path)
        val ide = ScriptingEnvironmentHolder.environment?.ide
        val info =
            if (document == null || ide == null) {
                null
            } else {
                ide.hover(document.path, document.text, request.line, request.column)
            }
        return ComputerHoverResponse(info)
    }

    override fun definition(
        computerId: Int,
        request: ComputerDefinitionRequest,
    ): ComputerDefinitionResponse {
        val document = workspace.readDocument(computerId, request.path)
        val ide = ScriptingEnvironmentHolder.environment?.ide
        val target =
            if (document == null || ide == null) {
                null
            } else {
                ide.definition(document.path, document.text, request.line, request.column)
            }
        return ComputerDefinitionResponse(target)
    }
}
