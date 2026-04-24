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
package ru.lazyhat.compukterkraft.common.computer.screen

import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.dsl.translatable
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.core.computer.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.computer.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.HoverState
import ru.lazyhat.compukterkraft.core.ui.foundation.IntSize
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.hoverable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.tooltip
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * Terminal screen authored with the UI DSL. Mirrors the historical
 * hand-rolled `renderBg`/`render` flow in terms of visual and input
 * behaviour, but expresses it declaratively:
 *
 *  - Dynamic text, visibility branches (powered-off / connecting /
 *    active) and snapshot contents flow through `ValueExpression`s —
 *    no recompile is needed per frame.
 *  - The snapshot's grid dimensions still drive `imageWidth`/
 *    `imageHeight`; when they change, [DslContainerScreen] auto-
 *    recompiles the layout (see Epic 3).
 *  - Power/reboot buttons are `canvas` draw lambdas fed by
 *    `HoverState` flags so hover chrome and icon color are resolved
 *    inline, without creating a new draw list on hover.
 *  - Tooltip text is routed through the new `Modifier.tooltip` hook
 *    which the runtime forwards to Minecraft's tooltip pipeline.
 */
class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : DslContainerScreen<T>(container, player, title) {
    private val inputHandler = ClientInputHandler(container)
    private val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)

    private val powerHover = HoverState()
    private val rebootHover = HoverState()

    // Tracks the snapshot's pixel dimensions (not the mod's `imageWidth`
    // which is clamped to MIN_IMAGE_WIDTH). A change here means the
    // baked terminal-surface bounds in the program are now stale even if
    // `imageWidth`/`imageHeight` did not move, so we explicitly
    // invalidate the cached executor.
    private var lastTerminalDimensions = IntSize.Zero

    init {
        val (cols, rows) = terminalDimensions(container.clientSide.screenSnapshot)
        imageWidth = WorkbenchTerminalMetrics.imageWidth(cols)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(rows, contentTopInset = COMPUTER_CONTENT_TOP)
    }

    override fun containerTick() {
        super.containerTick()
        val state = currentTerminalState()
        if (state !is WorkbenchTerminalViewState.Active && terminalInput.focused) {
            terminalInput.focused = false
        }
        syncTerminalWindowSize(state)
        terminalDimensions(menu.clientSide.screenSnapshot).also { dims ->
            if (dims != lastTerminalDimensions) {
                lastTerminalDimensions = dims
                invalidate()
            }
        }
        terminalInput.update()
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val handled = super.mouseClicked(x, y, button)
        // The DSL owns focus acquisition (clicks inside the terminal
        // surface acquire, clicks outside clear). We mirror that flag
        // into the WorkbenchTerminalInputController so its internal
        // `keysDown` bookkeeping stays consistent and so a click
        // outside the terminal releases any held keys.
        terminalInput.focused = isDslFocused
        return handled
    }

    override fun content(): UiElement {
        val (cols, rows) = terminalDimensions(menu.clientSide.screenSnapshot)
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

        val statusRelX = layout.statusBounds.x - leftPos
        val statusRelY = layout.statusBounds.y - topPos
        val terminalRelX = layout.terminalBounds.x - leftPos
        val terminalRelY = layout.terminalBounds.y - topPos
        val surfaceRelX = layout.terminalSurfaceBounds.x - leftPos
        val surfaceRelY = layout.terminalSurfaceBounds.y - topPos
        val powerRelX = powerBtn.x - leftPos
        val powerRelY = powerBtn.y - topPos
        val rebootRelX = rebootBtn.x - leftPos
        val rebootRelY = rebootBtn.y - topPos

        return ui(Modifier.size(imageWidth, imageHeight).background(BACKGROUND)) {
            text(
                modifier = Modifier.offset(statusRelX + 12, statusRelY + 6),
                color = STATUS_TEXT_COLOR,
                text =
                    value {
                        when (currentTerminalState()) {
                            is WorkbenchTerminalViewState.Active -> {
                                if (terminalInput.focused) {
                                    "Input active  |  Ctrl+V paste"
                                } else {
                                    "Click terminal to focus input"
                                }
                            }

                            WorkbenchTerminalViewState.PoweredOff -> {
                                Component.translatable("gui.compukterkraft.terminal.powered_off").string
                            }

                            WorkbenchTerminalViewState.Connecting -> {
                                Component.translatable("gui.compukterkraft.terminal.connecting").string
                            }
                        }
                    },
            )

            If(value { currentTerminalState() is WorkbenchTerminalViewState.Active }) {
                text(
                    modifier =
                        Modifier.offset(
                            statusRelX + layout.statusBounds.width - STATUS_TEXT_RIGHT_INSET,
                            statusRelY + 6,
                        ),
                    color = STATUS_TEXT_COLOR,
                    text =
                        value {
                            val active = currentTerminalState() as? WorkbenchTerminalViewState.Active
                            active?.let { "${it.snapshot.width} x ${it.snapshot.height}" } ?: ""
                        },
                )

                terminalSurface(
                    snapshot =
                        value {
                            (currentTerminalState() as? WorkbenchTerminalViewState.Active)?.snapshot!!
                        },
                    modifier =
                        Modifier
                            .offset(terminalRelX, terminalRelY)
                            .size(layout.terminalBounds.width, layout.terminalBounds.height),
                    onKey = { keyCode ->
                        terminalInput.focused = true
                        terminalInput.keyPressed(keyCode, 0, 0)
                    },
                    onKeyReleased = { keyCode ->
                        terminalInput.keyReleased(keyCode, 0)
                    },
                    onCharTyped = { ch ->
                        terminalInput.focused = true
                        terminalInput.charTyped(ch)
                    },
                )
            }

            If(value { currentTerminalState() !is WorkbenchTerminalViewState.Active }) {
                text(
                    modifier = Modifier.offset(surfaceRelX + 12, surfaceRelY + 12),
                    color = STATUS_TEXT_COLOR,
                    text =
                        translatable {
                            when (currentTerminalState()) {
                                WorkbenchTerminalViewState.PoweredOff -> "gui.compukterkraft.terminal.powered_off"
                                WorkbenchTerminalViewState.Connecting -> "gui.compukterkraft.terminal.connecting"
                                is WorkbenchTerminalViewState.Active -> ""
                            }
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
                                    "gui.compukterkraft.control.shutdown"
                                } else {
                                    "gui.compukterkraft.control.turn_on"
                                }
                            },
                        ),
                onClick = {
                    terminalInput.focused = false
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
                        .tooltip(translatable("gui.compukterkraft.control.reboot")),
                onClick = {
                    terminalInput.focused = false
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

    private fun currentTerminalState(): WorkbenchTerminalViewState =
        WorkbenchTerminalViewState.from(menu.isComputerOn, menu.clientSide.screenSnapshot)

    private fun syncTerminalWindowSize(state: WorkbenchTerminalViewState) {
        val size =
            when (state) {
                is WorkbenchTerminalViewState.Active -> terminalDimensions(state.snapshot)
                WorkbenchTerminalViewState.PoweredOff, WorkbenchTerminalViewState.Connecting -> IntSize.Zero
            }
        val nextWidth = WorkbenchTerminalMetrics.imageWidth(size.width)
        val nextHeight = WorkbenchTerminalMetrics.imageHeight(size.height, contentTopInset = COMPUTER_CONTENT_TOP)
        if (imageWidth != nextWidth || imageHeight != nextHeight) {
            imageWidth = nextWidth
            imageHeight = nextHeight
            leftPos = (width - imageWidth) / 2
            topPos = (height - imageHeight) / 2
            // DslContainerScreen detects bounds drift in renderBg and
            // recompiles the program; no explicit invalidate call needed.
        }
    }

    private fun terminalDimensions(snapshot: ScreenBufferSnapshot?): IntSize =
        snapshot?.run { IntSize(snapshot.width, snapshot.height) } ?: IntSize.Zero

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
        private const val COMPUTER_CONTENT_TOP = 8
        private const val STATUS_BUTTON_SIZE = 14
        private const val STATUS_BUTTON_GAP = 6
        private const val STATUS_BUTTON_MARGIN_END = 10
        private const val STATUS_TEXT_RIGHT_INSET = 52

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
