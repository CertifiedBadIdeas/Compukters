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
    private val source =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt")
            .readText()

    @Test
    fun computerScreenUsesDisplayBufferNotTerminalBuffer() {
        assertFalse(source.contains("ClientTerminalBuffer"))
        assertFalse(source.contains("AttachTerminalServerMessage"))
        assertFalse(source.contains("ResizeTerminalServerMessage"))
        assertFalse(source.contains("terminalSurface("))
        assertTrue(source.contains("ClientDisplayBuffer"))
        assertTrue(source.contains("DisplayAttachServerMessage"))
        assertTrue(source.contains("DisplayResizeServerMessage"))
    }

    @Test
    fun computerScreenRendersDisplayAsTextureNotPerPixelGuiRects() {
        assertTrue(source.contains("NativeImage"))
        assertTrue(source.contains("DynamicTexture"))
        assertTrue(source.contains("drawDisplayTexture"))
        assertFalse(source.contains("frontArgb()"))
        assertFalse(source.contains("while (x < buffer.width)"))
        assertFalse(source.contains("fillRect(px, py, pw, ph, color)"))
    }
}
