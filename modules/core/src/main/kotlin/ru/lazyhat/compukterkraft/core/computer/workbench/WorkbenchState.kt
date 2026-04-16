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
package ru.lazyhat.compukterkraft.core.computer.workbench

import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

enum class WorkbenchMode {
    TERMINAL,
    EDITOR,
}

data class WorkbenchActionState(
    val canPull: Boolean = false,
    val canPush: Boolean = false,
    val canRun: Boolean = false,
    val canAttachTerminal: Boolean = false,
)

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
    val importPickerVisible: Boolean = false,
    val importPickerItems: List<CompletionItem> = emptyList(),
    val selectedImportPickerIndex: Int = 0,
)

data class WorkbenchState(
    val mode: WorkbenchMode = WorkbenchMode.TERMINAL,
    val browserPath: String = "",
    val entries: List<ComputerWorkspaceEntry> = emptyList(),
    val openDocument: ComputerWorkspaceDocument? = null,
    val editor: EditorState = EditorState(),
    val target: WorkbenchTargetState = WorkbenchTargetState(),
    val sync: WorkbenchSyncState = WorkbenchSyncState(),
    val actions: WorkbenchActionState = WorkbenchActionState(),
)
