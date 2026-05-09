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

import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.computer.block.checkUsable
import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffer
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta

/**
 * Type-safe representation of which side of the network this menu lives on.
 */
sealed interface MenuSide {
    /**
     * Server-side state: owns [RuntimeDevice] and [ServerInputState].
     */
    class Server(
        val device: RuntimeDevice,
        val input: ServerInputState<out AbstractComputerMenu>,
    ) : MenuSide

    /**
     * Client-side state: owns the [ClientDisplayBuffer] attached by the
     * currently-open computer screen.
     */
    class Client : MenuSide {
        var displayBuffer: ClientDisplayBuffer? = null
            private set

        fun attachDisplayBuffer(buffer: ClientDisplayBuffer) {
            val current = displayBuffer
            displayBuffer =
                if (
                    current == null ||
                    current.displayId != buffer.displayId ||
                    current.width != buffer.width ||
                    current.height != buffer.height
                ) {
                    buffer
                } else {
                    current
                }
        }

        fun detachDisplayBuffer() {
            displayBuffer = null
        }

        fun applyDisplayFrame(frame: DisplayFrameDelta) {
            displayBuffer?.apply(frame)
        }
    }
}

abstract class AbstractComputerMenu(
    type: MenuType<out AbstractComputerMenu>,
    id: Int,
    private val canUse: (Player) -> Boolean,
    override val family: DeviceFamily,
    computer: RuntimeDevice?,
    containerData: ComputerContainerData?,
) : AbstractContainerMenu(type, id),
    ComputerMenu {
    val uploadMaxSize: Int

    private val data: ContainerData =
        if (computer == null) {
            SimpleContainerData(1)
        } else {
            SingleContainerData { if (computer.isOn) 1 else 0 }
        }

    /**
     * Type-safe side discriminator.
     * On the server: [MenuSide.Server] — holds the [RuntimeDevice] + input.
     * On the client: [MenuSide.Client] — holds the [ClientDisplayBuffer]
     * attached by the open computer screen.
     */

    override val side: MenuSide =
        if (computer != null) {
            MenuSide.Server(computer, ServerInputState(this))
        } else {
            MenuSide.Client()
        }

    /** Whether the computer is currently on (synced from server via [ContainerData]). */
    val isComputerOn: Boolean get() = data.get(0) == 1

    val displayStack: ItemStack = containerData?.displayStack ?: ItemStack.EMPTY

    init {
        addDataSlots(data)

        uploadMaxSize = containerData?.uploadMaxSize ?: Config.uploadMaxSize
    }

    override fun stillValid(player: Player): Boolean {
        val server = side as? MenuSide.Server
        return (server == null || server.device.family.checkUsable(player)) && canUse(player)
    }

    override fun handleDisplayFrame(frame: DisplayFrameDelta) {
        val client =
            side as? MenuSide.Client
                ?: throw UnsupportedOperationException("Cannot apply display frame on the server")
        client.applyDisplayFrame(frame)
    }

    override fun removed(player: Player) {
        super.removed(player)
        (side as? MenuSide.Server)?.input?.close()
    }
}
