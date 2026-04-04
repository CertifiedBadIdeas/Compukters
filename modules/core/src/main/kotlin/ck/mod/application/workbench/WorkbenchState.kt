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
import ck.lang.runtime.HoverInfo

enum class WorkbenchMode {
    TERMINAL,
    EDITOR,
}

data class EditorState(
    val text: String = "",
    val dirty: Boolean = false,
    val scrollLine: Int = 0,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val ideSnapshot: ComputerIdeSnapshot? = null,
    val hoverInfo: HoverInfo? = null,
    val completionItems: List<CompletionItem> = emptyList(),
    val selectedCompletion: Int = 0,
)

data class WorkbenchState(
    val mode: WorkbenchMode = WorkbenchMode.TERMINAL,
    val browserPath: String = "",
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val openDocument: ComputerWorkspaceDocument? = null,
    val editor: EditorState = EditorState(),
)
