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

import ru.lazyhat.compukterkraft.lang.api.SourceRange

data class DeviceWorkspaceEntry(
    val path: String,
    val directory: Boolean,
    val size: Int = 0,
    val version: Long = 0,
)

data class DeviceWorkspaceDocument(
    val path: String,
    val text: String,
    val version: Long,
)

enum class IdeDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

data class Diagnostic(
    val message: String,
    val range: SourceRange? = null,
    val severity: IdeDiagnosticSeverity = IdeDiagnosticSeverity.ERROR,
)

enum class HighlightTokenKind {
    KEYWORD,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    IDENTIFIER,
    FUNCTION,
    TYPE,
    MODULE,
    FIELD,
    OPERATOR,
    PUNCTUATION,
}

data class HighlightToken(
    val kind: HighlightTokenKind,
    val range: SourceRange,
)

enum class CompletionItemKind {
    KEYWORD,
    MODULE,
    FUNCTION,
    VARIABLE,
    PARAMETER,
    TYPE,
    FIELD,
}

data class CompletionItem(
    val label: String,
    val detail: String,
    val kind: CompletionItemKind,
    val documentation: String? = null,
    val insertText: String? = null,
)

data class HoverInfo(
    val contents: String,
    val documentation: String? = null,
    val range: SourceRange? = null,
)

data class DefinitionTarget(
    val path: String,
    val range: SourceRange,
)

data class ComputerIdeSnapshot(
    val document: DeviceWorkspaceDocument,
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

interface DeviceWorkspace {
    fun list(
        computerId: Int,
        path: String = "",
    ): List<DeviceWorkspaceEntry>

    fun readDocument(
        computerId: Int,
        path: String,
    ): DeviceWorkspaceDocument?

    fun isDirectory(
        computerId: Int,
        path: String,
    ): Boolean

    fun writeDocument(
        computerId: Int,
        path: String,
        text: String,
    ): DeviceWorkspaceDocument

    fun makeDirectory(
        computerId: Int,
        path: String,
    ): Boolean

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
