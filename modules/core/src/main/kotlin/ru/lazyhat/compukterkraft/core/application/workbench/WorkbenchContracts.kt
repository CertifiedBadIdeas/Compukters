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
package ru.lazyhat.compukterkraft.core.application.workbench

import kotlinx.coroutines.flow.StateFlow
import ru.lazyhat.compukterkraft.lang.api.BuiltinRegistry
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HighlightTokenKind
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

data class WorkbenchRemoteState(
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val document: ComputerWorkspaceDocument? = null,
)

/**
 * Provides a reactive [StateFlow] of workspace state updates.
 * Replaces the previous callback-based subscription model.
 */
interface WorkbenchUpdateSource {
    /** Observable stream of remote workspace state. */
    val stateFlow: StateFlow<WorkbenchRemoteState>
}

interface WorkspaceGateway {
    fun list(path: String)

    fun read(path: String)

    fun write(
        path: String,
        text: String,
    )
}

interface ComputerControlGateway {
    fun reboot()
}

interface IdeRuntimeCatalogSource {
    fun runtimeRegistry(): BuiltinRegistry
}

interface WorkbenchIdeFacade {
    fun analyze(
        path: String,
        source: String,
    ): ComputerIdeSnapshot

    fun complete(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun completeFromLastAnalysis(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): List<CompletionItem>

    fun availableImports(
        path: String,
        source: String,
    ): List<CompletionItem>

    fun hover(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): HoverInfo?

    fun definition(
        path: String,
        source: String,
        line: Int,
        column: Int,
    ): DefinitionTarget?
}

fun completionDetail(kind: CompletionItemKind): String =
    when (kind) {
        CompletionItemKind.KEYWORD -> "kw"
        CompletionItemKind.MODULE -> "mod"
        CompletionItemKind.FUNCTION -> "fn"
        CompletionItemKind.VARIABLE -> "var"
        CompletionItemKind.PARAMETER -> "arg"
        CompletionItemKind.TYPE -> "type"
        CompletionItemKind.FIELD -> "field"
    }

fun highlightColor(kind: HighlightTokenKind): Int =
    when (kind) {
        HighlightTokenKind.KEYWORD -> 0x8EC5FF
        HighlightTokenKind.STRING -> 0xD9C27C
        HighlightTokenKind.NUMBER -> 0xC6A0F6
        HighlightTokenKind.BOOLEAN -> 0xC6A0F6
        HighlightTokenKind.NULL -> 0xC6A0F6
        HighlightTokenKind.IDENTIFIER -> 0xE6ECF5
        HighlightTokenKind.FUNCTION -> 0x8BD5CA
        HighlightTokenKind.TYPE -> 0xF5B971
        HighlightTokenKind.MODULE -> 0x7FC1FF
        HighlightTokenKind.FIELD -> 0xA8D68F
        HighlightTokenKind.OPERATOR -> 0xE6ECF5
        HighlightTokenKind.PUNCTUATION -> 0xB0B8C5
    }
