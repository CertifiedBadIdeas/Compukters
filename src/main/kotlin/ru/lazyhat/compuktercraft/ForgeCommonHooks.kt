package ru.lazyhat.compuktercraft

import net.minecraftforge.event.server.ServerStartingEvent
import net.minecraftforge.event.server.ServerStoppingEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import ru.lazyhat.compuktercraft.context.ServerContext

@Mod.EventBusSubscriber(modid = CompukterCraftMod.ID)
object ForgeCommonHooks {
    @SubscribeEvent
    fun onServerStarting(event: ServerStartingEvent) {
        ServerContext.create(event.server)
    }

    @SubscribeEvent
    fun onServerStopping(event: ServerStoppingEvent) {
        ServerContext.close()
    }
}
