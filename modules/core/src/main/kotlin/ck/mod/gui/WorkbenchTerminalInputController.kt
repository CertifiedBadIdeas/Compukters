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

import ck.mod.application.input.InputEventSink
import ck.mod.application.input.KeyInputEvent
import ck.mod.application.input.MouseInputEvent
import ck.mod.application.input.PasteInputEvent
import ck.mod.input.KeyCodes
import ck.mod.platform.api.PlatformInputProvider
import ck.mod.utils.StringUtil
import java.util.BitSet

class WorkbenchTerminalInputController(
    private val computer: InputEventSink,
    private val inputProvider: PlatformInputProvider,
) {
    private val keysDown = BitSet(256)

    var focused: Boolean = false
        set(value) {
            field = value
            if (!value) {
                releaseState()
            }
        }

    fun update() = Unit

    fun charTyped(ch: Char): Boolean {
        if (!focused) return false

        val terminalChar = StringUtil.unicodeToTerminal(ch.code)
        if (StringUtil.isTypableChar(terminalChar)) {
            computer.accept(KeyInputEvent.Character(terminalChar.toByte()))
        }
        return true
    }

    fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (!focused || key == KeyCodes.KEY_ESCAPE) return false
        if (inputProvider.isPasteShortcut(key)) {
            paste()
            return true
        }

        if (key >= 0) {
            val actualKey = KeyConverter.physicalToActual(key, scancode, inputProvider)
            val repeat = keysDown.get(actualKey)
            keysDown.set(actualKey)
            computer.accept(KeyInputEvent.Down(actualKey, repeat))
        }
        return true
    }

    fun keyReleased(
        key: Int,
        scancode: Int,
    ): Boolean {
        if (!focused) return false

        if (key >= 0) {
            val actualKey = KeyConverter.physicalToActual(key, scancode, inputProvider)
            if (keysDown.get(actualKey)) {
                keysDown.set(actualKey, false)
                computer.accept(KeyInputEvent.Up(actualKey))
            }
        }
        return true
    }

    fun mouseClicked(
        bounds: TerminalRect,
        mouseX: Double,
        mouseY: Double,
    ): Boolean {
        focused = bounds.contains(mouseX.toInt(), mouseY.toInt())
        return focused
    }

    fun mouseScrolled(
        bounds: TerminalRect,
        mouseX: Double,
        mouseY: Double,
        delta: Double,
    ): Boolean {
        if (!focused || !bounds.contains(mouseX.toInt(), mouseY.toInt())) return false
        val cellX = ((mouseX.toInt() - bounds.x) / TerminalFontConstants.FONT_WIDTH) + 1
        val cellY = ((mouseY.toInt() - bounds.y) / TerminalFontConstants.FONT_HEIGHT) + 1
        val direction = if (delta > 0) 1 else -1
        computer.accept(MouseInputEvent.Scroll(direction, cellX, cellY))
        return true
    }

    private fun paste() {
        val clipboard = StringUtil.getClipboardString(inputProvider.getClipboardString())
        if (clipboard.remaining() > 0) {
            computer.accept(PasteInputEvent(clipboard))
        }
    }

    private fun releaseState() {
        for (key in 0..<keysDown.size()) {
            if (keysDown.get(key)) {
                computer.accept(KeyInputEvent.Up(key))
            }
        }
        keysDown.clear()
    }
}
