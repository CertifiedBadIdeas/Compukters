package ru.lazyhat.compuktercraft

import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod

@Mod.EventBusSubscriber(modid = CompukterCraftMod.ID, value = [Dist.CLIENT], bus = Mod.EventBusSubscriber.Bus.MOD)
object ForgeClientRegistry {
    init {
        CompukterCraftMod.LOGGER.info("ForgeClientRegistry init")
    }

    @SubscribeEvent
    fun registerReloadListeners(event: RegisterClientReloadListenersEvent) {
        ClientRegistry.registerReloadListeners(
            { reloadListener: PreparableReloadListener -> event.registerReloadListener(reloadListener) },
            Minecraft.getInstance(),
        )
    }
}
