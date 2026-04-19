package ru.lazyhat.compukterkraft.common.ui.program

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import ru.lazyhat.compukterkraft.common.computer.menu.AbstractComputerMenu
import ru.lazyhat.compukterkraft.common.computer.screen.ComputerScreen
import ru.lazyhat.compukterkraft.core.ui.program.ScreenRuntimeExecutor

abstract class DslContainerScreen<T : AbstractComputerMenu>(
    menu: T,
    inventory: Inventory,
    title: Component,
) : ComputerScreen<T>(menu, inventory, title) {
    protected fun renderProgram(
        graphics: GuiGraphics,
        executor: ScreenRuntimeExecutor,
    ) {
        executor.render(GuiGraphicsRenderBackend(graphics, font))
    }
}
