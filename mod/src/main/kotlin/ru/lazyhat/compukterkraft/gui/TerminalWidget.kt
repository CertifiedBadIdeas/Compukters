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
package ru.lazyhat.compukterkraft.gui

import com.mojang.blaze3d.vertex.Tesselator
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukterkraft.gui.ComputerBorderRenderer.MARGIN
import ru.lazyhat.compukterkraft.gui.FixedWidthFontRenderer.FONT_HEIGHT
import ru.lazyhat.compukterkraft.gui.FixedWidthFontRenderer.FONT_WIDTH
import ru.lazyhat.compukterkraft.utils.StringUtil
import java.util.BitSet
import kotlin.math.max

/**
 * A widget which renders a computer terminal and handles input events (keyboard, mouse, clipboard) and computer
 * shortcuts (terminate/shutdown/reboot).
 *
 * @see dan200.computercraft.client.gui.ClientInputHandler The input handler typically used with this class.
 */
class TerminalWidget(
    private val terminal: Terminal,
    private val computer: InputHandler,
    x: Int,
    y: Int,
) : AbstractWidget(x, y, terminal.width * FONT_WIDTH + MARGIN * 2, terminal.height * FONT_HEIGHT + MARGIN * 2, DESCRIPTION) {
    // The positions of the actual terminal
    private val innerX: Int = x + MARGIN
    private val innerY: Int = y + MARGIN
    private val innerWidth: Int = terminal.width * FONT_WIDTH
    private val innerHeight: Int = terminal.width * FONT_HEIGHT

    private var terminateTimer = -1f
    private var rebootTimer = -1f
    private var shutdownTimer = -1f

    private var lastMouseButton = -1
    private var lastMouseX = -1
    private var lastMouseY = -1

    private val keysDown = BitSet(256)

    override fun charTyped(
        ch: Char,
        modifiers: Int,
    ): Boolean {
        val terminalChar = StringUtil.unicodeToTerminal(ch.code)
        if (StringUtil.isTypableChar(terminalChar)) computer.charTyped(terminalChar.toByte())
        return true
    }

    override fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (key == GLFW.GLFW_KEY_ESCAPE) return false
        if (Screen.isPaste(key)) {
            paste()
            return true
        }

        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            when (KeyConverter.physicalToActual(key, scancode)) {
                GLFW.GLFW_KEY_T -> {
                    if (terminateTimer < 0) terminateTimer = 0f
                }

                GLFW.GLFW_KEY_S -> {
                    if (shutdownTimer < 0) shutdownTimer = 0f
                }

                GLFW.GLFW_KEY_R -> {
                    if (rebootTimer < 0) rebootTimer = 0f
                }
            }
        }

        if (key >= 0 && terminateTimer < KEY_SUPPRESS_DELAY && rebootTimer < KEY_SUPPRESS_DELAY && shutdownTimer < KEY_SUPPRESS_DELAY) {
            // Queue the "key" event and add to the down set
            val repeat = keysDown.get(key)
            keysDown.set(key)
            computer.keyDown(key, repeat)
        }

        return true
    }

    private fun paste() {
        val clipboard = StringUtil.getClipboardString(Minecraft.getInstance().keyboardHandler.clipboard)
        if (clipboard.remaining() > 0) computer.paste(clipboard)
    }

    override fun keyReleased(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        // Queue the "key_up" event and remove from the down set
        if (key >= 0 && keysDown.get(key)) {
            keysDown.set(key, false)
            computer.keyUp(key)
        }

        when (KeyConverter.physicalToActual(key, scancode)) {
            GLFW.GLFW_KEY_T -> {
                terminateTimer = -1f
            }

            GLFW.GLFW_KEY_R -> {
                rebootTimer = -1f
            }

            GLFW.GLFW_KEY_S -> {
                shutdownTimer = -1f
            }

            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> {
                shutdownTimer = -1f
                rebootTimer = shutdownTimer
                terminateTimer = rebootTimer
            }
        }

        return true
    }

    override fun mouseClicked(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (!inTermRegion(mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        var charX = ((mouseX - innerX) / FONT_WIDTH).toInt()
        var charY = ((mouseY - innerY) / FONT_HEIGHT).toInt()
        charX = max(charX, 0).coerceAtMost(terminal.width - 1)
        charY = max(charY, 0).coerceAtMost(terminal.height - 1)

        computer.mouseClick(button + 1, charX + 1, charY + 1)

        lastMouseButton = button
        lastMouseX = charX
        lastMouseY = charY

        return true
    }

    override fun mouseReleased(
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (!inTermRegion(mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        var charX = ((mouseX - innerX) / FONT_WIDTH).toInt()
        var charY = ((mouseY - innerY) / FONT_HEIGHT).toInt()
        charX = max(charX, 0).coerceAtMost(terminal.width - 1)
        charY = max(charY, 0).coerceAtMost(terminal.height - 1)

        if (lastMouseButton == button) {
            computer.mouseUp(lastMouseButton + 1, charX + 1, charY + 1)
            lastMouseButton = -1
        }

        lastMouseX = charX
        lastMouseY = charY

        return true
    }

    override fun mouseDragged(
        mouseX: Double,
        mouseY: Double,
        button: Int,
        v2: Double,
        v3: Double,
    ): Boolean {
        if (!inTermRegion(mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        var charX = ((mouseX - innerX) / FONT_WIDTH).toInt()
        var charY = ((mouseY - innerY) / FONT_HEIGHT).toInt()
        charX = max(charX, 0).coerceAtMost(terminal.width - 1)
        charY = max(charY, 0).coerceAtMost(terminal.height - 1)

        if (button == lastMouseButton && (charX != lastMouseX || charY != lastMouseY)) {
            computer.mouseDrag(button + 1, charX + 1, charY + 1)
            lastMouseX = charX
            lastMouseY = charY
        }

        return true
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        delta: Double,
    ): Boolean {
        if (!inTermRegion(mouseX, mouseY)) return false
        if (!hasMouseSupport() || delta == 0.0) return false

        var charX = ((mouseX - innerX) / FONT_WIDTH).toInt()
        var charY = ((mouseY - innerY) / FONT_HEIGHT).toInt()
        charX = max(charX, 0).coerceAtMost(terminal.width - 1)
        charY = max(charY, 0).coerceAtMost(terminal.height - 1)

        computer.mouseScroll(if (delta < 0) 1 else -1, charX + 1, charY + 1)

        lastMouseX = charX
        lastMouseY = charY

        return true
    }

    private fun inTermRegion(
        mouseX: Double,
        mouseY: Double,
    ): Boolean = active && visible && mouseX >= innerX && mouseY >= innerY && mouseX < innerX + innerWidth && mouseY < innerY + innerHeight

    private fun hasMouseSupport(): Boolean = terminal.isColour

    fun update() {
        if (terminateTimer in 0f..<TERMINATE_TIME && (
                0.05f.let {
                    terminateTimer += it
                    terminateTimer
                }
            ) > TERMINATE_TIME
        ) {
            computer.terminate()
        }

        if (shutdownTimer in 0f..<TERMINATE_TIME && (
                0.05f.let {
                    shutdownTimer += it
                    shutdownTimer
                }
            ) > TERMINATE_TIME
        ) {
            computer.shutdown()
        }

        if (rebootTimer in 0f..<TERMINATE_TIME && (
                0.05f.let {
                    rebootTimer += it
                    rebootTimer
                }
            ) > TERMINATE_TIME
        ) {
            computer.reboot()
        }
    }

    override fun setFocused(focused: Boolean) {
        super.setFocused(focused)

        if (!focused) {
            // When blurring, we should make all keys go up
            for (key in 0..<keysDown.size()) {
                if (keysDown.get(key)) computer.keyUp(key)
            }
            keysDown.clear()

            // When blurring, we should make the last mouse button go up
            if (lastMouseButton >= 0) {
                computer.mouseUp(lastMouseButton + 1, lastMouseX + 1, lastMouseY + 1)
                lastMouseButton = -1
            }

            rebootTimer = -1f
            terminateTimer = rebootTimer
            shutdownTimer = terminateTimer
        }
    }

    public override fun renderWidget(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
    ) {
        if (!visible) return

        val bufferSource: MultiBufferSource.BufferSource = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder())
        val emitter =
            FixedWidthFontRenderer.toVertexConsumer(
                graphics.pose(),
                bufferSource.getBuffer(RenderTypes.TERMINAL),
            )

        FixedWidthFontRenderer.drawTerminal(
            emitter,
            innerX.toFloat(),
            innerY.toFloat(),
            terminal,
            MARGIN.toFloat(),
            MARGIN.toFloat(),
            MARGIN.toFloat(),
            MARGIN.toFloat(),
        )

        bufferSource.endBatch()
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, message)
    }

    companion object {
        private val DESCRIPTION: Component = Component.translatable("gui.compukterkraft.terminal")

        private const val TERMINATE_TIME = 0.5f
        private const val KEY_SUPPRESS_DELAY = 0.2f

        fun getWidth(termWidth: Int): Int = termWidth * FONT_WIDTH + MARGIN * 2

        fun getHeight(termHeight: Int): Int = termHeight * FONT_HEIGHT + MARGIN * 2
    }
}
