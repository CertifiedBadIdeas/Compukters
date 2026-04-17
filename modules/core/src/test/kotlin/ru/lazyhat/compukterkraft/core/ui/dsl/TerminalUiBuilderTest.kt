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

package ru.lazyhat.compukterkraft.core.ui.dsl

import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalMetrics
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalUiBuilderTest {
    private val snapshot = ScreenBufferSnapshot.empty(width = 16, height = 8, colour = true)
    private val layout = WorkbenchTerminalMetrics.layout(0, 0, 480, 280, 16, 8)

    @Test
    fun poweredOffViewShowsPlaceholderWithoutTerminalTitleOrFocusHint() {
        val nodes =
            buildTerminalUi(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                layout = layout,
                terminalState = WorkbenchTerminalViewState.PoweredOff,
                focused = false,
                showFocusHint = false,
                poweredOffText = "Computer is off. Turn it on first.",
                connectingText = "Connecting...",
            )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Computer is off. Turn it on first." in texts)
        assertFalse("Terminal" in texts)
        assertFalse("Click terminal to focus input" in texts)
        assertTrue(nodes.none { it is TerminalView })
    }

    @Test
    fun activeViewShowsFocusHintOnlyWhenTerminalIsUnfocused() {
        val nodes =
            buildTerminalUi(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                layout = layout,
                terminalState = WorkbenchTerminalViewState.Active(snapshot),
                focused = false,
                showFocusHint = true,
                poweredOffText = "Computer is off. Turn it on first.",
                connectingText = "Connecting...",
            )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Click terminal to focus input" in texts)
        assertFalse("Computer is off. Turn it on first." in texts)
        assertTrue(nodes.any { it is TerminalView })
    }

    @Test
    fun connectingViewShowsOnlyPlaceholderText() {
        val nodes =
            buildTerminalUi(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                layout = layout,
                terminalState = WorkbenchTerminalViewState.Connecting,
                focused = false,
                showFocusHint = false,
                poweredOffText = "Computer is off. Turn it on first.",
                connectingText = "Connecting...",
            )

        val texts = nodes.filterIsInstance<Text>().map { it.text }

        assertTrue("Connecting..." in texts)
        assertFalse("Click terminal to focus input" in texts)
        assertFalse(texts.any { it.contains(" x ") })
        assertTrue(nodes.none { it is TerminalView })
    }

    @Test
    fun activeViewReservesRightStatusAreaForExternalControls() {
        val nodes =
            buildTerminalUi(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                layout = layout,
                terminalState = WorkbenchTerminalViewState.Active(snapshot),
                focused = true,
                showFocusHint = false,
                poweredOffText = "Computer is off. Turn it on first.",
                connectingText = "Connecting...",
                statusRightInset = 52,
            )

        val sizeText = nodes.filterIsInstance<RightAlignedText>().single { it.text == "16 x 8" }

        assertEquals(layout.statusBounds.width - 24 - 52, sizeText.areaWidth)
    }

    @Test
    fun dockViewDoesNotPaintFullscreenWindowBackground() {
        val nodes =
            buildTerminalUi(
                leftPos = 0,
                topPos = 0,
                imageWidth = 480,
                imageHeight = 280,
                layout = layout,
                terminalState = WorkbenchTerminalViewState.Active(snapshot),
                focused = true,
                showFocusHint = false,
                poweredOffText = "Computer is off. Turn it on first.",
                connectingText = "Connecting...",
                drawWindowBackground = false,
            )

        val fullscreenRect = nodes.filterIsInstance<Rect>().any { it.x == 0 && it.y == 0 && it.w == 480 && it.h == 280 }

        assertFalse(fullscreenRect)
    }
}
