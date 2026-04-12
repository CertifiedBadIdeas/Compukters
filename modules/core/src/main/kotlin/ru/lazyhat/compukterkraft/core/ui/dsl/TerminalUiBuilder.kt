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

package ru.lazyhat.compukterkraft.core.ui.dsl

import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Colour constants for the workbench terminal chrome.
 */
private object TerminalColors {
    const val WINDOW_BACKGROUND: Int = 0xFF12151D.toInt()
    const val PANEL_BACKGROUND: Int = 0xFF0D1016.toInt()
    const val PANEL_BORDER: Int = 0xFF1D2330.toInt()
    const val STATUS_BACKGROUND: Int = 0xFF161B25.toInt()
    const val TERMINAL_BACKGROUND: Int = 0xFF05070B.toInt()
    const val TERMINAL_BORDER: Int = 0xFF222938.toInt()
    const val TERMINAL_BORDER_FOCUSED: Int = 0xFF4883C7.toInt()
    const val TITLE_COLOR: Int = 0xE6ECF5
    const val MUTED_TEXT: Int = 0x9CA8B8
}

/**
 * Build a declarative UI node list for the terminal panel.
 *
 * This is a **pure function** — given the same inputs, it always produces the same output.
 * It can be tested without Minecraft.
 */
fun buildTerminalUi(
    leftPos: Int,
    topPos: Int,
    imageWidth: Int,
    imageHeight: Int,
    layout: WorkbenchTerminalLayout,
    terminalState: WorkbenchTerminalViewState,
    focused: Boolean,
    showFocusHint: Boolean,
    poweredOffText: String,
    connectingText: String,
): List<UiNode> =
    buildList {
        // Window background
        add(Rect(leftPos, topPos, imageWidth, imageHeight, TerminalColors.WINDOW_BACKGROUND))

        // Panel background
        add(
            Rect(
                layout.panelBounds.x,
                layout.panelBounds.y,
                layout.panelBounds.width,
                layout.panelBounds.height,
                TerminalColors.PANEL_BACKGROUND,
            ),
        )

        // Panel top border (1px line)
        add(Rect(layout.panelBounds.x, layout.panelBounds.y, layout.panelBounds.width, 1, TerminalColors.PANEL_BORDER))

        // Status bar background
        add(
            Rect(
                layout.statusBounds.x,
                layout.statusBounds.y,
                layout.statusBounds.width,
                layout.statusBounds.height,
                TerminalColors.STATUS_BACKGROUND,
            ),
        )

        // Terminal border
        val borderColour =
            if (terminalState is WorkbenchTerminalViewState.Active && focused) {
                TerminalColors.TERMINAL_BORDER_FOCUSED
            } else {
                TerminalColors.TERMINAL_BORDER
            }

        add(
            Rect(
                layout.terminalSurfaceBounds.x - 1,
                layout.terminalSurfaceBounds.y - 1,
                layout.terminalSurfaceBounds.width + 2,
                layout.terminalSurfaceBounds.height + 2,
                borderColour,
            ),
        )

        // Terminal background
        if (terminalState is WorkbenchTerminalViewState.Active) {
            add(
                Rect(
                    layout.terminalSurfaceBounds.x,
                    layout.terminalSurfaceBounds.y,
                    layout.terminalSurfaceBounds.width,
                    layout.terminalSurfaceBounds.height,
                    TerminalColors.TERMINAL_BACKGROUND,
                ),
            )
        }

        // Status bar text
        if (terminalState is WorkbenchTerminalViewState.Active) {
            val statusText = if (showFocusHint) "Click terminal to focus input" else "Input active  |  Ctrl+V paste"
            add(Text(layout.statusBounds.x + 12, layout.statusBounds.y + 6, statusText, TerminalColors.MUTED_TEXT))
        }

        // Size text (right-aligned)
        when (terminalState) {
            is WorkbenchTerminalViewState.Active -> {
                val snapshot: ScreenBufferSnapshot = terminalState.snapshot
                val sizeText = "${snapshot.width} x ${snapshot.height}"
                add(
                    RightAlignedText(
                        layout.statusBounds.x + 12,
                        layout.statusBounds.y + 6,
                        layout.statusBounds.width - 24,
                        sizeText,
                        TerminalColors.MUTED_TEXT,
                    ),
                )
                add(TerminalView(layout.terminalBounds.x, layout.terminalBounds.y, snapshot))
            }

            WorkbenchTerminalViewState.PoweredOff -> {
                add(
                    Text(
                        layout.terminalSurfaceBounds.x + 12,
                        layout.terminalSurfaceBounds.y + 12,
                        poweredOffText,
                        TerminalColors.MUTED_TEXT,
                    ),
                )
            }

            WorkbenchTerminalViewState.Connecting -> {
                add(
                    Text(
                        layout.terminalSurfaceBounds.x + 12,
                        layout.terminalSurfaceBounds.y + 12,
                        connectingText,
                        TerminalColors.MUTED_TEXT,
                    ),
                )
            }
        }
    }
