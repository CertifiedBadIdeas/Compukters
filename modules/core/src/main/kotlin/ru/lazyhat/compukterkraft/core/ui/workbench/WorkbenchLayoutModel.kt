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
    val headerBounds: UiRect = UiRect(leftPos, topPos, imageWidth, LEGACY_HEADER_HEIGHT),
    val sidebarBounds: UiRect = UiRect(leftPos + 8, topPos + 34, 120, imageHeight - 46),
    val editorBounds: UiRect = UiRect(leftPos + 136, topPos + 34, imageWidth - 144, imageHeight - 66),
    val inventoryBounds: UiRect = UiRect(leftPos + 136, topPos + imageHeight - 116, imageWidth - 144, 76),
    val statusBarBounds: UiRect = UiRect(leftPos + 136, topPos + imageHeight - 28, imageWidth - 144, 20),
    val terminalDockBounds: UiRect? = null,
    val targetSlotBounds: UiRect = UiRect(leftPos + imageWidth - 28, topPos + 7, 18, 18),
    val terminalToggleBounds: UiRect = UiRect(leftPos + 8, topPos + 8, 72, 20),
    val rebootBounds: UiRect = UiRect(leftPos + 8 + 4 * 80, topPos + 8, 72, 20),
) {
    val editorLineHeight: Int = LINE_HEIGHT

    fun visibleEditorLines(): Int = ((editorBounds.height - 6) / LINE_HEIGHT).coerceAtLeast(1)

    fun editorTextOrigin(): Pair<Int, Int> = editorBounds.x + EDITOR_TEXT_PADDING_X to editorBounds.y + 6

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
        var rowY = sidebarBounds.y + 22
        val rowWidth = (sidebarBounds.width - 8).coerceAtLeast(32)

        if (state.browserPath.isNotEmpty()) {
            rows +=
                WorkspaceRowLayout(
                    label = "..",
                    bounds = UiRect(sidebarBounds.x + 2, rowY - 1, rowWidth, 11),
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
                    bounds = UiRect(sidebarBounds.x + 2, rowY - 1, rowWidth, 11),
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
        val popupY = editorTextOrigin().second + visibleLine * LINE_HEIGHT
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

    private fun toolbarButtonBounds(index: Int): UiRect = UiRect(headerBounds.x + 8 + index * 80, headerBounds.y + (headerBounds.height - 20) / 2, 72, 20)

    companion object {
        fun fullscreen(
            leftPos: Int,
            topPos: Int,
            screenWidth: Int,
            screenHeight: Int,
            terminalVisible: Boolean,
            font: FontMetrics,
        ): WorkbenchLayoutModel {
            val headerBounds = UiRect(leftPos, topPos, screenWidth, FULLSCREEN_HEADER_HEIGHT)
            val bodyTop = headerBounds.bottom + FULLSCREEN_SECTION_GAP
            val sidebarBounds =
                UiRect(
                    leftPos + FULLSCREEN_OUTER_PADDING,
                    bodyTop,
                    FULLSCREEN_SIDEBAR_WIDTH,
                    (screenHeight - FULLSCREEN_HEADER_HEIGHT - FULLSCREEN_STATUS_HEIGHT - FULLSCREEN_OUTER_PADDING * 2 - FULLSCREEN_SECTION_GAP)
                        .coerceAtLeast(1),
                )
            val statusBarBounds = UiRect(leftPos, topPos + screenHeight - FULLSCREEN_STATUS_HEIGHT, screenWidth, FULLSCREEN_STATUS_HEIGHT)
            val inventoryBounds =
                UiRect(
                    sidebarBounds.right + FULLSCREEN_SECTION_GAP,
                    statusBarBounds.y - FULLSCREEN_INVENTORY_HEIGHT - FULLSCREEN_SECTION_GAP,
                    screenWidth - FULLSCREEN_SIDEBAR_WIDTH - FULLSCREEN_OUTER_PADDING * 2 - FULLSCREEN_SECTION_GAP,
                    FULLSCREEN_INVENTORY_HEIGHT,
                )
            val terminalDockBounds =
                if (terminalVisible) {
                    UiRect(
                        sidebarBounds.right + FULLSCREEN_SECTION_GAP,
                        inventoryBounds.y - FULLSCREEN_DOCK_HEIGHT - FULLSCREEN_SECTION_GAP,
                        screenWidth - FULLSCREEN_SIDEBAR_WIDTH - FULLSCREEN_OUTER_PADDING * 2 - FULLSCREEN_SECTION_GAP,
                        FULLSCREEN_DOCK_HEIGHT,
                    )
                } else {
                    null
                }
            val editorBottom = (terminalDockBounds?.y ?: inventoryBounds.y) - FULLSCREEN_SECTION_GAP
            val editorBounds =
                UiRect(
                    sidebarBounds.right + FULLSCREEN_SECTION_GAP,
                    bodyTop,
                    screenWidth - FULLSCREEN_SIDEBAR_WIDTH - FULLSCREEN_OUTER_PADDING * 2 - FULLSCREEN_SECTION_GAP,
                    (editorBottom - bodyTop).coerceAtLeast(1),
                )
            val targetSlotBounds = UiRect(leftPos + screenWidth - 150, topPos + 7, 18, 18)
            val terminalToggleBounds = UiRect(leftPos + screenWidth - 126, topPos + 6, 56, 20)
            val rebootBounds = UiRect(leftPos + screenWidth - 64, topPos + 6, 56, 20)
            return WorkbenchLayoutModel(
                leftPos = leftPos,
                topPos = topPos,
                imageWidth = screenWidth,
                imageHeight = screenHeight,
                font = font,
                headerBounds = headerBounds,
                sidebarBounds = sidebarBounds,
                editorBounds = editorBounds,
                inventoryBounds = inventoryBounds,
                statusBarBounds = statusBarBounds,
                terminalDockBounds = terminalDockBounds,
                targetSlotBounds = targetSlotBounds,
                terminalToggleBounds = terminalToggleBounds,
                rebootBounds = rebootBounds,
            )
        }

        const val LINE_HEIGHT = 10
        const val WORKSPACE_ROW_STEP = 12
        const val COMPLETION_ROW_HEIGHT = 12
        const val MAX_COMPLETION_ITEMS = 8
        private const val LEGACY_HEADER_HEIGHT = 32
        private const val EDITOR_TEXT_PADDING_X = 40
        private const val FULLSCREEN_HEADER_HEIGHT = 32
        private const val FULLSCREEN_STATUS_HEIGHT = 20
        private const val FULLSCREEN_OUTER_PADDING = 12
        private const val FULLSCREEN_SECTION_GAP = 12
        private const val FULLSCREEN_SIDEBAR_WIDTH = 220
        private const val FULLSCREEN_INVENTORY_HEIGHT = 76
        private const val FULLSCREEN_DOCK_HEIGHT = 180
    }

    private fun editorLines(state: WorkbenchState): List<String> =
        if (state.editor.text.isEmpty()) {
            listOf("")
        } else {
            state.editor.text.split('\n')
        }
}
