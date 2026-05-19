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

package ru.lazyhat.compukterkraft.common.notebook.screen

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenuWithoutInventory
import ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreen
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.focusable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value

class NotebookScreen(
    menu: ComputerMenuWithoutInventory,
    inventory: Inventory,
    title: Component,
) : ComputerTerminalScreen<ComputerMenuWithoutInventory>(menu, inventory, title) {
    init {
        imageWidth = WorkbenchTerminalMetrics.imageWidth(TERMINAL_COLUMNS)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(TERMINAL_ROWS, contentTopInset = NOTEBOOK_CONTENT_TOP)
    }

    override fun content(): UiElement {
        val layout = currentLayout()
        val terminalRelX = layout.terminalBounds.x - leftPos
        val terminalRelY = layout.terminalBounds.y - topPos
        val surfaceRelX = layout.terminalSurfaceBounds.x - leftPos
        val surfaceRelY = layout.terminalSurfaceBounds.y - topPos
        val statusRelX = layout.statusBounds.x - leftPos
        val statusRelY = layout.statusBounds.y - topPos

        return ui(Modifier.size(imageWidth, imageHeight).background(BACKGROUND)) {
            text(
                modifier = Modifier.offset(12, 8),
                color = TITLE,
            ) {
                title.string
            }
            text(
                modifier = Modifier.offset(imageWidth - 128, 8),
                color = STATUS,
            ) {
                when {
                    !menu.isComputerOn -> "OFF"
                    menu.clientSide.displayBuffer?.hasReceivedFrames == true -> "RUNNING"
                    else -> "CONNECTING"
                }
            }

            If(value { menu.isComputerOn }) {
                canvas(
                    modifier =
                        Modifier
                            .offset(terminalRelX, terminalRelY)
                            .size(layout.terminalBounds.width, layout.terminalBounds.height)
                            .focusable(
                                id = "notebook-display",
                                onKeyPressed = { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) },
                                onKeyReleased = { keyCode -> terminalInput.keyReleased(keyCode, 0) },
                                onCharTyped = { ch -> terminalInput.charTyped(ch) },
                            ),
                ) {
                    drawDisplayPlaceholder(layout.terminalBounds.width, layout.terminalBounds.height)
                }
            }

            If(value { !menu.isComputerOn }) {
                text(
                    modifier = Modifier.offset(surfaceRelX + 12, surfaceRelY + 12),
                    color = STATUS,
                ) {
                    "POWERED OFF"
                }
            }

            text(
                modifier = Modifier.offset(statusRelX + 8, statusRelY + 6),
                color = DIM,
            ) {
                "${TERMINAL_COLUMNS * TerminalFontConstants.FONT_WIDTH} x ${TERMINAL_ROWS * TerminalFontConstants.FONT_HEIGHT}"
            }

            laptopButton(
                x = imageWidth - 154,
                y = statusRelY + 3,
                label = { if (menu.isComputerOn) "SHUTDOWN" else "POWER" },
                action = { if (menu.isComputerOn) ComputerControlAction.SHUTDOWN else ComputerControlAction.TURN_ON },
            )
            laptopButton(
                x = imageWidth - 74,
                y = statusRelY + 3,
                label = { "REBOOT" },
                action = { ComputerControlAction.REBOOT },
            )
        }
    }

    override fun currentLayout(): WorkbenchTerminalLayout =
        WorkbenchTerminalMetrics.layout(
            leftPos = leftPos,
            topPos = topPos,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            terminalColumns = TERMINAL_COLUMNS,
            terminalRows = TERMINAL_ROWS,
            contentTopInset = NOTEBOOK_CONTENT_TOP,
        )

    private fun ru.lazyhat.compukterkraft.core.ui.foundation.UiScope.laptopButton(
        x: Int,
        y: Int,
        label: () -> String,
        action: () -> ComputerControlAction,
    ) {
        button(
            modifier = Modifier.offset(x, y).size(68, 14).background(BUTTON),
            onClick = { inputHandler.accept(ControlInputEvent(action())) },
        ) {
            text(
                modifier = Modifier.offset(5, 3),
                color = BUTTON_TEXT,
                text = label,
            )
        }
    }

    companion object {
        private val TERMINAL_COLUMNS = Config.DEFAULT_COMPUTER_TERM_WIDTH
        private val TERMINAL_ROWS = Config.DEFAULT_COMPUTER_TERM_HEIGHT
        private const val NOTEBOOK_CONTENT_TOP = 32
        private val BACKGROUND = Color.hex(0xFF101318U)
        private val TITLE = Color.hex(0xFFE6ECF5U)
        private val STATUS = Color.hex(0xFF7CFFB2U)
        private val DIM = Color.hex(0xFF798394U)
        private val BUTTON = Color.hex(0xFF202735U)
        private val BUTTON_TEXT = Color.hex(0xFFE6ECF5U)
    }
}
