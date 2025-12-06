package ru.lazyhat.compuktercraft

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.server.packs.resources.PreparableReloadListener
import ru.lazyhat.compuktercraft.gui.ComputerScreen
import ru.lazyhat.compuktercraft.gui.GuiSprites
import java.util.function.Consumer

object ClientRegistry {
    fun registerMainThread() {
        try {
            MenuScreens.register(
                ModRegistry.Menus.COMPUTER.get(),
                { container, inventory, title -> ComputerScreen(container, inventory, title) },
            )
            CompukterCraftMod.LOGGER.info("ClientRegistry: ComputerScreen successfully registered")
        } catch (e: Exception) {
            CompukterCraftMod.LOGGER.error("ClientRegistry: ComputerScreen registered with error ${e.message}")
        }
    }

    fun registerReloadListeners(
        register: Consumer<PreparableReloadListener>,
        minecraft: Minecraft,
    ) {
        register.accept(GuiSprites.initialize(minecraft.getTextureManager()))
    }
}
