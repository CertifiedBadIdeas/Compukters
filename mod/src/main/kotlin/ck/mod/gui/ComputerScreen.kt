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

import ck.mod.gui.ComputerBorderRenderer.BORDER
import ck.mod.menu.AbstractComputerMenu
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * A GUI for computers which renders the terminal (and border), but with no UI elements.
 *
 *
 * This is used by computers and pocket computers.
 *
 * @param <T> The concrete type of the associated menu.
</T> */
class ComputerScreen<T : AbstractComputerMenu>(
    container: T,
    player: Inventory,
    title: Component,
) : AbstractComputerScreen<T>(container, player, title, BORDER) {
    init {
        // LOGGER.info("ComputerID: ComputerScreen init")
        imageWidth = TerminalWidget.getWidth(terminalData.width) + BORDER * 2 + AbstractComputerMenu.SIDEBAR_WIDTH
        imageHeight = TerminalWidget.getHeight(terminalData.height) + BORDER * 2
    }

    override fun createTerminal(): TerminalWidget =
        TerminalWidget(terminalData, input, leftPos + AbstractComputerMenu.SIDEBAR_WIDTH + BORDER, topPos + BORDER)

    public override fun renderBg(
        graphics: GuiGraphics,
        partialTicks: Float,
        mouseX: Int,
        mouseY: Int,
    ) {
        // Draw a border around the terminal
        val terminal = getTerminal()
        val spriteRenderer =
            SpriteRenderer.createForGui(
                graphics,
                RenderTypes.GUI_SPRITES,
            )
        val computerTextures =
            GuiSprites.getComputerTextures(
                family,
            )

        ComputerBorderRenderer.render(
            spriteRenderer,
            computerTextures,
            terminal.x,
            terminal.y,
            terminal.getWidth(),
            terminal.getHeight(),
            false,
        )
        ComputerSidebar.renderBackground(spriteRenderer, computerTextures, leftPos, topPos + sidebarYOffset)
        graphics.flush() // Flush to ensure background textures are drawn before foreground.
    }
}
