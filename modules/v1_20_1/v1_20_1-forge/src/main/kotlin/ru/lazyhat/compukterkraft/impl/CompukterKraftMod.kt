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

package ru.lazyhat.compukterkraft.impl

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleMenuProvider
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.network.NetworkHooks
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.network.server.ServerNetworking
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.MOD_NAME
import ru.lazyhat.compukterkraft.impl.platform.NetworkHandler

@Mod(MOD_ID)
@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
class CompukterKraftMod(
    context: FMLJavaModLoadingContext,
) {
    init {
        LOGGER.info { "$MOD_ID has started!" }

        val modEventBus = context.modEventBus

        ModRegistry.register(modEventBus)
        ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED.get() }
        ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
        ModObjects.openComputerMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            NetworkHooks.openScreen(
                player,
                SimpleMenuProvider(
                    computer,
                    computer.name,
                ),
                menuData::toBytes,
            )
        }
        ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
        ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
        ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(::onClientSetup)
        } else {
            modEventBus.addListener(::onServerSetup)
        }

        NetworkHandler.setup()
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer
    }

    @Suppress("UNUSED_PARAMETER")
    fun onClientSetup(event: FMLClientSetupEvent) {
        LOGGER.info { "Initializing client... with $MOD_NAME!" }
        event.enqueueWork { ClientRegistry.registerMainThread() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info { "Initializing server... with $MOD_NAME!" }
    }
}
