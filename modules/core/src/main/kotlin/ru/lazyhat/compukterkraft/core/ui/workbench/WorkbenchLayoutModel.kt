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
package ru.lazyhat.compukterkraft.core.ui.workbench

import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchState
import ru.lazyhat.compukterkraft.core.computer.workbench.completionDetail
import ru.lazyhat.compukterkraft.core.platform.api.FontMetrics
import kotlin.math.abs
import kotlin.math.max

data class UiRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = x + width

    val bottom: Int
        get() = y + height

    operator fun contains(point: Pair<Int, Int>): Boolean = point.first in x..right && point.second in y..bottom

    fun contains(
        pointX: Int,
        pointY: Int,
    ): Boolean = pointX in x..right && pointY in y..bottom
}

data class ToolbarButtonLayout(
    val index: Int,
    val label: String,
    val bounds: UiRect,
)

data class WorkspaceRowLayout(
    val label: String,
    val bounds: UiRect,
    val path: String?,
    val selected: Boolean,
    val directory: Boolean,
)

data class CompletionPopupLayout(
    val bounds: UiRect,
    val rowHeight: Int,
    val labelsWidth: Int,
    val visibleItems: Int,
)

data class ImportPickerPopupLayout(
    val bounds: UiRect,
    val rowHeight: Int,
    val visibleItems: Int,
)

class WorkbenchLayoutModel(
    val leftPos: Int,
    val topPos: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    private val font: FontMetrics,
) {
    val editorLineHeight: Int = LINE_HEIGHT
    val sidebarBounds: UiRect = UiRect(leftPos + 8, topPos + 34, 120, imageHeight - 46)
    val editorBounds: UiRect = UiRect(leftPos + 136, topPos + 34, imageWidth - 144, imageHeight - 66)
    val statusBarBounds: UiRect = UiRect(leftPos + 136, topPos + imageHeight - 28, imageWidth - 144, 20)

    fun visibleEditorLines(): Int = ((imageHeight - 82) / LINE_HEIGHT).coerceAtLeast(1)

    fun editorTextOrigin(): Pair<Int, Int> = leftPos + 176 to topPos + 40

    fun toolbarButtons(state: WorkbenchState): List<ToolbarButtonLayout> =
        listOf(
            ToolbarButtonLayout(0, if (state.mode.name == "TERMINAL") "IDE" else "Console", toolbarButtonBounds(0)),
            ToolbarButtonLayout(1, "Save", toolbarButtonBounds(1)),
            ToolbarButtonLayout(2, "Refresh", toolbarButtonBounds(2)),
            ToolbarButtonLayout(3, "Up", toolbarButtonBounds(3)),
            ToolbarButtonLayout(4, "Reboot", toolbarButtonBounds(4)),
            ToolbarButtonLayout(5, "Imports", toolbarButtonBounds(5)),
        )

    fun workspaceRows(state: WorkbenchState): List<WorkspaceRowLayout> {
        val rows = mutableListOf<WorkspaceRowLayout>()
        var rowY = topPos + 54

        if (state.browserPath.isNotEmpty()) {
            rows +=
                WorkspaceRowLayout(
                    label = "..",
                    bounds = UiRect(leftPos + 10, rowY - 1, 114, 11),
                    path = null,
                    selected = true,
                    directory = true,
                )
            rowY += WORKSPACE_ROW_STEP
        }

        state.entries.forEach { entry ->
            rows +=
                WorkspaceRowLayout(
                    label = if (entry.directory) entry.path.substringAfterLast('/') + "/" else entry.path.substringAfterLast('/'),
                    bounds = UiRect(leftPos + 10, rowY - 1, 114, 11),
                    path = entry.path,
                    selected = false,
                    directory = entry.directory,
                )
            rowY += WORKSPACE_ROW_STEP
        }

        return rows
    }

    fun toolbarButtonAt(
        state: WorkbenchState,
        mouseX: Int,
        mouseY: Int,
    ): ToolbarButtonLayout? = toolbarButtons(state).firstOrNull { it.bounds.contains(mouseX, mouseY) }

    fun workspaceRowAt(
        state: WorkbenchState,
        mouseX: Int,
        mouseY: Int,
    ): WorkspaceRowLayout? = workspaceRows(state).firstOrNull { it.bounds.contains(mouseX, mouseY) }

    fun completionPopup(state: WorkbenchState): CompletionPopupLayout? {
        if (state.editor.completionItems.isEmpty()) return null

        val visibleLine = state.editor.cursorLine - state.editor.scrollLine
        if (visibleLine < 0) return null

        val line = editorLines(state).getOrElse(state.editor.cursorLine) { "" }
        val beforeCursor = line.take(state.editor.cursorColumn)
        val popupX = editorTextOrigin().first + font.width(beforeCursor)
        val popupY = topPos + 52 + visibleLine * LINE_HEIGHT
        val visibleItems =
            state.editor.completionItems.size
                .coerceAtMost(MAX_COMPLETION_ITEMS)
        val labelsWidth =
            state.editor.completionItems
                .take(visibleItems)
                .maxOfOrNull { font.width(it.label + "  " + completionDetail(it.kind)) }
                ?.plus(12) ?: 120

        return CompletionPopupLayout(
            bounds = UiRect(popupX, popupY, labelsWidth, visibleItems * COMPLETION_ROW_HEIGHT + 4),
            rowHeight = COMPLETION_ROW_HEIGHT,
            labelsWidth = labelsWidth,
            visibleItems = visibleItems,
        )
    }

    fun completionIndexAt(
        state: WorkbenchState,
        mouseX: Int,
        mouseY: Int,
    ): Int? {
        val popup = completionPopup(state) ?: return null
        if (!popup.bounds.contains(mouseX, mouseY)) return null
        return ((mouseY - popup.bounds.y - 2) / popup.rowHeight).coerceIn(0, state.editor.completionItems.size - 1)
    }

    fun importPickerPopup(state: WorkbenchState): ImportPickerPopupLayout? {
        if (!state.editor.importPickerVisible || state.editor.importPickerItems.isEmpty()) return null

        val visibleItems = state.editor.importPickerItems.size.coerceAtMost(MAX_COMPLETION_ITEMS)
        val popupWidth =
            max(
                160,
                state.editor.importPickerItems
                    .take(visibleItems)
                    .maxOfOrNull { font.width(it.label) + 28 } ?: 160,
            )
        val popupHeight = visibleItems * COMPLETION_ROW_HEIGHT + 24
        val popupX = editorBounds.x + (editorBounds.width - popupWidth) / 2
        val popupY = editorBounds.y + 12

        return ImportPickerPopupLayout(
            bounds = UiRect(popupX, popupY, popupWidth, popupHeight),
            rowHeight = COMPLETION_ROW_HEIGHT,
            visibleItems = visibleItems,
        )
    }

    fun importPickerIndexAt(
        state: WorkbenchState,
        mouseX: Int,
        mouseY: Int,
    ): Int? {
        val popup = importPickerPopup(state) ?: return null
        if (!popup.bounds.contains(mouseX, mouseY)) return null
        val rowsTop = popup.bounds.y + 18
        if (mouseY < rowsTop) return null
        return ((mouseY - rowsTop) / popup.rowHeight).coerceIn(0, state.editor.importPickerItems.size - 1)
    }

    fun mouseToCursor(
        state: WorkbenchState,
        mouseX: Int,
        mouseY: Int,
    ): Pair<Int, Int> {
        val lines = editorLines(state)
        val editorOrigin = editorTextOrigin()
        val lineIndex = ((mouseY - editorOrigin.second) / LINE_HEIGHT + state.editor.scrollLine).coerceIn(0, max(lines.lastIndex, 0))
        val line = lines.getOrElse(lineIndex) { "" }
        val relativeX = mouseX - editorOrigin.first
        var bestColumn = 0
        var bestDistance = Int.MAX_VALUE

        for (index in 0..line.length) {
            val width = font.width(line.take(index))
            val distance = abs(relativeX - width)
            if (distance < bestDistance) {
                bestDistance = distance
                bestColumn = index
            }
        }

        return lineIndex to bestColumn
    }

    private fun toolbarButtonBounds(index: Int): UiRect = UiRect(leftPos + 8 + index * 80, topPos + 8, 72, 20)

    private companion object {
        const val LINE_HEIGHT = 10
        const val WORKSPACE_ROW_STEP = 12
        const val COMPLETION_ROW_HEIGHT = 12
        const val MAX_COMPLETION_ITEMS = 8
    }

    private fun editorLines(state: WorkbenchState): List<String> =
        if (state.editor.text.isEmpty()) {
            listOf("")
        } else {
            state.editor.text.split('\n')
        }
}
