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

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffer
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayAttachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayDetachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayResizeServerMessage
import ru.lazyhat.compukterkraft.common.localization.CompukterKeys
import ru.lazyhat.compukterkraft.common.localization.CompukterTranslatable
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.dsl.translatable
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.gui.TerminalFontConstants
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color
import ru.lazyhat.compukterkraft.core.ui.foundation.HoverState
import ru.lazyhat.compukterkraft.core.ui.foundation.UiElement
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.Modifier
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.background
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.focusable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.hoverable
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.offset
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.size
import ru.lazyhat.compukterkraft.core.ui.foundation.modifier.tooltip
import ru.lazyhat.compukterkraft.core.ui.foundation.ui
import ru.lazyhat.compukterkraft.core.ui.foundation.value

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
open class ComputerTerminalScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : DslContainerScreen<T>(container, player, title) {
    protected val inputHandler = ClientInputHandler(container)
    protected val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)
    private val displayId: Int = SHARED_TERMINAL_DISPLAY_ID
    private val displayTexture = DisplayTextureCache(displayId)

    private val powerHover = HoverState()
    private val rebootHover = HoverState()

    init {
        val cols = DEFAULT_COLS
        val rows = DEFAULT_ROWS
        imageWidth = WorkbenchTerminalMetrics.imageWidth(cols)
        imageHeight = WorkbenchTerminalMetrics.imageHeight(rows, contentTopInset = COMPUTER_CONTENT_TOP)
    }

    override fun removed() {
        displayTexture.close()
        ClientNetworking.sendToServer(DisplayDetachServerMessage(menu, displayId))
        super.removed()
    }

    override fun renderBg(
        guiGraphics: GuiGraphics,
        partialTick: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        super.renderBg(guiGraphics, partialTick, mouseX, mouseY)
        drawDisplayTexture(guiGraphics)
    }

    override fun containerTick() {
        super.containerTick()
        menu.clientSide.displayBuffer?.swapIfDirty()
        syncDisplayEndpoint()
        // The display surface only enters the tree once the computer
        // reaches the Active state; focus it as soon as it appears so the
        // player never has to click to start typing.
        focusFirstNodeIfUnfocused()
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val handled = super.mouseClicked(x, y, button)
        // The portable terminal screen has exactly one sensible keyboard
        // target — the terminal surface itself — and we don't want the
        // player to have to click on it before typing. Re-acquire focus
        // after every click so power/reboot button clicks (or clicks on
        // empty chrome) don't strand the terminal without focus.
        focusFirstNodeIfUnfocused()
        return handled
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = isInventoryKey(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers)

    override fun init() {
        super.init()
        attachDisplayEndpoint()
        // After the executor has been built for the first time, plant
        // focus on the terminal surface so the user can type immediately.
        focusFirstNodeIfUnfocused()
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
                            menu.clientSide.displayBuffer?.hasReceivedFrames == true -> CompukterKeys.Gui.Terminal.FOCUSED
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
                            val buffer = menu.clientSide.displayBuffer
                            buffer?.let { displayResolutionText(it.width, it.height) } ?: ""
                        },
                )

                canvas(
                    modifier =
                        Modifier
                            .offset(terminalRelX, terminalRelY)
                            .size(layout.terminalBounds.width, layout.terminalBounds.height)
                            .focusable(
                                id = "computer-display",
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

    private fun attachDisplayEndpoint() {
        val displayWidth = currentDisplayWidth()
        val displayHeight = currentDisplayHeight()
        menu.clientSide.attachDisplayBuffer(ClientDisplayBuffer(displayId, displayWidth, displayHeight))
        ClientNetworking.sendToServer(DisplayAttachServerMessage(menu, displayId, displayWidth, displayHeight))
    }

    private fun syncDisplayEndpoint() {
        val displayWidth = currentDisplayWidth()
        val displayHeight = currentDisplayHeight()
        val buffer = menu.clientSide.displayBuffer
        if (buffer == null || buffer.width != displayWidth || buffer.height != displayHeight) {
            menu.clientSide.attachDisplayBuffer(ClientDisplayBuffer(displayId, displayWidth, displayHeight))
            ClientNetworking.sendToServer(DisplayResizeServerMessage(menu, displayId, displayWidth, displayHeight))
        }
    }

    protected fun CanvasScope.drawDisplayPlaceholder(
        targetWidth: Int,
        targetHeight: Int,
    ) {
        val buffer = menu.clientSide.displayBuffer
        if (buffer == null || !buffer.hasReceivedFrames) {
            fillRect(0, 0, targetWidth, targetHeight, DISPLAY_PLACEHOLDER)
        }
    }

    private fun drawDisplayTexture(guiGraphics: GuiGraphics) {
        if (!menu.isComputerOn) return
        val buffer = menu.clientSide.displayBuffer ?: return
        if (!buffer.hasReceivedFrames) return
        displayTexture.draw(guiGraphics, buffer, currentLayout().terminalBounds)
    }

    protected open fun currentLayout() =
        WorkbenchTerminalMetrics.layout(
            leftPos = leftPos,
            topPos = topPos,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            terminalColumns = DEFAULT_COLS,
            terminalRows = DEFAULT_ROWS,
            contentTopInset = COMPUTER_CONTENT_TOP,
        )

    private fun currentDisplayWidth(): Int = (DEFAULT_COLS * TerminalFontConstants.FONT_WIDTH).coerceAtLeast(64)

    private fun currentDisplayHeight(): Int = (DEFAULT_ROWS * TerminalFontConstants.FONT_HEIGHT).coerceAtLeast(48)

    private fun displayResolutionText(
        width: Int,
        height: Int,
    ): String = "$width x $height"

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
        private const val SHARED_TERMINAL_DISPLAY_ID = 1
        private val DEFAULT_COLS = Config.DEFAULT_COMPUTER_TERM_WIDTH
        private val DEFAULT_ROWS = Config.DEFAULT_COMPUTER_TERM_HEIGHT
        private const val COMPUTER_CONTENT_TOP = 8
        private const val STATUS_BUTTON_SIZE = 14
        private const val STATUS_BUTTON_GAP = 6
        private const val STATUS_BUTTON_MARGIN_END = 10
        private const val RESOLUTION_BUTTON_GAP = 8
        private const val STATUS_TEXT_START_PADDING = 12

        private val BACKGROUND = Color.hex(0xFF12151DU)
        private val DISPLAY_PLACEHOLDER = Color.hex(0xFF05070AU)
        private val STATUS_TEXT_COLOR = Color.hex(0xFF9CA8B8U)
        private val BUTTON_BG = Color.hex(0xFF1B202AU)
        private val BUTTON_BG_HOVER = Color.hex(0xFF222938U)
        private val BUTTON_BORDER = Color.hex(0xFF2C3444U)
        private val BUTTON_ICON = Color.hex(0xFFE6ECF5U)
        private val POWER_ACCENT = Color.hex(0xFF4FA56CU)
        private val REBOOT_ACCENT = Color.hex(0xFFC9894FU)
    }

    private class DisplayTextureCache(
        private val displayId: Int,
    ) : AutoCloseable {
        private var image: NativeImage? = null
        private var texture: DynamicTexture? = null
        private var location: ResourceLocation? = null
        private var width: Int = 0
        private var height: Int = 0
        private var uploadedVersion: Long = Long.MIN_VALUE

        fun draw(
            guiGraphics: GuiGraphics,
            buffer: ClientDisplayBuffer,
            bounds: TerminalRect,
        ) {
            ensureTexture(buffer.width, buffer.height)
            uploadIfNeeded(buffer)
            val textureLocation = location ?: return
            guiGraphics.blit(
                textureLocation,
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                0f,
                0f,
                buffer.width,
                buffer.height,
                buffer.width,
                buffer.height,
            )
        }

        private fun ensureTexture(
            width: Int,
            height: Int,
        ) {
            if (image != null && this.width == width && this.height == height) return
            close()
            this.width = width
            this.height = height
            val newImage = NativeImage(width, height, false)
            val newTexture = DynamicTexture(newImage)
            image = newImage
            texture = newTexture
            location = Minecraft.getInstance().textureManager.register("compukterkraft_display_$displayId", newTexture)
            uploadedVersion = Long.MIN_VALUE
        }

        private fun uploadIfNeeded(buffer: ClientDisplayBuffer) {
            val currentImage = image ?: return
            val currentTexture = texture ?: return
            if (buffer.frontVersion == uploadedVersion) return
            val snapshot = buffer.copyFrontSnapshotSince(uploadedVersion)
            if (snapshot.version == uploadedVersion) return
            for (region in snapshot.regions) {
                var row = region.y
                while (row < region.y + region.height) {
                    var columnOffset = 0
                    while (columnOffset < region.width) {
                        currentImage.setPixelRGBA(
                            region.x + columnOffset,
                            row,
                            FastColor.ABGR32.fromArgb32(
                                snapshot.pixels[row * buffer.width + region.x + columnOffset],
                            ),
                        )
                        columnOffset = columnOffset + 1
                    }
                    row = row + 1
                }
            }
            currentTexture.bind()
            for (region in snapshot.regions) {
                currentImage.upload(0, region.x, region.y, region.x, region.y, region.width, region.height, false, false)
            }
            uploadedVersion = snapshot.version
        }

        override fun close() {
            location?.let { Minecraft.getInstance().textureManager.release(it) }
            image = null
            texture = null
            location = null
            width = 0
            height = 0
            uploadedVersion = Long.MIN_VALUE
        }
    }
}
