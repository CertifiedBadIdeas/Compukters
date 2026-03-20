package ru.lazyhat.compuktercraft

import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Mod(CompukterCraftMod.ID)
@EventBusSubscriber(modid = CompukterCraftMod.ID, bus = EventBusSubscriber.Bus.MOD)
open class CompukterCraftMod {
    companion object {
        const val ID = "compuktercraft"
    }

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

        // checkScriptingDependency()

        // ModRegistry.register()

//        safeRunForDist(
//            {
//                // MOD_BUS.addListener(::onClientSetup)
//            },
//            {
//                // MOD_BUS.addListener(::onServerSetup)
//            },
//        )

        // NetworkHandler.setup()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client... with Compukter Craft!")
        // event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing server... with Compukter Craft!")
    }
}