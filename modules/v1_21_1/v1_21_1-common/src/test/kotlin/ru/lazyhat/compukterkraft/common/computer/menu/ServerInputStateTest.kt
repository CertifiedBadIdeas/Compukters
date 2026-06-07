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
package ru.lazyhat.compukterkraft.common.computer.menu

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.device.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerInputStateTest {
    @Test
    fun closeReleasesHeldKeysThroughKeyUpEventsOnce() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val device = RecordingRuntimeDevice()
        val menu = TestComputerMenu(device)
        val input = menu.serverSide.input

        input.accept(KeyInputEvent.Down(key = 257, repeat = false))
        input.close()
        input.close()

        assertEquals(
            listOf(
                QueuedEvent("key", listOf(257, false)),
                QueuedEvent("key_up", listOf(257)),
            ),
            device.events,
        )
    }

    @Test
    fun controlRebootCallsRuntimeDeviceReboot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()

        val device = RecordingRuntimeDevice()
        val menu = TestComputerMenu(device)

        menu.serverSide.input.accept(ControlInputEvent(ComputerControlAction.REBOOT))

        assertEquals(1, device.rebootCalls)
        assertEquals(0, device.turnOnCalls)
        assertEquals(0, device.shutdownCalls)
        assertEquals(emptyList(), device.events)
    }

    private data class QueuedEvent(
        val name: String,
        val arguments: List<Any>,
    )

    private class RecordingRuntimeDevice : RuntimeDevice {
        val events = mutableListOf<QueuedEvent>()
        var turnOnCalls = 0
        var shutdownCalls = 0
        var rebootCalls = 0

        override val deviceId: Int = 1
        override val isOn: Boolean = true
        override val family: DeviceFamily = DeviceFamily.NORMAL
        override var label: String? = null

        override fun turnOn() {
            turnOnCalls += 1
        }

        override fun shutdown() {
            shutdownCalls += 1
        }

        override fun reboot() {
            rebootCalls += 1
        }

        override fun serverTick() = Unit

        override fun close() = Unit

        override fun queueEvent(
            event: String,
            arguments: Array<Any>,
        ) {
            events += QueuedEvent(event, arguments.toList())
        }

        override fun attachDisplaySession(
            playerUuid: UUID,
            containerId: Int,
            displayId: Int,
            width: Int,
            height: Int,
        ) = Unit

        override fun resizeDisplaySession(
            playerUuid: UUID,
            displayId: Int,
            width: Int,
            height: Int,
        ) = Unit

        override fun detachDisplaySession(
            playerUuid: UUID,
            displayId: Int,
        ) = Unit
    }

    private class TestComputerMenu(
        device: RuntimeDevice,
    ) : AbstractContainerMenu(null, 1),
        ComputerMenu {
        @Suppress("UNCHECKED_CAST")
        override val side: MenuSide =
            MenuSide.Server(
                device,
                ServerInputState(this) as ServerInputState<out AbstractComputerMenu>,
            )
        override val family: DeviceFamily = DeviceFamily.NORMAL

        override fun handleDisplayFrame(frame: DisplayFrameDelta) = Unit

        override fun quickMoveStack(
            player: Player,
            index: Int,
        ): ItemStack = ItemStack.EMPTY

        override fun stillValid(player: Player): Boolean = true
    }
}
