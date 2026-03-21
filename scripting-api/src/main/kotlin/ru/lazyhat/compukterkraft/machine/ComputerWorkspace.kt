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

package ru.lazyhat.compukterkraft.machine

import ru.lazyhat.compukterkraft.scripting.api.CompletionItem
import ru.lazyhat.compukterkraft.scripting.api.DefinitionTarget
import ru.lazyhat.compukterkraft.scripting.api.Diagnostic
import ru.lazyhat.compukterkraft.scripting.api.HighlightToken
import ru.lazyhat.compukterkraft.scripting.api.HoverInfo

data class ComputerWorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val size: Int = 0,
    val version: Long = 0,
)

data class ComputerWorkspaceDocument(
    val path: String,
    val text: String,
    val version: Long,
)

data class ComputerIdeSnapshot(
    val document: ComputerWorkspaceDocument,
    val diagnostics: List<Diagnostic>,
    val highlights: List<HighlightToken>,
)

data class ComputerCompletionRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class ComputerCompletionResponse(
    val items: List<CompletionItem>,
)

data class ComputerHoverRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class ComputerHoverResponse(
    val info: HoverInfo?,
)

data class ComputerDefinitionRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class ComputerDefinitionResponse(
    val target: DefinitionTarget?,
)

interface ComputerWorkspace {
    fun list(computerId: Int, path: String = ""): List<ComputerWorkspaceEntry>

    fun readDocument(
        computerId: Int,
        path: String,
    ): ComputerWorkspaceDocument?

    fun writeDocument(
        computerId: Int,
        path: String,
        text: String,
    ): ComputerWorkspaceDocument

    fun deleteDocument(
        computerId: Int,
        path: String,
    ): Boolean
}

interface ComputerIdeHost {
    fun snapshot(
        computerId: Int,
        path: String,
    ): ComputerIdeSnapshot?

    fun complete(
        computerId: Int,
        request: ComputerCompletionRequest,
    ): ComputerCompletionResponse

    fun hover(
        computerId: Int,
        request: ComputerHoverRequest,
    ): ComputerHoverResponse

    fun definition(
        computerId: Int,
        request: ComputerDefinitionRequest,
    ): ComputerDefinitionResponse
}
