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
import ru.lazyhat.compukterkraft.common.computer.client.ClientTerminalBuffer
import ru.lazyhat.compukterkraft.common.computer.context.ServerComputer
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.DeviceFamily

/**
 * Type-safe representation of which side of the network this menu lives on.
 */
sealed interface MenuSide {
    /**
     * Server-side state: owns [ServerComputer] and [ServerInputState].
     */
    class Server(
        val computer: ServerComputer,
        val input: ServerInputState<out AbstractComputerMenu>,
    ) : MenuSide

    /**
     * Client-side state: owns the [ClientTerminalBuffer] attached by the
     * currently-open terminal screen.
     */
    class Client : MenuSide {
        /**
         * Stream-I/O terminal buffer. `null` until [attachTerminalBuffer] is
         * called by the open [ComputerTerminalScreen][ru.lazyhat.compukterkraft.common.terminal.screen.ComputerTerminalScreen].
         */
        var terminalBuffer: ClientTerminalBuffer? = null
            private set

        fun attachTerminalBuffer(buffer: ClientTerminalBuffer) {
            terminalBuffer = buffer
        }

        fun detachTerminalBuffer() {
            terminalBuffer = null
        }

        fun applyStdoutBytes(bytes: ByteArray) {
            terminalBuffer?.applyStdoutBytes(bytes)
        }
    }
}

abstract class AbstractComputerMenu(
    type: MenuType<out AbstractComputerMenu>,
    id: Int,
    private val canUse: (Player) -> Boolean,
    override val family: DeviceFamily,
    computer: ServerComputer?,
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
     * On the server: [MenuSide.Server] — holds the [ServerComputer] + input.
     * On the client: [MenuSide.Client] — holds the [ClientTerminalBuffer]
     * attached by the open terminal screen.
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
        return (server == null || server.computer.checkUsable(player)) && canUse(player)
    }

    override fun handleStdoutBytes(bytes: ByteArray) {
        val client =
            side as? MenuSide.Client
                ?: throw UnsupportedOperationException("Cannot apply stdout bytes on the server")
        client.applyStdoutBytes(bytes)
    }

    override fun removed(player: Player) {
        super.removed(player)
        (side as? MenuSide.Server)?.input?.close()
    }
}
