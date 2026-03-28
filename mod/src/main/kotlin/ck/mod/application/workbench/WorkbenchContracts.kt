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
package ck.mod.application.workbench

import ck.lang.runtime.CompletionItem
import ck.lang.runtime.ComputerIdeSnapshot
import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.lang.runtime.DefinitionTarget
import ck.lang.runtime.HighlightTokenKind
import ck.lang.runtime.HoverInfo
import kotlinx.coroutines.flow.StateFlow

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

fun completionDetail(kind: ck.lang.runtime.CompletionItemKind): String =
    when (kind) {
        ck.lang.runtime.CompletionItemKind.KEYWORD -> "kw"
        ck.lang.runtime.CompletionItemKind.MODULE -> "mod"
        ck.lang.runtime.CompletionItemKind.FUNCTION -> "fn"
        ck.lang.runtime.CompletionItemKind.VARIABLE -> "var"
        ck.lang.runtime.CompletionItemKind.PARAMETER -> "arg"
        ck.lang.runtime.CompletionItemKind.TYPE -> "type"
        ck.lang.runtime.CompletionItemKind.FIELD -> "field"
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
