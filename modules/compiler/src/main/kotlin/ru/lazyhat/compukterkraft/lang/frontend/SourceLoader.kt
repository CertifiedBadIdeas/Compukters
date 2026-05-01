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
package ru.lazyhat.compukterkraft.lang.frontend

/**
 * Loads CKL source files for multi-file compilation.
 *
 * [resolve] takes the canonical path of the file containing an import and the
 * literal path written in that import. It returns a canonical path suitable as a
 * de-duplication key, or null when the source cannot be resolved.
 */
interface SourceLoader {
    fun resolve(from: String, importPath: String): String?

    fun read(canonical: String): String?
}

interface SourceIndex {
    fun listSources(): List<String>

    fun readIndexedSource(canonical: String): String?
}

class MapSourceLoader(
    private val files: Map<String, String>,
) : SourceLoader,
    SourceIndex {
    override fun resolve(
        from: String,
        importPath: String,
    ): String? {
        val baseDir = from.substringBeforeLast('/', missingDelimiterValue = "")
        val combined = if (baseDir.isEmpty()) importPath else "$baseDir/$importPath"
        val normalised = normalise(combined)
        return if (files.containsKey(normalised)) normalised else null
    }

    override fun read(canonical: String): String? = files[canonical]

    override fun listSources(): List<String> = files.keys.filter { it.endsWith(".ck") }.sorted()

    override fun readIndexedSource(canonical: String): String? = read(canonical)

    private fun normalise(path: String): String {
        val parts = path.split('/').toMutableList()
        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "", "." -> parts.removeAt(i)
                ".." -> {
                    if (i > 0) {
                        parts.removeAt(i)
                        parts.removeAt(i - 1)
                        i -= 1
                    } else {
                        parts.removeAt(i)
                    }
                }
                else -> i += 1
            }
        }
        return parts.joinToString("/")
    }
}

object NoOpSourceLoader : SourceLoader {
    override fun resolve(
        from: String,
        importPath: String,
    ): String? = null

    override fun read(canonical: String): String? = null
}

object EmptySourceIndex : SourceIndex {
    override fun listSources(): List<String> = emptyList()

    override fun readIndexedSource(canonical: String): String? = null
}
