/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.core.gui

import ru.lazyhat.compukters.core.device.input.InputEventSink
import ru.lazyhat.compukters.core.device.input.KeyInputEvent
import ru.lazyhat.compukters.core.device.input.PasteInputEvent
import ru.lazyhat.compukters.core.input.KeyCodes
import ru.lazyhat.compukters.core.platform.api.PlatformInputProvider
import ru.lazyhat.compukters.core.utils.StringUtil
import java.util.BitSet

class WorkbenchTerminalInputController(
    private val inputSink: InputEventSink,
    private val inputProvider: PlatformInputProvider,
) {
    private val keysDown = BitSet(256)

    fun charTyped(ch: Char): Boolean {
        val terminalChar = StringUtil.unicodeToTerminal(ch.code)
        if (StringUtil.isTypableChar(terminalChar)) {
            inputSink.accept(KeyInputEvent.Character(terminalChar.toByte()))
        }
        return true
    }

    fun keyPressed(
        key: Int,
        scancode: Int,
        modifiers: Int,
    ): Boolean {
        if (key == KeyCodes.KEY_ESCAPE) return false
        if (inputProvider.isPasteShortcut(key)) {
            paste()
            return true
        }

        if (key >= 0) {
            val actualKey = KeyConverter.physicalToActual(key, scancode, inputProvider)
            val repeat = keysDown.get(actualKey)
            keysDown.set(actualKey)
            inputSink.accept(KeyInputEvent.Down(actualKey, repeat))
            if (actualKey == KeyCodes.KEY_ENTER || actualKey == KeyCodes.KEY_KP_ENTER) {
                inputSink.accept(KeyInputEvent.Character('\n'.code.toByte()))
            }
        }
        return true
    }

    fun keyReleased(
        key: Int,
        scancode: Int,
    ): Boolean {
        if (key >= 0) {
            val actualKey = KeyConverter.physicalToActual(key, scancode, inputProvider)
            if (keysDown.get(actualKey)) {
                keysDown.set(actualKey, false)
                inputSink.accept(KeyInputEvent.Up(actualKey))
            }
        }
        return true
    }

    private fun paste() {
        val clipboard = StringUtil.getClipboardString(inputProvider.getClipboardString())
        if (clipboard.remaining() > 0) {
            inputSink.accept(PasteInputEvent(clipboard))
        }
    }
}
