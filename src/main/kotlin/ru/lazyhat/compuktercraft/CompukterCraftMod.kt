package ru.lazyhat.compuktercraft

import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.lazyhat.compuktercraft.platform.NetworkHandler
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.runForDist

@Mod(CompukterCraftMod.ID)
@EventBusSubscriber(modid = CompukterCraftMod.ID, bus = EventBusSubscriber.Bus.MOD)
object CompukterCraftMod {
    const val ID = "compuktercraft"

    val LOGGER: Logger = LogManager.getLogger(ID)
    val installedVersion =
        ModList
            .get()
            .getModContainerById(ID)
            .map {
                it.modInfo.version.toString()
            }.orElse("unknown")

    init {
        LOGGER.log(Level.INFO, "$ID has started!")

        ModRegistry.register()

        runForDist(
            clientTarget = {
                MOD_BUS.addListener(::onClientSetup)
            },
            serverTarget = {
                MOD_BUS.addListener(::onServerSetup)
            },
        )

        NetworkHandler.setup()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client... with Compukter Craft!")
        event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing server... with Compukter Craft!")
    }
}
