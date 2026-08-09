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

package ru.lazyhat.compukterkraft.core.bootstrap

import ru.lazyhat.compukterkraft.core.platform.api.PlatformBlockRegistrar
import ru.lazyhat.compukterkraft.core.platform.api.PlatformClientHooks
import ru.lazyhat.compukterkraft.core.platform.api.PlatformMenuRegistrar
import ru.lazyhat.compukterkraft.core.platform.api.PlatformNetworkRegistrar

object CommonModBootstrap {
    fun registerCommon(
        blocks: PlatformBlockRegistrar,
        menus: PlatformMenuRegistrar,
        network: PlatformNetworkRegistrar,
        clientHooks: PlatformClientHooks,
    ) {
        blocks.registerBlock(CommonBlockDescriptor.Notebook)
        blocks.registerBlock(CommonBlockDescriptor.AdvancedNotebook)
        blocks.registerBlock(CommonBlockDescriptor.Workbench)
        menus.registerMenu(CommonMenuDescriptor.Computer)
        menus.registerMenu(CommonMenuDescriptor.Workbench)

        CommonNetworkProtocol.serverboundChannels.forEach(network::registerServerbound)
        CommonNetworkProtocol.clientboundChannels.forEach(network::registerClientbound)

        clientHooks.registerClientScreens()
    }
}
