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

package ck.mod

import ck.mod.data.ComputerContainerData
import ck.mod.binding.ModObjects
import ck.mod.context.ServerContext
import ck.mod.context.getComputerIdentitySavedData
import ck.mod.network.ClientNetworking
import ck.mod.network.server.ServerNetworking
import ck.mod.platform.NetworkHandler
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

class CompukterKraftMod : ModInitializer {
    override fun onInitialize() {
        LOGGER.info { "$MOD_ID has started!" }

        ModRegistry.register()
        ModObjects.computerBlockEntityType = { ModRegistry.BlockEntities.COMPUTER_ADVANCED }
        ModObjects.computerMenuType = { ModRegistry.Menus.COMPUTER }
        ModObjects.openComputerMenu = { player: ServerPlayer, computer, menuData: ComputerContainerData ->
            player.openMenu(
                object : ExtendedScreenHandlerFactory<ComputerContainerData> {
                    override fun createMenu(
                        id: Int,
                        inv: net.minecraft.world.entity.player.Inventory,
                        p: Player,
                    ) = computer.createMenu(id, inv, p)

                    override fun getDisplayName() = computer.name

                    override fun getScreenOpeningData(player: ServerPlayer): ComputerContainerData = menuData
                },
            )
        }
        ModObjects.blockNamedEntityLootConditionType = { ModRegistry.LootItemConditionTypes.BLOCK_NAMED }
        ModObjects.hasComputerIdLootConditionType = { ModRegistry.LootItemConditionTypes.HAS_ID }
        ModObjects.playerCreativeLootConditionType = { ModRegistry.LootItemConditionTypes.PLAYER_CREATIVE }
        NetworkHandler.setup()
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer
        ServerContext.idAllocator = { server -> getComputerIdentitySavedData(server).allocateComputerId() }
        FabricCommonHooks.register()
    }
}
