package ru.lazyhat.compukterkraft

import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.lazyhat.compukterkraft.block.ModBlocks
import ru.lazyhat.compukterkraft.item.ModItems
import thedarkcolour.kotlinforforge.forge.MOD_BUS
import thedarkcolour.kotlinforforge.forge.runForDist

@Mod(CompukterCraftMod.ID)
object CompukterCraftMod {
    const val ID = "compuktercraft"

    val LOGGER: Logger = LogManager.getLogger(ID)

    init {
        LOGGER.log(Level.INFO, "$ID has started!")

        ModItems.REGISTRY.register(MOD_BUS)
        ModBlocks.REGISTRY.register(MOD_BUS)

        val obj =
            runForDist(
                clientTarget = {
                    MOD_BUS.addListener(::onClientSetup)
                    Minecraft.getInstance()
                },
                serverTarget = {
                    MOD_BUS.addListener(::onServerSetup)
                    "test"
                },
            )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing client... with Compukter Craft!")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.log(Level.INFO, "Initializing server... with Compukter Craft!")
    }
}
