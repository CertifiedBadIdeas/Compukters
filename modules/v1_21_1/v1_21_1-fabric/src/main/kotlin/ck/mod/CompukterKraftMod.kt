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

import ck.mod.context.ComputerIdentitySavedData
import ck.mod.context.ServerContext
import ck.mod.network.ClientNetworking
import ck.mod.network.server.ServerNetworking
import ck.mod.platform.NetworkHandler
import net.fabricmc.api.ModInitializer

class CompukterKraftMod : ModInitializer {
    override fun onInitialize() {
        LOGGER.info { "$MOD_ID has started!" }

        ModRegistry.register()
        NetworkHandler.setup()
        ServerNetworking.playerSender = NetworkHandler::sendToPlayer
        ClientNetworking.serverSender = NetworkHandler::sendToServer
        ServerContext.idAllocator = { server -> ComputerIdentitySavedData.get(server).allocateComputerId() }
        FabricCommonHooks.register()
    }
}
