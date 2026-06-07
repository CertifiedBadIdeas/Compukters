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
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerDisplayScreen
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
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

private enum class NotebookRuntimeState(
    val label: String,
) {
    OFF("OFFLINE"),
    FAILED("ERROR"),
    CONNECTING("BOOTING"),
    RUNNING("RUNNING"),
}

class NotebookScreen(
    menu: ComputerMenuWithoutInventory,
    inventory: Inventory,
    title: Component,
) : ComputerDisplayScreen<ComputerMenuWithoutInventory>(menu, inventory, title) {
    override val displayId: Int = NOTEBOOK_DISPLAY_ID
    override val terminalColumns: Int = TERMINAL_COLUMNS
    override val terminalRows: Int = TERMINAL_ROWS

    init {
        imageWidth = WorkbenchTerminalMetrics.imageWidth(TERMINAL_COLUMNS)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(TERMINAL_ROWS, contentTopInset = NOTEBOOK_CONTENT_TOP)
    }

    override fun content(): UiElement {
        val layout = currentLayout()
        val displayBounds = currentDisplayBounds(layout)
        val displayRelX = displayBounds.x - leftPos
        val displayRelY = displayBounds.y - topPos
        val surfaceRelX = layout.terminalSurfaceBounds.x - leftPos
        val surfaceRelY = layout.terminalSurfaceBounds.y - topPos
        val statusRelX = layout.statusBounds.x - leftPos
        val statusRelY = layout.statusBounds.y - topPos
        val moduleBayX = statusRelX + 104
        val moduleBayY = statusRelY + 3

        return ui(Modifier.size(imageWidth, imageHeight).background(BACKGROUND)) {
            text(
                modifier = Modifier.offset(12, 8),
                color = TITLE,
            ) {
                title.string
            }
            text(
                modifier = Modifier.offset(12, 20),
                color = DIM,
            ) {
                "K16 LAPTOP"
            }
            text(
                modifier = Modifier.offset(imageWidth - 156, 8),
                color = { runtimeStateColor(runtimeState()) },
            ) {
                "STATE: ${runtimeState().label}"
            }

            If(value { menu.isComputerOn }) {
                canvas(
                    modifier =
                        Modifier
                            .offset(displayRelX, displayRelY)
                            .size(displayBounds.width, displayBounds.height)
                            .focusable(
                                id = "notebook-display",
                                onKeyPressed = { keyCode -> terminalInput.keyPressed(keyCode, 0, 0) },
                                onKeyReleased = { keyCode -> terminalInput.keyReleased(keyCode, 0) },
                                onCharTyped = { ch -> terminalInput.charTyped(ch) },
                            ),
                ) {
                    drawDisplayPlaceholder(displayBounds.width, displayBounds.height)
                }
            }

            If(value { !menu.isComputerOn }) {
                text(
                    modifier = Modifier.offset(surfaceRelX + 12, surfaceRelY + 12),
                    color = DIM,
                ) {
                    "POWERED OFF - PRESS POWER"
                }
            }

            text(
                modifier = Modifier.offset(statusRelX + 8, statusRelY + 6),
                color = DIM,
            ) {
                displayResolutionText(currentDisplayWidth(), currentDisplayHeight())
            }
            moduleBay(moduleBayX, moduleBayY)

            laptopButton(
                x = imageWidth - 154,
                y = statusRelY + 3,
                label = { if (menu.isComputerOn) "SHUTDOWN" else "POWER" },
                enabled = { true },
                action = { if (menu.isComputerOn) ComputerControlAction.SHUTDOWN else ComputerControlAction.TURN_ON },
            )
            laptopButton(
                x = imageWidth - 74,
                y = statusRelY + 3,
                label = { "REBOOT" },
                enabled = { menu.isComputerOn },
                beforeAction = { resetDisplayBufferForRuntimeRestart() },
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
        enabled: () -> Boolean,
        beforeAction: () -> Unit = {},
        action: () -> ComputerControlAction,
    ) {
        button(
            modifier = Modifier.offset(x, y).size(68, 14),
            onClick = {
                if (enabled()) {
                    beforeAction()
                    inputHandler.accept(ControlInputEvent(action()))
                }
            },
        ) {
            canvas(Modifier.size(68, 14)) {
                drawButtonChrome(if (enabled()) BUTTON else BUTTON_DISABLED)
            }
            text(
                modifier = Modifier.offset(5, 3),
                color = { if (enabled()) BUTTON_TEXT else BUTTON_TEXT_DISABLED },
                text = label,
            )
        }
    }

    private fun ru.lazyhat.compukterkraft.core.ui.foundation.UiScope.moduleBay(
        x: Int,
        y: Int,
    ) {
        canvas(Modifier.offset(x, y).size(126, 14)) {
            fillRect(0, 0, 126, 14, MODULE_BAY)
            fillRect(0, 0, 126, 1, MODULE_BAY_BORDER)
            fillRect(0, 13, 126, 1, MODULE_BAY_BORDER)
            fillRect(0, 0, 1, 14, MODULE_BAY_BORDER)
            fillRect(125, 0, 1, 14, MODULE_BAY_BORDER)
        }
        text(
            modifier = Modifier.offset(x + 6, y + 3),
            color = DIM,
        ) {
            "MODULE BAY: EMPTY"
        }
    }

    private fun ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope.drawButtonChrome(color: Color) {
        fillRect(0, 0, 68, 14, color)
        fillRect(0, 0, 68, 1, BUTTON_BORDER)
        fillRect(0, 13, 68, 1, BUTTON_BORDER)
        fillRect(0, 0, 1, 14, BUTTON_BORDER)
        fillRect(67, 0, 1, 14, BUTTON_BORDER)
    }

    private fun runtimeState(): NotebookRuntimeState =
        when {
            menu.hasComputerRuntimeFailure -> NotebookRuntimeState.FAILED
            !menu.isComputerOn -> NotebookRuntimeState.OFF
            menu.clientSide.displayBuffer?.hasReceivedFrames == true -> NotebookRuntimeState.RUNNING
            else -> NotebookRuntimeState.CONNECTING
        }

    private fun runtimeStateColor(state: NotebookRuntimeState): Color =
        when (state) {
            NotebookRuntimeState.OFF -> DIM
            NotebookRuntimeState.FAILED -> BOOTING
            NotebookRuntimeState.CONNECTING -> BOOTING
            NotebookRuntimeState.RUNNING -> STATUS
        }

    companion object {
        private const val NOTEBOOK_DISPLAY_ID = 1
        private val TERMINAL_COLUMNS = Config.DEFAULT_COMPUTER_TERM_WIDTH
        private val TERMINAL_ROWS = Config.DEFAULT_COMPUTER_TERM_HEIGHT
        private const val NOTEBOOK_CONTENT_TOP = 32
        private val BACKGROUND = Color.hex(0xFF101318U)
        private val TITLE = Color.hex(0xFFE6ECF5U)
        private val STATUS = Color.hex(0xFF7CFFB2U)
        private val BOOTING = Color.hex(0xFFFFD37CU)
        private val DIM = Color.hex(0xFF798394U)
        private val BUTTON = Color.hex(0xFF202735U)
        private val BUTTON_DISABLED = Color.hex(0xFF171B23U)
        private val BUTTON_BORDER = Color.hex(0xFF2C3444U)
        private val BUTTON_TEXT = Color.hex(0xFFE6ECF5U)
        private val BUTTON_TEXT_DISABLED = Color.hex(0xFF606A78U)
        private val MODULE_BAY = Color.hex(0xFF151A22U)
        private val MODULE_BAY_BORDER = Color.hex(0xFF2C3444U)
    }
}
