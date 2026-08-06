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
package ru.lazyhat.compukterkraft.common.terminal.screen

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerDisplayScreen
import ru.lazyhat.compukterkraft.common.localization.CompukterKeys
import ru.lazyhat.compukterkraft.common.localization.CompukterTranslatable
import ru.lazyhat.compukterkraft.common.ui.dsl.translatable
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.kraftui.foundation.CanvasScope
import ru.lazyhat.kraftui.foundation.Color
import ru.lazyhat.kraftui.foundation.HoverState
import ru.lazyhat.kraftui.foundation.UiElement
import ru.lazyhat.kraftui.foundation.modifier.Modifier
import ru.lazyhat.kraftui.foundation.modifier.background
import ru.lazyhat.kraftui.foundation.modifier.focusable
import ru.lazyhat.kraftui.foundation.modifier.hoverable
import ru.lazyhat.kraftui.foundation.modifier.offset
import ru.lazyhat.kraftui.foundation.modifier.size
import ru.lazyhat.kraftui.foundation.modifier.tooltip
import ru.lazyhat.kraftui.foundation.ui
import ru.lazyhat.kraftui.foundation.value

/**
 * Terminal screen authored with the UI DSL. Mirrors the historical
 * hand-rolled `renderBg`/`render` flow in terms of visual and input
 * behaviour, but expresses it declaratively:
 *
 *  - Dynamic text, visibility branches (powered-off / connecting /
 *    active) and snapshot contents flow through `ValueExpression`s —
 *    no recompile is needed per frame.
 *  - The snapshot's grid dimensions still drive `imageWidth`/
 *    `imageHeight`; when they change, [ComputerDisplayScreen] auto-
 *    recompiles the layout (see Epic 3).
 *  - Power/reboot buttons are `canvas` draw lambdas fed by
 *    `HoverState` flags so hover chrome and icon color are resolved
 *    inline, without creating a new draw list on hover.
 *  - Tooltip text is routed through the new `Modifier.tooltip` hook
 *    which the runtime forwards to Minecraft's tooltip pipeline.
 */
open class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : ComputerDisplayScreen<T>(container, player, title) {
    private val powerHover = HoverState()
    private val rebootHover = HoverState()

    init {
        val cols = DEFAULT_COLS
        val rows = DEFAULT_ROWS
        imageWidth = WorkbenchTerminalMetrics.imageWidth(cols)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(rows, contentTopInset = COMPUTER_CONTENT_TOP)
    }

    override fun content(): UiElement {
        val cols = DEFAULT_COLS
        val rows = DEFAULT_ROWS
        val layout =
            WorkbenchTerminalMetrics.layout(
                leftPos = leftPos,
                topPos = topPos,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                terminalColumns = cols,
                terminalRows = rows,
                contentTopInset = COMPUTER_CONTENT_TOP,
            )

        val rebootBtn = statusButtonBounds(layout.statusBounds, slotFromRight = 0)
        val powerBtn = statusButtonBounds(layout.statusBounds, slotFromRight = 1)
        val displayBounds = currentDisplayBounds(layout)

        val statusRelX = layout.statusBounds.x - leftPos
        val statusRelY = layout.statusBounds.y - topPos
        val displayRelX = displayBounds.x - leftPos
        val displayRelY = displayBounds.y - topPos
        val surfaceRelX = layout.terminalSurfaceBounds.x - leftPos
        val surfaceRelY = layout.terminalSurfaceBounds.y - topPos
        val powerRelX = powerBtn.x - leftPos
        val powerRelY = powerBtn.y - topPos
        val rebootRelX = rebootBtn.x - leftPos
        val rebootRelY = rebootBtn.y - topPos
        val resolutionTextWidth = font.width(displayResolutionText(currentDisplayWidth(), currentDisplayHeight()))
        val resolutionRelX =
            statusRightAlignedTextX(
                statusBounds = layout.statusBounds,
                rightBoundaryX = powerBtn.x,
                textWidth = resolutionTextWidth,
                gap = RESOLUTION_BUTTON_GAP,
            ) - leftPos

        return ui(Modifier.size(imageWidth, imageHeight).background(BACKGROUND)) {
            text(
                modifier = Modifier.offset(statusRelX + 12, statusRelY + 6),
                color = STATUS_TEXT_COLOR,
                text =
                    translatable {
                        when {
                            !menu.isComputerOn -> CompukterKeys.Gui.Terminal.POWERED_OFF
                            hasRetainedDisplayPresentation() -> CompukterKeys.Gui.Terminal.FOCUSED
                            else -> CompukterKeys.Gui.Terminal.CONNECTING
                        }
                    },
            )

            If(value { menu.isComputerOn }) {
                text(
                    modifier =
                        Modifier.offset(
                            resolutionRelX,
                            statusRelY + 6,
                        ),
                    color = STATUS_TEXT_COLOR,
                    text =
                        value {
                            if (hasRetainedDisplayPresentation()) {
                                displayResolutionText(currentDisplayWidth(), currentDisplayHeight())
                            } else {
                                ""
                            }
                        },
                )

                canvas(
                    modifier =
                        Modifier
                            .offset(displayRelX, displayRelY)
                            .size(displayBounds.width, displayBounds.height)
                            .focusable(
                                id = "computer-display",
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
                    color = STATUS_TEXT_COLOR,
                    text =
                        translatable {
                            CompukterKeys.Gui.Terminal.POWERED_OFF
                        },
                )
            }

            button(
                modifier =
                    Modifier
                        .offset(powerRelX, powerRelY)
                        .size(STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)
                        .hoverable(powerHover)
                        .tooltip(
                            translatable {
                                if (menu.isComputerOn) {
                                    CompukterKeys.Gui.Control.SHUTDOWN
                                } else {
                                    CompukterKeys.Gui.Control.TURN_ON
                                }
                            },
                        ),
                onClick = {
                    val action =
                        if (menu.isComputerOn) ComputerControlAction.SHUTDOWN else ComputerControlAction.TURN_ON
                    inputHandler.accept(ControlInputEvent(action))
                },
            ) {
                canvas(Modifier.size(STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)) {
                    drawButtonChrome(bg = if (powerHover.isHovered) BUTTON_BG_HOVER else BUTTON_BG, accent = POWER_ACCENT)
                    drawPowerIcon(BUTTON_ICON)
                }
            }

            button(
                modifier =
                    Modifier
                        .offset(rebootRelX, rebootRelY)
                        .size(STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)
                        .hoverable(rebootHover)
                        .tooltip(CompukterTranslatable.Gui.Control.reboot),
                onClick = {
                    inputHandler.accept(ControlInputEvent(ComputerControlAction.REBOOT))
                },
            ) {
                canvas(Modifier.size(STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)) {
                    drawButtonChrome(bg = if (rebootHover.isHovered) BUTTON_BG_HOVER else BUTTON_BG, accent = REBOOT_ACCENT)
                    drawRebootIcon(BUTTON_ICON)
                }
            }
        }
    }

    override fun currentLayout() =
        WorkbenchTerminalMetrics.layout(
            leftPos = leftPos,
            topPos = topPos,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            terminalColumns = DEFAULT_COLS,
            terminalRows = DEFAULT_ROWS,
            contentTopInset = COMPUTER_CONTENT_TOP,
        )

    private fun statusButtonBounds(
        statusBounds: TerminalRect,
        slotFromRight: Int,
    ): TerminalRect {
        val x =
            statusBounds.x + statusBounds.width - STATUS_BUTTON_MARGIN_END -
                STATUS_BUTTON_SIZE * (slotFromRight + 1) -
                STATUS_BUTTON_GAP * slotFromRight
        val y = statusBounds.y + (statusBounds.height - STATUS_BUTTON_SIZE) / 2
        return TerminalRect(x, y, STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE)
    }

    private fun statusRightAlignedTextX(
        statusBounds: TerminalRect,
        rightBoundaryX: Int,
        textWidth: Int,
        gap: Int,
    ): Int = (rightBoundaryX - gap - textWidth).coerceAtLeast(statusBounds.x + STATUS_TEXT_START_PADDING)

    private fun isInventoryKey(
        keyCode: Int,
        scanCode: Int,
    ): Boolean = minecraft?.options?.keyInventory?.matches(keyCode, scanCode) == true

    private fun CanvasScope.drawButtonChrome(
        bg: Color,
        accent: Color,
    ) {
        fillRect(0, 0, STATUS_BUTTON_SIZE, STATUS_BUTTON_SIZE, bg)
        // Top accent strip.
        fillRect(0, 0, STATUS_BUTTON_SIZE, 1, accent)
        // Bottom border.
        fillRect(0, STATUS_BUTTON_SIZE - 1, STATUS_BUTTON_SIZE, 1, BUTTON_BORDER)
        // Left border.
        fillRect(0, 0, 1, STATUS_BUTTON_SIZE, BUTTON_BORDER)
        // Right border.
        fillRect(STATUS_BUTTON_SIZE - 1, 0, 1, STATUS_BUTTON_SIZE, BUTTON_BORDER)
    }

    private fun CanvasScope.drawPowerIcon(color: Color) {
        // Historical glyph uses origin (buttonX+4, buttonY+3). Canvas
        // is already button-local so we add the same (4, 3) offset.
        val ox = 4
        val oy = 3
        fillRect(ox + 4, oy + 0, 2, 5, color)
        fillRect(ox + 2, oy + 4, 2, 5, color)
        fillRect(ox + 6, oy + 4, 2, 5, color)
        fillRect(ox + 3, oy + 8, 4, 2, color)
    }

    private fun CanvasScope.drawRebootIcon(color: Color) {
        val ox = 3
        val oy = 3
        fillRect(ox + 2, oy + 0, 6, 2, color)
        fillRect(ox + 1, oy + 2, 2, 5, color)
        fillRect(ox + 3, oy + 6, 5, 2, color)
        fillRect(ox + 7, oy + 1, 2, 5, color)
        fillRect(ox + 7, oy + 0, 4, 2, color)
        fillRect(ox + 8, oy + 2, 3, 3, color)
    }

    private companion object {
        private val DEFAULT_COLS = Config.DEFAULT_COMPUTER_TERM_WIDTH
        private val DEFAULT_ROWS = Config.DEFAULT_COMPUTER_TERM_HEIGHT
        private const val COMPUTER_CONTENT_TOP = 8
        private const val STATUS_BUTTON_SIZE = 14
        private const val STATUS_BUTTON_GAP = 6
        private const val STATUS_BUTTON_MARGIN_END = 10
        private const val RESOLUTION_BUTTON_GAP = 8
        private const val STATUS_TEXT_START_PADDING = 12

        private val BACKGROUND = Color.hex(0xFF12151DU)
        private val STATUS_TEXT_COLOR = Color.hex(0xFF9CA8B8U)
        private val BUTTON_BG = Color.hex(0xFF1B202AU)
        private val BUTTON_BG_HOVER = Color.hex(0xFF222938U)
        private val BUTTON_BORDER = Color.hex(0xFF2C3444U)
        private val BUTTON_ICON = Color.hex(0xFFE6ECF5U)
        private val POWER_ACCENT = Color.hex(0xFF4FA56CU)
        private val REBOOT_ACCENT = Color.hex(0xFFC9894FU)
    }
}
