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

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputerTerminalScreenArchitectureTest {
    private val terminalSource =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt")
            .readText()
    private val displaySource =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerDisplayScreen.kt")
            .readText()

    @Test
    fun computerScreenUsesDisplayBufferNotTerminalBuffer() {
        assertFalse(terminalSource.contains("ClientTerminalBuffer"))
        assertFalse(displaySource.contains("ClientTerminalBuffer"))
        assertFalse(terminalSource.contains("AttachTerminalServerMessage"))
        assertFalse(displaySource.contains("AttachTerminalServerMessage"))
        assertFalse(terminalSource.contains("ResizeTerminalServerMessage"))
        assertFalse(displaySource.contains("ResizeTerminalServerMessage"))
        assertFalse(terminalSource.contains("terminalSurface("))
        assertFalse(displaySource.contains("terminalSurface("))
        assertTrue(displaySource.contains("ClientDisplayBuffer"))
        assertTrue(displaySource.contains("DisplayAttachServerMessage"))
        assertTrue(displaySource.contains("DisplayResizeServerMessage"))
    }

    @Test
    fun computerScreenRendersDisplayAsTextureNotPerPixelGuiRects() {
        assertTrue(displaySource.contains("NativeImage"))
        assertTrue(displaySource.contains("DynamicTexture"))
        assertTrue(displaySource.contains("drawDisplayTexture"))
        assertFalse(displaySource.contains("frontArgb()"))
        assertFalse(displaySource.contains("while (x < buffer.width)"))
        assertFalse(displaySource.contains("fillRect(px, py, pw, ph, color)"))
    }

    @Test
    fun computerScreenKeepsResolutionLabelBeforeControlButtons() {
        assertTrue(terminalSource.contains("resolutionRelX"))
        assertTrue(terminalSource.contains("rightBoundaryX = powerBtn.x"))
        assertTrue(terminalSource.contains("RESOLUTION_BUTTON_GAP"))
        assertFalse(terminalSource.contains("STATUS_TEXT_RIGHT_INSET"))
    }

    @Test
    fun computerScreenKeepsInventoryKeyAsTextInputInsteadOfClosingScreen() {
        assertTrue(displaySource.contains("keyInventory"))
        assertTrue(displaySource.contains(".matches(keyCode, scanCode)"))
    }
}
