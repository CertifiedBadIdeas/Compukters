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
package ru.lazyhat.compukterkraft.lang.runtime

import ru.lazyhat.compukterkraft.lang.frontend.SourceIndex
import ru.lazyhat.compukterkraft.lang.frontend.SourceLoader

class DeviceWorkspaceSourceLoader(
    private val workspace: DeviceWorkspace,
    private val deviceId: Int,
) : SourceLoader,
    SourceIndex {
    override fun resolve(
        from: String,
        importPath: String,
    ): String? {
        val baseDir = from.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) importPath else "$baseDir/$importPath"
        val normalised = normalise(combined) ?: return null
        return if (workspace.readDocument(deviceId, normalised) != null) normalised else null
    }

    override fun read(canonical: String): String? = workspace.readDocument(deviceId, canonical)?.text

    override fun listSources(): List<String> = collectCkFiles("").sorted()

    override fun readIndexedSource(canonical: String): String? = read(canonical)

    private fun collectCkFiles(path: String): List<String> =
        workspace
            .list(deviceId, path)
            .flatMap { entry ->
                val normalised = normalise(entry.path) ?: return@flatMap emptyList()
                when {
                    entry.directory -> collectCkFiles(normalised)
                    normalised.endsWith(".ck") -> listOf(normalised)
                    else -> emptyList()
                }
            }

    private fun normalise(path: String): String? {
        val parts = path.split('/').toMutableList()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "", "." -> parts.removeAt(i)
                ".." -> {
                    if (i == 0) return null
                    parts.removeAt(i)
                    parts.removeAt(i - 1)
                    i -= 1
                }
                else -> i += 1
            }
        }
        return parts.joinToString("/")
    }
}
