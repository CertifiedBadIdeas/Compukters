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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommonModBootstrapTest {
    @Test
    fun registersComputerContentAndNetworkMessagesThroughPlatformPorts() {
        val blocks = RecordingBlockRegistrar()
        val menus = RecordingMenuRegistrar()
        val network = RecordingNetworkRegistrar()
        val clientHooks = RecordingClientHooks()

        CommonModBootstrap.registerCommon(blocks, menus, network, clientHooks)

        assertEquals(
            listOf(
                CommonBlockDescriptor.Notebook,
                CommonBlockDescriptor.AdvancedNotebook,
                CommonBlockDescriptor.Workbench,
            ),
            blocks.blocks,
        )
        assertEquals(listOf(CommonMenuDescriptor.Computer, CommonMenuDescriptor.Workbench), menus.menus)
        assertEquals(
            listOf(
                "computer_action",
                "key_event",
                "mouse_event",
                "paste_event",
                "workbench_workspace_request",
                "workbench_input",
            ),
            network.serverbound,
        )
        assertEquals(
            listOf(
                "chat_table",
                "computer_terminal",
                "workbench_workspace",
                "workbench_terminal",
            ),
            network.clientbound,
        )
        assertEquals(1, clientHooks.registrationCalls)
    }

    private class RecordingBlockRegistrar : PlatformBlockRegistrar {
        val blocks = mutableListOf<CommonBlockDescriptor>()

        override fun registerBlock(descriptor: CommonBlockDescriptor) {
            blocks += descriptor
        }
    }

    @Test
    fun exposesWorkbenchDescriptors() {
        assertTrue(CommonBlockDescriptor.entries.any { it.name == "Workbench" })
        assertTrue(CommonMenuDescriptor.entries.any { it.name == "Workbench" })
        assertTrue(CommonNetworkProtocol.serverboundChannels.contains("workbench_workspace_request"))
        assertTrue(CommonNetworkProtocol.serverboundChannels.contains("workbench_input"))
        assertTrue(CommonNetworkProtocol.clientboundChannels.contains("workbench_workspace"))
        assertTrue(CommonNetworkProtocol.clientboundChannels.contains("workbench_terminal"))
    }

    private class RecordingMenuRegistrar : PlatformMenuRegistrar {
        val menus = mutableListOf<CommonMenuDescriptor>()

        override fun registerMenu(descriptor: CommonMenuDescriptor) {
            menus += descriptor
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
