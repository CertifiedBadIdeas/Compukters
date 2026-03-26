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
package ck.mod.gui

import ck.mod.gui.FixedWidthFontRenderer.FONT_HEIGHT
import ck.mod.gui.FixedWidthFontRenderer.FONT_WIDTH
import ck.mod.utils.StringUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import java.util.BitSet
import kotlin.math.max

data class TerminalBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

class TerminalInputController(
    private val terminal: Terminal,
    private val computer: InputHandler,
) {
    private var terminateTimer = -1f
    private var rebootTimer = -1f
    private var shutdownTimer = -1f

    private var lastMouseButton = -1
    private var lastMouseX = -1
    private var lastMouseY = -1

    private val keysDown = BitSet(256)

    var focused: Boolean = false
        set(value) {
            field = value
            if (!value) {
                releaseState()
            }
        }

    fun charTyped(ch: Char): Boolean {
        val terminalChar = StringUtil.unicodeToTerminal(ch.code)
        if (StringUtil.isTypableChar(terminalChar)) {
            computer.charTyped(terminalChar.toByte())
        }
        return true
    }

    fun keyPressed(
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
                GLFW.GLFW_KEY_T -> if (terminateTimer < 0) terminateTimer = 0f
                GLFW.GLFW_KEY_S -> if (shutdownTimer < 0) shutdownTimer = 0f
                GLFW.GLFW_KEY_R -> if (rebootTimer < 0) rebootTimer = 0f
            }
        }

        if (key >= 0 && terminateTimer < KEY_SUPPRESS_DELAY && rebootTimer < KEY_SUPPRESS_DELAY && shutdownTimer < KEY_SUPPRESS_DELAY) {
            val repeat = keysDown.get(key)
            keysDown.set(key)
            computer.keyDown(key, repeat)
        }

        return true
    }

    fun keyReleased(
        key: Int,
        scancode: Int,
    ): Boolean {
        if (key >= 0 && keysDown.get(key)) {
            keysDown.set(key, false)
            computer.keyUp(key)
        }

        when (KeyConverter.physicalToActual(key, scancode)) {
            GLFW.GLFW_KEY_T -> terminateTimer = -1f
            GLFW.GLFW_KEY_R -> rebootTimer = -1f
            GLFW.GLFW_KEY_S -> shutdownTimer = -1f
            GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> {
                shutdownTimer = -1f
                rebootTimer = shutdownTimer
                terminateTimer = rebootTimer
            }
        }

        return true
    }

    fun mouseClicked(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (!inTermRegion(bounds, mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        val (charX, charY) = terminalPosition(bounds, mouseX, mouseY)
        computer.mouseClick(button + 1, charX + 1, charY + 1)

        lastMouseButton = button
        lastMouseX = charX
        lastMouseY = charY
        focused = true
        return true
    }

    fun mouseReleased(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (!inTermRegion(bounds, mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        val (charX, charY) = terminalPosition(bounds, mouseX, mouseY)
        if (lastMouseButton == button) {
            computer.mouseUp(lastMouseButton + 1, charX + 1, charY + 1)
            lastMouseButton = -1
        }
        lastMouseX = charX
        lastMouseY = charY
        return true
    }

    fun mouseDragged(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
        button: Int,
    ): Boolean {
        if (!inTermRegion(bounds, mouseX, mouseY)) return false
        if (!hasMouseSupport() || button < 0 || button > 2) return false

        val (charX, charY) = terminalPosition(bounds, mouseX, mouseY)
        if (button == lastMouseButton && (charX != lastMouseX || charY != lastMouseY)) {
            computer.mouseDrag(button + 1, charX + 1, charY + 1)
            lastMouseX = charX
            lastMouseY = charY
        }
        return true
    }

    fun mouseScrolled(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
        delta: Double,
    ): Boolean {
        if (!inTermRegion(bounds, mouseX, mouseY)) return false
        if (!hasMouseSupport() || delta == 0.0) return false

        val (charX, charY) = terminalPosition(bounds, mouseX, mouseY)
        computer.mouseScroll(if (delta < 0) 1 else -1, charX + 1, charY + 1)

        lastMouseX = charX
        lastMouseY = charY
        return true
    }

    fun update() {
        if (terminateTimer in 0f..<TERMINATE_TIME && (terminateTimer + 0.05f).also { terminateTimer = it } > TERMINATE_TIME) {
            computer.terminate()
        }
        if (shutdownTimer in 0f..<TERMINATE_TIME && (shutdownTimer + 0.05f).also { shutdownTimer = it } > TERMINATE_TIME) {
            computer.shutdown()
        }
        if (rebootTimer in 0f..<TERMINATE_TIME && (rebootTimer + 0.05f).also { rebootTimer = it } > TERMINATE_TIME) {
            computer.reboot()
        }
    }

    private fun paste() {
        val clipboard = StringUtil.getClipboardString(Minecraft.getInstance().keyboardHandler.clipboard)
        if (clipboard.remaining() > 0) {
            computer.paste(clipboard)
        }
    }

    private fun terminalPosition(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
    ): Pair<Int, Int> {
        var charX = ((mouseX - bounds.x) / FONT_WIDTH).toInt()
        var charY = ((mouseY - bounds.y) / FONT_HEIGHT).toInt()
        charX = max(charX, 0).coerceAtMost(terminal.width - 1)
        charY = max(charY, 0).coerceAtMost(terminal.height - 1)
        return charX to charY
    }

    private fun inTermRegion(
        bounds: TerminalBounds,
        mouseX: Double,
        mouseY: Double,
    ): Boolean = mouseX >= bounds.x &&
        mouseY >= bounds.y &&
        mouseX < bounds.x + bounds.width &&
        mouseY < bounds.y + bounds.height

    private fun hasMouseSupport(): Boolean = terminal.isColour

    private fun releaseState() {
        for (key in 0..<keysDown.size()) {
            if (keysDown.get(key)) {
                computer.keyUp(key)
            }
        }
        keysDown.clear()

        if (lastMouseButton >= 0) {
            computer.mouseUp(lastMouseButton + 1, lastMouseX + 1, lastMouseY + 1)
            lastMouseButton = -1
        }

        rebootTimer = -1f
        terminateTimer = rebootTimer
        shutdownTimer = terminateTimer
    }

    companion object {
        private const val TERMINATE_TIME = 0.5f
        private const val KEY_SUPPRESS_DELAY = 0.2f
    }
}
