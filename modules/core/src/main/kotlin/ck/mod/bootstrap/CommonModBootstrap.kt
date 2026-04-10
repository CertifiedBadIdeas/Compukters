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

package ck.mod.bootstrap

import ck.mod.platform.api.PlatformBlockRegistrar
import ck.mod.platform.api.PlatformClientHooks
import ck.mod.platform.api.PlatformMenuRegistrar
import ck.mod.platform.api.PlatformNetworkRegistrar

object CommonModBootstrap {
    fun registerCommon(
        blocks: PlatformBlockRegistrar,
        menus: PlatformMenuRegistrar,
        network: PlatformNetworkRegistrar,
        clientHooks: PlatformClientHooks,
    ) {
        blocks.registerBlock(CommonBlockDescriptor.ComputerAdvanced)
        menus.registerMenu(CommonMenuDescriptor.Computer)

        CommonNetworkProtocol.serverboundChannels.forEach(network::registerServerbound)
        CommonNetworkProtocol.clientboundChannels.forEach(network::registerClientbound)

        clientHooks.registerClientScreens()
    }
}
