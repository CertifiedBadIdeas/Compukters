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

package ru.lazyhat.compukterkraft.lang.api

sealed interface ImportSource {
    val range: SourceRange

    data class FilePath(
        val path: String,
        override val range: SourceRange,
    ) : ImportSource

    data class BuiltinNamespace(
        val name: String,
        override val range: SourceRange,
    ) : ImportSource
}

sealed interface ImportMode {
    data class Namespace(
        val alias: String,
        val aliasRange: SourceRange,
    ) : ImportMode

    data class Selective(
        val items: List<ImportItem>,
        val range: SourceRange,
    ) : ImportMode

    data class Invalid(
        val message: String,
        val range: SourceRange,
    ) : ImportMode
}

data class ImportItem(
    val name: String,
    val range: SourceRange,
)

data class ImportDeclaration(
    val source: ImportSource,
    val mode: ImportMode,
    val range: SourceRange,
)
