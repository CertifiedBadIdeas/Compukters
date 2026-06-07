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

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FastColor
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffer
import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffers
import ru.lazyhat.compukterkraft.common.computer.input.ClientInputHandler
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayAttachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayDetachServerMessage
import ru.lazyhat.compukterkraft.common.computer.network.server.DisplayResizeServerMessage
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.platform.MinecraftInputProvider
import ru.lazyhat.compukterkraft.common.ui.program.DslContainerScreen
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.core.gui.TerminalRect
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalInputController
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.ui.foundation.CanvasScope
import ru.lazyhat.compukterkraft.core.ui.foundation.Color

abstract class ComputerDisplayScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : DslContainerScreen<T>(container, player, title) {
    protected val inputHandler = ClientInputHandler(container)
    protected val terminalInput = WorkbenchTerminalInputController(inputHandler, MinecraftInputProvider)

    protected abstract val displayId: Int
    protected abstract val terminalColumns: Int
    protected abstract val terminalRows: Int

    private val displayTexture: DisplayTextureCache by lazy { DisplayTextureCache(displayId) }
    private var lastMenuPowerState: Boolean? = null

    override fun init() {
        super.init()
        attachDisplayEndpoint()
        lastMenuPowerState = menu.isComputerOn
        focusFirstNodeIfUnfocused()
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
        syncDisplayPowerState()
        menu.clientSide.displayBuffer?.swapIfDirty()
        syncDisplayEndpoint()
        focusFirstNodeIfUnfocused()
    }

    override fun mouseClicked(
        x: Double,
        y: Double,
        button: Int,
    ): Boolean {
        val handled = super.mouseClicked(x, y, button)
        focusFirstNodeIfUnfocused()
        return handled
    }

    override fun keyPressed(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.keyPressed(keyCode, scanCode, modifiers)) return true
        return isInventoryKey(keyCode, scanCode) || super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun keyReleased(
        keyCode: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.keyReleased(keyCode, scanCode)) return true
        return super.keyReleased(keyCode, scanCode, modifiers)
    }

    override fun charTyped(
        codePoint: Char,
        modifiers: Int,
    ): Boolean {
        if (menu.isComputerOn && terminalInput.charTyped(codePoint)) return true
        return super.charTyped(codePoint, modifiers)
    }

    protected abstract fun currentLayout(): WorkbenchTerminalLayout

    protected fun CanvasScope.drawDisplayPlaceholder(
        targetWidth: Int,
        targetHeight: Int,
    ) {
        val buffer = menu.clientSide.displayBuffer
        if (buffer == null || !buffer.hasReceivedFrames) {
            fillRect(0, 0, targetWidth, targetHeight, DISPLAY_PLACEHOLDER)
        }
    }

    protected fun currentDisplayWidth(): Int = K16_GPU0_WIDTH

    protected fun currentDisplayHeight(): Int = K16_GPU0_HEIGHT

    protected fun displayResolutionText(
        width: Int,
        height: Int,
    ): String = "$width x $height"

    protected fun currentDisplayBounds(layout: WorkbenchTerminalLayout): TerminalRect {
        val surface = layout.terminalSurfaceBounds
        val width = currentDisplayWidth()
        val height = currentDisplayHeight()
        return TerminalRect(
            x = surface.x + (surface.width - width) / 2,
            y = surface.y + (surface.height - height) / 2,
            width = width,
            height = height,
        )
    }

    private fun attachDisplayEndpoint() {
        val displayWidth = currentDisplayWidth()
        val displayHeight = currentDisplayHeight()
        menu.clientSide.attachDisplayBuffer(displayBuffer(displayWidth, displayHeight))
        ClientNetworking.sendToServer(DisplayAttachServerMessage(menu, displayId, displayWidth, displayHeight))
    }

    private fun syncDisplayEndpoint() {
        val displayWidth = currentDisplayWidth()
        val displayHeight = currentDisplayHeight()
        val buffer = menu.clientSide.displayBuffer
        if (buffer == null || buffer.width != displayWidth || buffer.height != displayHeight) {
            menu.clientSide.attachDisplayBuffer(displayBuffer(displayWidth, displayHeight))
            ClientNetworking.sendToServer(DisplayResizeServerMessage(menu, displayId, displayWidth, displayHeight))
        }
    }

    private fun syncDisplayPowerState() {
        val currentPowerState = menu.isComputerOn
        val lastPowerState = lastMenuPowerState
        if (lastPowerState == true && !currentPowerState) {
            resetDisplayBuffer()
        }
        lastMenuPowerState = currentPowerState
    }

    protected fun resetDisplayBufferForRuntimeRestart() {
        resetDisplayBuffer()
    }

    private fun resetDisplayBuffer() {
        val displayWidth = currentDisplayWidth()
        val displayHeight = currentDisplayHeight()
        menu.displayStack.computerDataTagCopy()?.computerID?.let { computerId ->
            ClientDisplayBuffers.remove(computerId, displayId, displayWidth, displayHeight)
        }
        menu.clientSide.detachDisplayBuffer()
        menu.clientSide.attachDisplayBuffer(ClientDisplayBuffer(displayId, displayWidth, displayHeight))
    }

    private fun displayBuffer(
        displayWidth: Int,
        displayHeight: Int,
    ): ClientDisplayBuffer =
        menu.displayStack.computerDataTagCopy()?.computerID?.let { computerId ->
            ClientDisplayBuffers.getOrCreate(computerId, displayId, displayWidth, displayHeight)
        } ?: ClientDisplayBuffer(displayId, displayWidth, displayHeight)

    private fun drawDisplayTexture(guiGraphics: GuiGraphics) {
        if (!menu.isComputerOn) return
        val buffer = menu.clientSide.displayBuffer ?: return
        if (!buffer.hasReceivedFrames) return
        displayTexture.draw(guiGraphics, buffer, currentDisplayBounds(currentLayout()))
    }

    private fun isInventoryKey(
        keyCode: Int,
        scanCode: Int,
    ): Boolean = minecraft?.options?.keyInventory?.matches(keyCode, scanCode) == true

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

    private companion object {
        private const val K16_GPU0_WIDTH = 320
        private const val K16_GPU0_HEIGHT = 200
        private val DISPLAY_PLACEHOLDER = Color.hex(0xFF05070AU)
    }
}
