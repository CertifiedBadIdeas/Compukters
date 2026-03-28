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
package ck.mod.ui.render

import ck.lang.runtime.ScreenBufferSnapshot
import ck.mod.gui.WorkbenchTerminalLayout
import ck.mod.ui.dsl.UiRenderer
import ck.mod.ui.dsl.buildTerminalUi
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

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
        snapshot: ScreenBufferSnapshot,
        focused: Boolean,
    ) {
        val nodes = buildTerminalUi(leftPos, topPos, imageWidth, imageHeight, layout, snapshot, focused)
        UiRenderer.render(graphics, font, nodes)
    }
}
