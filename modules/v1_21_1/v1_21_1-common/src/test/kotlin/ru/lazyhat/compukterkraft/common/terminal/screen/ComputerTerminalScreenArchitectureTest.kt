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
    private val notebookSource =
        Paths
            .get("src/main/kotlin/ru/lazyhat/compukterkraft/common/notebook/screen/NotebookScreen.kt")
            .readText()

    @Test
    fun computerScreenUsesOneConnectionScopedRetainedDisplayEntry() {
        assertFalse(terminalSource.contains("ClientTerminalBuffer"))
        assertFalse(displaySource.contains("ClientTerminalBuffer"))
        assertFalse(terminalSource.contains("AttachTerminalServerMessage"))
        assertFalse(displaySource.contains("AttachTerminalServerMessage"))
        assertFalse(terminalSource.contains("ResizeTerminalServerMessage"))
        assertFalse(displaySource.contains("ResizeTerminalServerMessage"))
        assertFalse(terminalSource.contains("terminalSurface("))
        assertFalse(displaySource.contains("terminalSurface("))
        assertTrue(displaySource.contains("ClientRetainedDisplays.attachMenu"))
        assertTrue(displaySource.contains("RetainedDisplayObserverHandle"))
        assertTrue(displaySource.contains("RetainedDisplayMenuRenderer"))
        assertFalse(displaySource.contains("ClientDisplayBuffer"))
        assertFalse(displaySource.contains("DisplayAttachServerMessage"))
        assertFalse(displaySource.contains("DisplayResizeServerMessage"))
    }

    @Test
    fun computerScreenSubmitsRetainedBatchesInsteadOfUploadingAFramebufferTexture() {
        assertFalse(displaySource.contains("NativeImage"))
        assertFalse(displaySource.contains("DynamicTexture"))
        assertTrue(displaySource.contains("drawRetainedDisplay"))
        assertTrue(displaySource.contains("retainedRenderer.draw"))
        assertFalse(displaySource.contains("frontArgb()"))
        assertFalse(displaySource.contains("while (x < buffer.width)"))
        assertFalse(displaySource.contains("fillRect(px, py, pw, ph, color)"))
    }

    @Test
    fun clientDisplayPathCompositesGenericOperationsWithoutOwningFonts() {
        assertFalse(displaySource.contains("FixedWidthFontRenderer"))
        assertFalse(displaySource.contains("TerminalFontConstants"))
        assertFalse(displaySource.contains("terminal_font"))
        assertFalse(displaySource.contains("drawString("))
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
        assertTrue(displaySource.contains("terminalInput.keyPressed(keyCode, scanCode, modifiers)"))
    }

    @Test
    fun computerScreenRoutesTypedInputWithoutDependingOnCanvasFocus() {
        assertTrue(displaySource.contains("override fun keyReleased("))
        assertTrue(displaySource.contains("terminalInput.keyReleased(keyCode, scanCode)"))
        assertTrue(displaySource.contains("override fun charTyped("))
        assertTrue(displaySource.contains("terminalInput.charTyped(codePoint)"))
    }

    @Test
    fun computerScreenHidesDisplayTextureWhilePoweredOff() {
        assertTrue(displaySource.contains("if (!menu.isComputerOn) return"))
        assertTrue(displaySource.contains("retainedObserver?.presentation() ?: return"))
    }

    @Test
    fun computerScreenLeavesReplicaLifetimeToTheObserverRegistry() {
        assertTrue(displaySource.contains("retainedObserver?.close()"))
        assertFalse(displaySource.contains("lastMenuPowerState"))
        assertFalse(displaySource.contains("resetDisplayBuffer"))
        assertFalse(displaySource.contains("clientSide"))
    }

    @Test
    fun computerScreenReliesOnRetainedStateChangesAcrossReboot() {
        assertFalse(displaySource.contains("resetDisplayBufferForRuntimeRestart"))
        assertFalse(terminalSource.contains("resetDisplayBufferForRuntimeRestart"))
        assertTrue(terminalSource.contains("ControlInputEvent(ComputerControlAction.REBOOT)"))
        assertFalse(notebookSource.contains("resetDisplayBufferForRuntimeRestart"))
        assertTrue(notebookSource.contains("ComputerControlAction.REBOOT"))
    }

    @Test
    fun computerScreenUsesGpu0ResolutionForDisplayEndpoint() {
        assertTrue(displaySource.contains("RetainedDisplayGeometryCompiler.LOGICAL_WIDTH"))
        assertTrue(displaySource.contains("RetainedDisplayGeometryCompiler.LOGICAL_HEIGHT"))
        assertFalse(displaySource.contains("terminalColumns * TerminalFontConstants.FONT_WIDTH"))
        assertFalse(displaySource.contains("terminalRows * TerminalFontConstants.FONT_HEIGHT"))
        assertTrue(notebookSource.contains("displayResolutionText(currentDisplayWidth(), currentDisplayHeight())"))
        assertFalse(notebookSource.contains("TERMINAL_COLUMNS * TerminalFontConstants.FONT_WIDTH"))
        assertFalse(notebookSource.contains("TERMINAL_ROWS * TerminalFontConstants.FONT_HEIGHT"))
    }

    @Test
    fun computerScreenDisplaysGpu0AtNativeSizeInsideTerminalSurface() {
        assertTrue(displaySource.contains("currentDisplayBounds(layout: WorkbenchTerminalLayout)"))
        assertTrue(displaySource.contains("layout.terminalSurfaceBounds"))
        assertTrue(displaySource.contains("retainedRenderer.draw(guiGraphics, currentDisplayBounds(currentLayout()), presentation)"))
        assertTrue(terminalSource.contains("val displayBounds = currentDisplayBounds(layout)"))
        assertTrue(terminalSource.contains(".size(displayBounds.width, displayBounds.height)"))
        assertTrue(notebookSource.contains("val displayBounds = currentDisplayBounds(layout)"))
        assertTrue(notebookSource.contains(".size(displayBounds.width, displayBounds.height)"))
        assertFalse(displaySource.contains("DisplayTextureCache"))
    }
}
