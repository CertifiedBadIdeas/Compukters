// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.gui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.gui.ComputerBorderRenderer.BORDER
import ru.lazyhat.compuktercraft.menu.AbstractComputerMenu

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
        CompukterCraftMod.LOGGER.info("ComputerID: ComputerScreen init")
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
