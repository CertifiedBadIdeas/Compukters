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
package ru.lazyhat.compukterkraft.common.ui.render

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import ru.lazyhat.compukterkraft.common.ui.dsl.UiRenderer
import ru.lazyhat.compukterkraft.core.gui.WorkbenchTerminalLayout
import ru.lazyhat.compukterkraft.core.ui.dsl.buildTerminalUi
import ru.lazyhat.compukterkraft.core.ui.workbench.WorkbenchTerminalViewState

/**
 * Renders a terminal panel with chrome (borders, status bar) and the character grid.
 *
 * Delegates layout description to [buildTerminalUi] (pure function) and rendering to [UiRenderer].
 */
object WorkbenchTerminalRenderer {
    fun render(
        graphics: GuiGraphics,
        font: Font,
        leftPos: Int,
        topPos: Int,
        imageWidth: Int,
        imageHeight: Int,
        layout: WorkbenchTerminalLayout,
        terminalState: WorkbenchTerminalViewState,
        focused: Boolean,
        showFocusHint: Boolean,
        poweredOffText: String,
        connectingText: String,
        statusRightInset: Int = 0,
        drawWindowBackground: Boolean = true,
    ) {
        val nodes =
            buildTerminalUi(
                leftPos = leftPos,
                topPos = topPos,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                layout = layout,
                terminalState = terminalState,
                focused = focused,
                showFocusHint = showFocusHint,
                poweredOffText = poweredOffText,
                connectingText = connectingText,
                statusRightInset = statusRightInset,
                drawWindowBackground = drawWindowBackground,
            )
        UiRenderer.render(graphics, font, nodes)
    }
}
