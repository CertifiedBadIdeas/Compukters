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
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.context.ServerContext
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.network.server.ServerNetworking
import ru.lazyhat.compukterkraft.impl.context.getComputerIdentitySavedData
import ru.lazyhat.compukterkraft.impl.platform.NetworkHandler

@Mod(MOD_ID)
class CompukterKraftMod(
    modEventBus: IEventBus,
    dist: Dist,
) {
    init {
        LOGGER.info { "$MOD_ID has started!" }

        ModRegistry.register(modEventBus)
        ModObjects.computerBlockEntityType = {
            @Suppress("UNCHECKED_CAST")
            ModRegistry.BlockEntities.COMPUTER_ADVANCED.get() as BlockEntityType<ComputerBlockEntity>
        }
        ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER.get() }
        ModObjects.openComputerMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            player.openMenu(
                SimpleMenuProvider(
                    computer,
                    computer.name,
                ),
            ) { buffer ->
                menuData.toBytes(buffer)
            }
        }
        ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED.get() }
        ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID.get() }
        ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE.get() }
        NetworkHandler.setup(modEventBus)
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer
        ServerContext.idAllocator = { server -> getComputerIdentitySavedData(server).allocateComputerId() }

        if (dist != Dist.CLIENT) {
            modEventBus.addListener(::onServerSetup)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onServerSetup(event: FMLDedicatedServerSetupEvent) {
        LOGGER.info { "Initializing server... with $MOD_NAME!" }
    }
}
