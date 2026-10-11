/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.ide.client.state.IdeFileTab
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

data class IdeFileTabCell(
    val file: IdeFileTab,
    val bounds: IdeRect,
    val close: IdeRect,
    val label: String,
)

data class IdeFileTabLayout(
    val cells: List<IdeFileTabCell>,
    val firstIndex: Int,
    val previous: IdeRect?,
    val next: IdeRect?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

data class IdeFileTabDraw(
    val controls: List<IdeControlDraw>,
    val text: List<IdeTextDraw>,
    val fills: List<IdeFillDraw>,
)

object IdeFileTabBar {
    fun layout(
        bounds: IdeRect,
        files: List<IdeFileTab>,
        firstIndex: Int = 0,
        reveal: ProjectPath? = null,
    ): IdeFileTabLayout {
        if (files.isEmpty() || bounds.width <= 0) return IdeFileTabLayout(emptyList(), 0, null, null, false, false)
        val names = files.map { it.path.value.substringAfterLast('/') }
        val labels =
            files.mapIndexed { i, file ->
                if (names.count { it == names[i] } > 1) file.path.value else names[i]
            }

        fun width(index: Int) = (labels[index].length.coerceAtMost(28) * IdeCodeFontProfile.DEFAULT.cellWidth + 48).coerceIn(84, 204)
        val overflow = files.indices.sumOf(::width) > bounds.width
        val arrowWidth = if (overflow) 20 else 0
        val left = bounds.left + arrowWidth
        val right = bounds.right - arrowWidth

        fun cells(start: Int): List<IdeFileTabCell> {
            val cells = mutableListOf<IdeFileTabCell>()
            var x = left
            for (i in start until files.size) {
                val available = right - x
                if (available < width(i) && cells.isNotEmpty()) break
                if (available < 32) break
                val cellWidth = minOf(width(i), available)
                val cell = IdeRect(x, bounds.top, x + cellWidth, bounds.bottom)
                val close = IdeRect(cell.right - 20, cell.top + 2, cell.right - 2, cell.bottom - 2)
                val maxChars = ((close.left - cell.left - 10) / IdeCodeFontProfile.DEFAULT.cellWidth - 2).coerceAtLeast(1)
                val label = if (labels[i].length > maxChars) labels[i].take((maxChars - 1).coerceAtLeast(0)) + "…" else labels[i]
                cells += IdeFileTabCell(files[i], cell, close, label)
                x += cellWidth
            }
            return cells
        }
        var first = if (overflow) firstIndex.coerceIn(files.indices) else 0
        var visible = cells(first)
        val selected = files.indexOfFirst { it.path == reveal }
        if (selected >= 0 && visible.none { it.file.path == reveal }) {
            first = selected
            visible = cells(first)
        }
        return IdeFileTabLayout(
            visible,
            first,
            if (overflow) IdeRect(bounds.left, bounds.top, left, bounds.bottom) else null,
            if (overflow) IdeRect(right, bounds.top, bounds.right, bounds.bottom) else null,
            first > 0,
            first + visible.size < files.size,
        )
    }

    fun draw(
        layout: IdeFileTabLayout,
        active: ProjectPath?,
        enabled: Boolean,
        font: IdeCodeFontProfile = IdeCodeFontProfile.DEFAULT,
    ): IdeFileTabDraw {
        val text = mutableListOf<IdeTextDraw>()

        fun label(
            value: String,
            x: Int,
            y: Int,
            color: Int,
            clip: IdeRect,
        ) {
            text += IdeTextDraw(IdeTextKind.FileTab, value, x, y + font.glyphDrawOffsetY, color, IdeTextStyle.Ui, font, clip, null, 30)
        }
        layout.cells.forEach { cell ->
            val selected = cell.file.path == active
            val suffix =
                if (cell.file.conflict) {
                    " !"
                } else if (cell.file.dirty) {
                    " •"
                } else {
                    ""
                }
            val color = if (selected) IdeColors.TEXT else IdeColors.MUTED
            val labelBounds = IdeRect(cell.bounds.left + 4, cell.bounds.top, cell.close.left, cell.bounds.bottom)
            label(cell.label + suffix, labelBounds.left, font.centeredCellTop(cell.bounds), color, labelBounds)
            label("×", cell.close.left + 5, font.centeredCellTop(cell.close), IdeColors.MUTED, cell.close)
        }
        layout.previous?.let {
            label("‹", it.left + 6, font.centeredCellTop(it), if (layout.hasPrevious) IdeColors.TEXT else IdeColors.MUTED, it)
        }
        layout.next?.let {
            label("›", it.left + 6, font.centeredCellTop(it), if (layout.hasNext) IdeColors.TEXT else IdeColors.MUTED, it)
        }
        val controls =
            controls(layout, active, enabled).map { control ->
                val color = if (control.target.filePath == active) IdeColors.EDITOR else IdeColors.PANEL_ALT
                if (control.target.action in setOf(IdeHitAction.FileTab, IdeHitAction.CloseFileTab)) {
                    control.copy(panel = control.panel.copy(color = color))
                } else {
                    control
                }
            }
        val fills =
            layout.cells.filter { it.file.path == active }.map { cell ->
                IdeFillDraw(
                    IdeFillKind.Border,
                    IdeRect(cell.bounds.left, cell.bounds.bottom - 2, cell.bounds.right, cell.bounds.bottom),
                    IdeColors.ACCENT,
                    31,
                )
            }
        return IdeFileTabDraw(controls, text, fills)
    }

    fun controls(
        layout: IdeFileTabLayout,
        active: ProjectPath?,
        enabled: Boolean,
    ): List<IdeControlDraw> =
        buildList {
            layout.cells.forEach { cell ->
                add(
                    IdeControls.target(
                        IdeHitTarget(
                            IdeHitAction.FileTab,
                            cell.bounds,
                            enabled,
                            cell.file.path.value,
                            IdeFocusGroup.Page,
                            40,
                            selected =
                                cell.file.path == active,
                            filePath = cell.file.path,
                        ),
                        IdeControlStyle.Standard,
                    ),
                )
                add(
                    IdeControls.target(
                        IdeHitTarget(
                            IdeHitAction.CloseFileTab,
                            cell.close,
                            enabled,
                            "Close ${cell.file.path.value}",
                            IdeFocusGroup.Page,
                            41,
                            filePath = cell.file.path,
                        ),
                        IdeControlStyle.Standard,
                    ),
                )
            }
            layout.previous?.let {
                add(
                    IdeControls.target(
                        IdeHitTarget(
                            IdeHitAction.PreviousFileTabs,
                            it,
                            enabled && layout.hasPrevious,
                            "Previous files",
                            IdeFocusGroup.Page,
                            40,
                        ),
                        IdeControlStyle.Standard,
                    ),
                )
            }
            layout.next?.let {
                add(
                    IdeControls.target(
                        IdeHitTarget(IdeHitAction.NextFileTabs, it, enabled && layout.hasNext, "Next files", IdeFocusGroup.Page, 40),
                        IdeControlStyle.Standard,
                    ),
                )
            }
        }
}
