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

package ck.mod.data

import ck.mod.Config
import ck.mod.block.ComputerFamily
import ck.mod.computer.ServerComputer
import ck.mod.gui.TerminalState
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.item.ItemStack

class ComputerContainerData private constructor(
    val family: ComputerFamily,
    val terminalState: TerminalState,
    val displayStack: ItemStack,
    val uploadMaxSize: Int,
) : IContainerData {
    constructor(buffer: FriendlyByteBuf) : this(
        buffer.readEnum(ComputerFamily::class.java),
        TerminalState(buffer),
        buffer.readItem(),
        buffer.readInt().also {
            // LOGGER.info("ComputerContainerData init from buffer")
        },
    )

    override fun toBytes(buffer: FriendlyByteBuf) {
        buffer.writeEnum(family)
        terminalState.write(buffer)
        buffer.writeItem(displayStack)
        buffer.writeInt(uploadMaxSize)
        // LOGGER.info("ComputerContainerData write to buffer")
    }

    constructor(computer: ServerComputer, displayStack: ItemStack) : this(
        computer.family.also {
            // LOGGER.info("ComputerContainerData standard init")
        },
        TerminalState.create(computer.terminal),
        displayStack,
        Config.uploadMaxSize,
    )
}
