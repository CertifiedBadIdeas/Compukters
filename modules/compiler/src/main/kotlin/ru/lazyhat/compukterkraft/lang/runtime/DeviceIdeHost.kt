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
    /**
     * Caret offset inside [insertText] after the completion is applied. `null` means "place
     * caret at the end of the inserted text". Used for function completions to put the
     * caret between the auto-inserted `()`.
     */
    val cursorOffset: Int? = null,
    val sourceNamespace: String? = null,
    val additionalTextEdits: List<TextEdit> = emptyList(),
)

data class TextEdit(
    val startOffset: Int,
    val endOffset: Int,
    val replacement: String,
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

data class DeviceIdeSnapshot(
    val document: DeviceWorkspaceDocument,
    val diagnostics: List<Diagnostic>,
    val highlights: List<HighlightToken>,
)

data class DeviceCompletionRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class DeviceCompletionResponse(
    val items: List<CompletionItem>,
)

data class DeviceHoverRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class DeviceHoverResponse(
    val info: HoverInfo?,
)

data class DeviceDefinitionRequest(
    val path: String,
    val line: Int,
    val column: Int,
)

data class DeviceDefinitionResponse(
    val target: DefinitionTarget?,
)

data class DeviceFormatRequest(
    val path: String,
)

data class DeviceFormatResponse(
    val edits: List<TextEdit>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

interface DeviceIdeHost {
    fun snapshot(
        deviceId: Int,
        path: String,
    ): DeviceIdeSnapshot?

    fun complete(
        deviceId: Int,
        request: DeviceCompletionRequest,
    ): DeviceCompletionResponse

    fun hover(
        deviceId: Int,
        request: DeviceHoverRequest,
    ): DeviceHoverResponse

    fun definition(
        deviceId: Int,
        request: DeviceDefinitionRequest,
    ): DeviceDefinitionResponse

    fun formatDocument(
        deviceId: Int,
        request: DeviceFormatRequest,
    ): DeviceFormatResponse

    fun cleanupDocument(
        deviceId: Int,
        request: DeviceFormatRequest,
    ): DeviceFormatResponse
}
