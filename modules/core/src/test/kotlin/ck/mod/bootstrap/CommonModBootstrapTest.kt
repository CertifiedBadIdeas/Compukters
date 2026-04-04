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
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonModBootstrapTest {
    @Test
    fun registersComputerContentAndNetworkMessagesThroughPlatformPorts() {
        val blocks = RecordingBlockRegistrar()
        val menus = RecordingMenuRegistrar()
        val network = RecordingNetworkRegistrar()
        val clientHooks = RecordingClientHooks()

        CommonModBootstrap.registerCommon(blocks, menus, network, clientHooks)

        assertEquals(listOf("computer_advanced"), blocks.blockNames)
        assertEquals(listOf("computer"), menus.menuNames)
        assertEquals(
            listOf(
                "computer_action",
                "key_event",
                "mouse_event",
                "paste_event",
                "computer_workspace_request",
            ),
            network.serverbound,
        )
        assertEquals(
            listOf(
                "chat_table",
                "computer_terminal",
                "computer_workspace",
            ),
            network.clientbound,
        )
        assertEquals(1, clientHooks.registrationCalls)
    }

    private class RecordingBlockRegistrar : PlatformBlockRegistrar {
        val blockNames = mutableListOf<String>()

        override fun registerBlock(name: String) {
            blockNames += name
        }
    }

    private class RecordingMenuRegistrar : PlatformMenuRegistrar {
        val menuNames = mutableListOf<String>()

        override fun registerMenu(name: String) {
            menuNames += name
        }
    }

    private class RecordingNetworkRegistrar : PlatformNetworkRegistrar {
        val serverbound = mutableListOf<String>()
        val clientbound = mutableListOf<String>()

        override fun registerServerbound(channel: String) {
            serverbound += channel
        }

        override fun registerClientbound(channel: String) {
            clientbound += channel
        }
    }

    private class RecordingClientHooks : PlatformClientHooks {
        var registrationCalls = 0

        override fun registerClientScreens() {
            registrationCalls += 1
        }
    }
}
